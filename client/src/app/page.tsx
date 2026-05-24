'use client';

import { useState, useEffect } from 'react';

type VideoFile = {
  name: string;
  path: string;
  size: number;
  ext: string;
};

export default function Home() {
  const [status, setStatus] = useState<'idle' | 'loading' | 'error'>('loading');
  const [videos, setVideos] = useState<VideoFile[]>([]);
  const [serverInfo, setServerInfo] = useState<{ active: boolean, video?: any } | null>(null);
  const [streamUrl, setStreamUrl] = useState<string>('');
  const [backendUrl, setBackendUrl] = useState<string>('');
  
  useEffect(() => {
    // Determine backend URL based on the current hostname
    const host = window.location.hostname || '127.0.0.1';
    const baseUrl = `http://${host}:3001`;
    setBackendUrl(baseUrl);
    
    // Check current status and load files
    checkStatus(baseUrl);
    loadFiles(baseUrl);
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

  const loadFiles = async (url: string) => {
    setStatus('loading');
    try {
      const res = await fetch(`${url}/api/files`);
      const data = await res.json();
      setVideos(data.videos || []);
      setStatus('idle');
    } catch (e) {
      console.error('Failed to load files', e);
      setStatus('error');
    }
  };

  const selectVideo = async (videoPath: string) => {
    setStatus('loading');
    try {
      const res = await fetch(`${backendUrl}/api/set-active`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({ videoPath })
      });
      
      if (res.ok) {
        const response = await res.json();
        setStreamUrl(response.streamUrl);
        checkStatus(backendUrl);
      }
      setStatus('idle');
    } catch (e) {
      console.error('Failed to set active video', e);
      setStatus('error');
    }
  };

  const clearVideo = async () => {
    try {
      await fetch(`${backendUrl}/api/clear`, { method: 'DELETE' });
      setStreamUrl('');
      setServerInfo(null);
      checkStatus(backendUrl);
    } catch (e) {
      console.error('Failed to clear video', e);
    }
  };

  const formatSize = (bytes: number) => {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  };

  return (
    <div className="min-h-screen bg-gray-950 text-white flex flex-col items-center p-4 md:p-8">
      <div className="w-full max-w-2xl bg-gray-900 rounded-3xl shadow-2xl border border-gray-800 overflow-hidden">
        
        {/* Header */}
        <div className="p-6 md:p-8 border-b border-gray-800 bg-gray-900/50 backdrop-blur-sm">
          <div className="flex items-center justify-between mb-2">
            <h1 className="text-3xl font-bold bg-gradient-to-r from-blue-400 to-indigo-400 bg-clip-text text-transparent">
              StreamCast
            </h1>
            <div className="flex items-center space-x-2 bg-gray-800 px-3 py-1.5 rounded-full">
              <div className={`w-2 h-2 rounded-full ${backendUrl && status !== 'error' ? 'bg-emerald-400' : 'bg-red-500'} shadow-[0_0_8px_rgba(52,211,153,0.8)]`}></div>
              <span className="text-xs font-medium text-gray-300">
                {backendUrl ? 'Connected' : 'Offline'}
              </span>
            </div>
          </div>
          <p className="text-gray-400 text-sm">
            Stream local videos instantly to your TV via VLC. Zero uploads, zero extra storage.
          </p>
        </div>

        <div className="p-6 md:p-8">
          {serverInfo?.active ? (
            <div className="space-y-6 animate-in fade-in slide-in-from-bottom-4 duration-500">
              <div className="bg-emerald-950/40 border border-emerald-500/30 rounded-2xl p-5 relative overflow-hidden">
                <div className="absolute top-0 right-0 w-32 h-32 bg-emerald-500/10 blur-3xl rounded-full"></div>
                <h3 className="text-emerald-400 font-semibold mb-2 flex items-center">
                  <span className="relative flex h-3 w-3 mr-3">
                    <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75"></span>
                    <span className="relative inline-flex rounded-full h-3 w-3 bg-emerald-500"></span>
                  </span>
                  Now Broadcasting
                </h3>
                <p className="text-lg text-gray-200 truncate mb-1 font-medium" title={serverInfo.video?.originalName}>
                  {serverInfo.video?.originalName || 'video.mp4'}
                </p>
                <p className="text-sm text-emerald-500/70 mb-5">
                  {formatSize(serverInfo.video?.size)}
                </p>
                
                <div className="bg-black/40 rounded-xl p-4 border border-white/5 relative group backdrop-blur-sm">
                  <p className="text-xs text-gray-400 mb-2 uppercase tracking-wider font-semibold">Network Stream URL</p>
                  <div className="flex items-center justify-between bg-gray-900 rounded-lg p-3 border border-gray-700">
                    <code className="text-blue-300 text-sm break-all font-mono">
                      {streamUrl}
                    </code>
                  </div>
                </div>
              </div>

              <div className="bg-gray-800/50 rounded-2xl p-5 border border-gray-700/50">
                <h4 className="text-sm font-semibold text-gray-300 uppercase tracking-wider mb-3">How to play on your TV:</h4>
                <ol className="text-sm text-gray-400 space-y-3">
                  <li className="flex items-start">
                    <span className="flex items-center justify-center bg-gray-700 w-5 h-5 rounded-full text-xs text-white mr-3 shrink-0">1</span>
                    Open <strong>VLC Player</strong> on your Smart TV
                  </li>
                  <li className="flex items-start">
                    <span className="flex items-center justify-center bg-gray-700 w-5 h-5 rounded-full text-xs text-white mr-3 shrink-0">2</span>
                    Navigate to <strong>Network Stream</strong>
                  </li>
                  <li className="flex items-start">
                    <span className="flex items-center justify-center bg-gray-700 w-5 h-5 rounded-full text-xs text-white mr-3 shrink-0">3</span>
                    Enter the exact URL shown above and hit Play
                  </li>
                </ol>
              </div>

              <button
                onClick={clearVideo}
                className="w-full py-4 rounded-xl bg-gray-800 hover:bg-red-500/20 hover:text-red-400 hover:border-red-500/30 text-gray-300 font-medium transition-all duration-300 border border-transparent shadow-sm flex items-center justify-center space-x-2"
              >
                <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M6 18L18 6M6 6l12 12"></path>
                </svg>
                <span>Stop Stream & Choose Another</span>
              </button>
            </div>
          ) : (
            <div className="space-y-4">
              <div className="flex items-center justify-between mb-4">
                <h2 className="text-lg font-semibold text-gray-200">Local Videos</h2>
                <button 
                  onClick={() => loadFiles(backendUrl)}
                  className="p-2 hover:bg-gray-800 rounded-full transition-colors group"
                  title="Refresh list"
                >
                  <svg className={`w-5 h-5 text-gray-400 group-hover:text-white ${status === 'loading' ? 'animate-spin' : ''}`} fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"></path>
                  </svg>
                </button>
              </div>

              {status === 'loading' && videos.length === 0 ? (
                <div className="flex flex-col items-center justify-center py-12 text-gray-500">
                  <div className="w-8 h-8 border-2 border-blue-500 border-t-transparent rounded-full animate-spin mb-4"></div>
                  <p>Scanning storage for videos...</p>
                </div>
              ) : videos.length > 0 ? (
                <div className="grid grid-cols-1 gap-3 max-h-[60vh] overflow-y-auto pr-2 custom-scrollbar">
                  {videos.map((video, idx) => (
                    <div 
                      key={idx}
                      onClick={() => selectVideo(video.path)}
                      className="group flex items-center p-4 rounded-xl bg-gray-800/40 hover:bg-gray-800 border border-transparent hover:border-blue-500/30 cursor-pointer transition-all duration-200 hover:shadow-md"
                    >
                      <div className="w-12 h-12 bg-blue-500/10 rounded-lg flex items-center justify-center mr-4 group-hover:scale-105 transition-transform group-hover:bg-blue-500/20 text-blue-400">
                        <svg className="w-6 h-6" fill="currentColor" viewBox="0 0 20 20">
                          <path d="M2 6a2 2 0 012-2h6a2 2 0 012 2v8a2 2 0 01-2 2H4a2 2 0 01-2-2V6zM14.553 7.106A1 1 0 0014 8v4a1 1 0 00.553.894l2 1A1 1 0 0018 13V7a1 1 0 00-1.447-.894l-2 1z"></path>
                        </svg>
                      </div>
                      <div className="flex-1 min-w-0">
                        <p className="text-gray-200 font-medium truncate group-hover:text-white transition-colors">
                          {video.name}
                        </p>
                        <p className="text-xs text-gray-500 mt-1">
                          {formatSize(video.size)} • {video.ext.toUpperCase()}
                        </p>
                      </div>
                      <div className="opacity-0 group-hover:opacity-100 transition-opacity ml-2">
                        <span className="bg-blue-600 hover:bg-blue-500 text-white text-xs font-semibold px-3 py-1.5 rounded-full">
                          Stream
                        </span>
                      </div>
                    </div>
                  ))}
                </div>
              ) : (
                <div className="border-2 border-dashed border-gray-700 rounded-2xl p-10 text-center">
                  <div className="w-16 h-16 bg-gray-800 rounded-full flex items-center justify-center mx-auto mb-4 text-gray-500">
                    <svg className="w-8 h-8" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M7 4v16M17 4v16M3 8h4m10 0h4M3 12h18M3 16h4m10 0h4M4 20h16a1 1 0 001-1V5a1 1 0 00-1-1H4a1 1 0 00-1 1v14a1 1 0 001 1z"></path>
                    </svg>
                  </div>
                  <h3 className="text-gray-300 font-medium mb-2">No videos found</h3>
                  <p className="text-sm text-gray-500 mb-4 max-w-sm mx-auto">
                    We couldn't find any MP4, MKV, or AVI files in your standard folders. 
                  </p>
                  <div className="bg-blue-900/20 text-blue-400 p-3 rounded-lg text-xs border border-blue-800/30 text-left">
                    <strong>Note:</strong> If running on Android via Termux, make sure you ran <code>termux-setup-storage</code> first!
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      </div>

      <style dangerouslySetInnerHTML={{__html: `
        .custom-scrollbar::-webkit-scrollbar {
          width: 6px;
        }
        .custom-scrollbar::-webkit-scrollbar-track {
          background: rgba(31, 41, 55, 0.5); 
          border-radius: 10px;
        }
        .custom-scrollbar::-webkit-scrollbar-thumb {
          background: rgba(75, 85, 99, 0.8); 
          border-radius: 10px;
        }
        .custom-scrollbar::-webkit-scrollbar-thumb:hover {
          background: rgba(107, 114, 128, 1); 
        }
      `}} />
    </div>
  );
}
