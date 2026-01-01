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

echo "Stopping shadow-cljs server..."
npx shadow-cljs stop 2>/dev/null || true
rm -f .shadow-cljs.pid

rm -f .nrepl-port .server.port
echo "Done."
