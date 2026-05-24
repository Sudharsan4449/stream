'use client';

import { useState, useRef, useEffect } from 'react';

export default function Home() {
  const [videoFile, setVideoFile] = useState<File | null>(null);
  const [status, setStatus] = useState<string>('idle');
  const [progress, setProgress] = useState<number>(0);
  const [serverInfo, setServerInfo] = useState<{ active: boolean, video?: any } | null>(null);
  const [streamUrl, setStreamUrl] = useState<string>('');
  const [backendUrl, setBackendUrl] = useState<string>('');
  
  useEffect(() => {
    // Determine backend URL based on the current hostname
    const host = window.location.hostname || '127.0.0.1';
    const baseUrl = `http://${host}:3001`;
    setBackendUrl(baseUrl);
    
    // Check current status
    checkStatus(baseUrl);
  }, []);

  const checkStatus = async (url: string) => {
    try {
      const res = await fetch(`${url}/api/status`);
      const data = await res.json();
      setServerInfo(data);
      if (data.active) {
        setStreamUrl(`${url}/api/stream`);
      }
    } catch (e) {
      console.error('Failed to connect to backend', e);
    }
  };

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files && e.target.files.length > 0) {
      setVideoFile(e.target.files[0]);
    }
  };

  const uploadVideo = () => {
    if (!videoFile) return;
    
    setStatus('uploading');
    setProgress(0);

    const formData = new FormData();
    formData.append('video', videoFile);

    const xhr = new XMLHttpRequest();
    xhr.open('POST', `${backendUrl}/api/upload`, true);

    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) {
        const percentComplete = (e.loaded / e.total) * 100;
        setProgress(percentComplete);
      }
    };

    xhr.onload = () => {
      if (xhr.status === 200) {
        const response = JSON.parse(xhr.responseText);
        setStatus('success');
        setStreamUrl(response.streamUrl);
        checkStatus(backendUrl);
      } else {
        setStatus('error');
      }
    };

    xhr.onerror = () => {
      setStatus('error');
    };

    xhr.send(formData);
  };

  const clearVideo = async () => {
    try {
      await fetch(`${backendUrl}/api/clear`, { method: 'DELETE' });
      setVideoFile(null);
      setStreamUrl('');
      setServerInfo(null);
      setStatus('idle');
      setProgress(0);
      checkStatus(backendUrl);
    } catch (e) {
      console.error('Failed to clear video', e);
    }
  };

  return (
    <div className="min-h-screen bg-gray-900 text-white flex flex-col items-center justify-center p-6">
      <div className="max-w-md w-full bg-gray-800 rounded-2xl shadow-xl overflow-hidden">
        <div className="p-8">
          <h1 className="text-3xl font-bold text-center mb-2 bg-gradient-to-r from-blue-400 to-emerald-400 bg-clip-text text-transparent">
            VLC Streamer
          </h1>
          <p className="text-gray-400 text-center mb-8 text-sm">
            Stream local mobile videos directly to your TV
          </p>

          {serverInfo?.active ? (
            <div className="space-y-6 animate-in fade-in slide-in-from-bottom-4">
              <div className="bg-emerald-900/30 border border-emerald-500/30 rounded-xl p-4">
                <h3 className="text-emerald-400 font-semibold mb-1 flex items-center">
                  <span className="w-2 h-2 rounded-full bg-emerald-400 mr-2 animate-pulse"></span>
                  Active Stream
                </h3>
                <p className="text-sm text-gray-300 truncate mb-4" title={serverInfo.video?.originalName}>
                  {serverInfo.video?.originalName || 'video.mp4'}
                </p>
                
                <div className="bg-black/50 rounded-lg p-3 relative group">
                  <p className="text-xs text-gray-400 mb-1">Network Stream URL</p>
                  <code className="text-blue-300 break-all select-all block">
                    {streamUrl}
                  </code>
                </div>
              </div>

              <div className="space-y-3">
                <h4 className="text-sm font-medium text-gray-300 uppercase tracking-wider">How to play on TV:</h4>
                <ol className="text-sm text-gray-400 space-y-2 list-decimal list-inside pl-1">
                  <li>Open <strong>VLC Player</strong> on your TV</li>
                  <li>Go to <strong>Network Stream</strong> (or 'Open Network')</li>
                  <li>Enter the exact URL shown above</li>
                  <li>Press <strong>Play</strong></li>
                </ol>
              </div>

              <button
                onClick={clearVideo}
                className="w-full py-3 rounded-xl bg-red-500/10 text-red-400 hover:bg-red-500/20 font-medium transition-colors border border-red-500/20"
              >
                Stop & Clear Stream
              </button>
            </div>
          ) : (
            <div className="space-y-6">
              <div className="border-2 border-dashed border-gray-600 rounded-xl p-6 text-center hover:border-blue-400 transition-colors cursor-pointer relative group">
                <input
                  type="file"
                  accept="video/*"
                  onChange={handleFileChange}
                  className="absolute inset-0 w-full h-full opacity-0 cursor-pointer"
                />
                <div className="space-y-2">
                  <div className="w-12 h-12 bg-gray-700 rounded-full flex items-center justify-center mx-auto group-hover:bg-blue-500/20 group-hover:text-blue-400 transition-colors">
                    <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12"></path>
                    </svg>
                  </div>
                  <p className="text-sm font-medium text-gray-300">
                    {videoFile ? videoFile.name : 'Select a video to stream'}
                  </p>
                  <p className="text-xs text-gray-500">
                    MP4, MKV, AVI supported
                  </p>
                </div>
              </div>

              {status === 'uploading' && (
                <div className="space-y-2">
                  <div className="flex justify-between text-xs text-gray-400">
                    <span>Preparing stream...</span>
                    <span>{Math.round(progress)}%</span>
                  </div>
                  <div className="w-full h-2 bg-gray-700 rounded-full overflow-hidden">
                    <div 
                      className="h-full bg-blue-500 transition-all duration-300 ease-out"
                      style={{ width: `${progress}%` }}
                    ></div>
                  </div>
                </div>
              )}

              {status === 'error' && (
                <div className="text-red-400 text-sm text-center bg-red-500/10 p-3 rounded-lg border border-red-500/20">
                  Failed to prepare stream. Please try again.
                </div>
              )}

              <button
                onClick={uploadVideo}
                disabled={!videoFile || status === 'uploading'}
                className="w-full py-3 rounded-xl bg-blue-600 hover:bg-blue-500 disabled:opacity-50 disabled:hover:bg-blue-600 text-white font-medium transition-all shadow-lg shadow-blue-500/20 flex items-center justify-center space-x-2"
              >
                <span>{status === 'uploading' ? 'Processing...' : 'Start Streaming'}</span>
                {status !== 'uploading' && (
                  <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M14 5l7 7m0 0l-7 7m7-7H3"></path>
                  </svg>
                )}
              </button>
            </div>
          )}
        </div>
        
        <div className="bg-gray-800/50 p-4 border-t border-gray-700 flex items-center justify-between">
          <div className="flex items-center space-x-2">
            <div className={`w-2 h-2 rounded-full ${backendUrl ? 'bg-green-500' : 'bg-red-500'}`}></div>
            <span className="text-xs text-gray-400">
              Server: {backendUrl ? backendUrl : 'Connecting...'}
            </span>
          </div>
        </div>
      </div>
    </div>
  );
}
