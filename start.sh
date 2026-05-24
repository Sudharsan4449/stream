#!/bin/bash
# Start script for Termux

echo "Starting Mobile-to-VLC Streamer..."

# Start Express Server in background
cd server
npm run dev &
SERVER_PID=$!

# Start Next.js Frontend in foreground
cd ../client
npm run dev

# On exit, kill server
trap "kill $SERVER_PID" EXIT
