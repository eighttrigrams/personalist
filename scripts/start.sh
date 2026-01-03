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

if [ "$1" = "prod" ]; then
  echo "Starting in production mode on port $PORT..."
  echo "Building uberjar..."
  clj -T:build uber
  echo "Running jar..."
  java --add-opens=java.base/java.nio=ALL-UNNAMED \
       -Dio.netty.tryReflectionSetAccessible=true \
       --enable-native-access=ALL-UNNAMED \
       -jar target/personalist-0.0.1-standalone.jar
else
  SHADOW_MODE=$(grep -o ':shadow?[[:space:]]*true' config.edn)
  if [ -n "$SHADOW_MODE" ]; then
    echo "Starting with shadow-cljs watch (hot reload)..."
    npx shadow-cljs watch app &
    echo $! > .shadow-cljs.pid
    sleep 5
  else
    echo "Starting with shadow-cljs release (no hot reload)..."
    npx shadow-cljs release app
  fi
  clojure -X:xtdb
fi
