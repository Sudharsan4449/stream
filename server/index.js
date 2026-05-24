const express = require('express');
const cors = require('cors');
const fs = require('fs');
const path = require('path');
const os = require('os');

const app = express();
const PORT = process.env.PORT || 3001;

app.use(cors());
app.use(express.json());

let currentVideo = null; // Store information about the currently active video

const SUPPORTED_EXTS = ['.mp4', '.mkv', '.avi', '.mov', '.webm'];

// Recursive function to find video files up to a certain depth
function findVideos(dir, depth = 0, maxDepth = 4) {
  let results = [];
  if (depth > maxDepth) return results;
  
  if (!fs.existsSync(dir)) return results;

  try {
    const list = fs.readdirSync(dir);
    for (let file of list) {
      // Ignore hidden files and node_modules
      if (file.startsWith('.') || file === 'node_modules') continue;
      
      const filePath = path.join(dir, file);
      try {
        const stat = fs.statSync(filePath);
        if (stat.isDirectory()) {
          results = results.concat(findVideos(filePath, depth + 1, maxDepth));
        } else {
          const ext = path.extname(file).toLowerCase();
          if (SUPPORTED_EXTS.includes(ext)) {
            results.push({
              name: file,
              path: filePath,
              size: stat.size,
              ext: ext
            });
          }
        }
      } catch (err) {
        // Skip files with permission errors
      }
    }
  } catch (err) {
    // Skip unreadable directories
  }
  return results;
}

// API to list available videos from storage
app.get('/api/files', (req, res) => {
  let dirsToScan = [];
  const homedir = os.homedir();
  
  if (process.platform === 'android' || fs.existsSync('/data/data/com.termux')) {
    // Termux environment: look for standard Android storage symlinks created by termux-setup-storage
    const storageDir = path.join(homedir, 'storage');
    if (fs.existsSync(storageDir)) {
      dirsToScan.push(path.join(storageDir, 'movies'));
      dirsToScan.push(path.join(storageDir, 'downloads'));
      dirsToScan.push(path.join(storageDir, 'dcim'));
      dirsToScan.push(path.join(storageDir, 'shared', 'Movies'));
      dirsToScan.push(path.join(storageDir, 'shared', 'Download'));
    } else {
      // Fallback: search the current working directory
      dirsToScan.push(process.cwd());
    }
  } else {
    // Windows/Mac/Linux fallback for local testing
    dirsToScan.push(path.join(homedir, 'Videos'));
    dirsToScan.push(path.join(homedir, 'Downloads'));
    dirsToScan.push(process.cwd()); 
  }

  // Deduplicate and filter to only directories that actually exist
  dirsToScan = [...new Set(dirsToScan)].filter(dir => fs.existsSync(dir));

  let allVideos = [];
  for (const dir of dirsToScan) {
    allVideos = allVideos.concat(findVideos(dir));
  }

  // Deduplicate files by path (just in case overlapping symlinks caused duplicates)
  const uniqueVideos = [];
  const map = new Map();
  for (const item of allVideos) {
    if (!map.has(item.path)) {
      map.set(item.path, true);
      uniqueVideos.push(item);
    }
  }

  // Sort by size descending (usually larger files are the full movies we want)
  uniqueVideos.sort((a, b) => b.size - a.size);

  res.json({ videos: uniqueVideos, scannedDirs: dirsToScan });
});

// API to set the active video stream
app.post('/api/set-active', (req, res) => {
  const { videoPath } = req.body;
  if (!videoPath || !fs.existsSync(videoPath)) {
    return res.status(400).json({ error: 'Invalid or missing file path.' });
  }

  const stat = fs.statSync(videoPath);
  currentVideo = {
    path: videoPath,
    originalName: path.basename(videoPath),
    size: stat.size,
    mimetype: `video/${path.extname(videoPath).replace('.', '')}`
  };

  const streamUrl = `http://${req.hostname}:${PORT}/api/stream`;
  
  res.json({
    message: 'Video active for streaming',
    video: currentVideo,
    streamUrl
  });
});

// API to stream video to VLC with HTTP Range requests
app.get('/api/stream', (req, res) => {
  if (!currentVideo || !fs.existsSync(currentVideo.path)) {
    return res.status(404).send('No active video found.');
  }

  const videoPath = currentVideo.path;
  const stat = fs.statSync(videoPath);
  const fileSize = stat.size;
  const range = req.headers.range;

  if (range) {
    const parts = range.replace(/bytes=/, "").split("-");
    const start = parseInt(parts[0], 10);
    const end = parts[1] ? parseInt(parts[1], 10) : fileSize - 1;

    if(start >= fileSize) {
      res.status(416).send('Requested range not satisfiable\n'+start+' >= '+fileSize);
      return;
    }

    const chunksize = (end - start) + 1;
    const file = fs.createReadStream(videoPath, { start, end });
    const head = {
      'Content-Range': `bytes ${start}-${end}/${fileSize}`,
      'Accept-Ranges': 'bytes',
      'Content-Length': chunksize,
      'Content-Type': currentVideo.mimetype || 'video/mp4',
    };

    res.writeHead(206, head);
    file.pipe(res);
  } else {
    const head = {
      'Content-Length': fileSize,
      'Content-Type': currentVideo.mimetype || 'video/mp4',
    };
    res.writeHead(200, head);
    fs.createReadStream(videoPath).pipe(res);
  }
});

// Get current video status
app.get('/api/status', (req, res) => {
  if (currentVideo && fs.existsSync(currentVideo.path)) {
    res.json({ active: true, video: currentVideo });
  } else {
    res.json({ active: false });
  }
});

// Clear current video
app.delete('/api/clear', (req, res) => {
  currentVideo = null;
  res.json({ message: 'Video cleared' });
});

app.listen(PORT, '0.0.0.0', () => {
  console.log(`Streaming server running on http://0.0.0.0:${PORT}`);
});
