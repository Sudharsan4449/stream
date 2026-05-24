const express = require('express');
const cors = require('cors');
const multer = require('multer');
const fs = require('fs');
const path = require('path');

const app = express();
const PORT = process.env.PORT || 3001;

app.use(cors());
app.use(express.json());

const UPLOADS_DIR = path.join(__dirname, 'uploads');
if (!fs.existsSync(UPLOADS_DIR)) {
  fs.mkdirSync(UPLOADS_DIR);
}

// Multer storage configuration
const storage = multer.diskStorage({
  destination: (req, file, cb) => {
    cb(null, UPLOADS_DIR);
  },
  filename: (req, file, cb) => {
    // Keep the original extension, replace spaces and special characters
    const uniqueSuffix = Date.now() + '-' + Math.round(Math.random() * 1E9);
    const ext = path.extname(file.originalname);
    cb(null, file.fieldname + '-' + uniqueSuffix + ext);
  }
});

const upload = multer({ storage: storage });

let currentVideo = null; // Store information about the currently active video

// API to handle video upload from mobile
app.post('/api/upload', upload.single('video'), (req, res) => {
  if (!req.file) {
    return res.status(400).json({ error: 'No video file uploaded.' });
  }

  // If there's already a video, delete it to save space (no permanent storage)
  if (currentVideo && fs.existsSync(currentVideo.path)) {
    fs.unlinkSync(currentVideo.path);
  }

  currentVideo = {
    path: req.file.path,
    filename: req.file.filename,
    originalName: req.file.originalname,
    mimetype: req.file.mimetype,
    size: req.file.size
  };

  const streamUrl = `http://${req.hostname}:${PORT}/api/stream`;
  
  res.json({
    message: 'Video ready for streaming',
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
  if (currentVideo && fs.existsSync(currentVideo.path)) {
    fs.unlinkSync(currentVideo.path);
    currentVideo = null;
    res.json({ message: 'Video cleared' });
  } else {
    res.status(404).json({ error: 'No active video to clear' });
  }
});

app.listen(PORT, '0.0.0.0', () => {
  console.log(`Streaming server running on http://0.0.0.0:${PORT}`);
});
