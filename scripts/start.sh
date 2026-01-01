#!/bin/bash

PORT=${PORT:-3017}
echo $PORT > .server.port

if [ "$1" = "dev" ]; then
  echo "Starting in development mode on port $PORT..."
  npx shadow-cljs watch app &
  echo $! > .shadow-cljs.pid
  sleep 5
  clojure -X:xtdb
else
  echo "Starting in demo mode on port $PORT..."
  echo "Building release JS (no shadow-cljs dev tooling)..."
  npx shadow-cljs release app
  clojure -X:xtdb
fi
