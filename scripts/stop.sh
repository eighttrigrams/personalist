#!/bin/bash

if [ -f .server.port ]; then
  PORT=$(cat .server.port)
else
  PORT=${PORT:-3017}
fi

echo "Stopping application..."

echo "Stopping server on port $PORT..."
PID=$(lsof -ti:$PORT)
if [ -n "$PID" ]; then
  kill $PID
  echo "Killed server process $PID"
else
  echo "No server found on port $PORT"
fi

if [ -f .shadow-cljs.pid ]; then
  SHADOW_PID=$(cat .shadow-cljs.pid)
  if kill -0 $SHADOW_PID 2>/dev/null; then
    kill $SHADOW_PID
    echo "Killed shadow-cljs process $SHADOW_PID"
  else
    echo "Shadow-cljs process $SHADOW_PID not running"
  fi
  rm -f .shadow-cljs.pid
else
  echo "No shadow-cljs PID file found"
fi

rm -f .nrepl-port .server.port
echo "Done."
