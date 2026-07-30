#!/bin/bash

. "$(dirname "$0")/config.sh"

if [ -f .server.port ]; then
  PORT=$(cat .server.port)
elif [ -f config.edn ]; then
  PORT=$(read_config ":server :port")
fi

if [ -z "$PORT" ]; then
  echo "Cannot tell which port to stop: no .server.port and no config.edn."
  exit 1
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
