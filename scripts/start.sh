#!/bin/bash

if [ ! -f config.edn ]; then
  echo "No config.edn found. Creating default configuration..."
  cat > config.edn << 'CONFIG'
{:db {:type :xtdb2-in-memory}
 :pre-seed? true}
CONFIG
  echo "Created config.edn"
fi

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
