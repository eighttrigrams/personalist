#!/bin/bash

if [ ! -f config.edn ]; then
  echo "No config.edn found. Creating default configuration..."
  cat > config.edn << 'CONFIG'
{:db {:type :xtdb2-in-memory}
 :pre-seed? true
 :dangerously-skip-logins? true}
CONFIG
  echo "Created config.edn"
fi

PORT=${PORT:-3017}
echo $PORT > .server.port

if [ "$1" = "prod" ]; then
  IN_MEMORY=$(clj -M -e "(-> \"config.edn\" slurp read-string :db :type (= :xtdb2-in-memory))")
  if [ "$IN_MEMORY" = "true" ]; then
    echo "Error: Cannot start in production mode with in-memory database."
    echo "Please configure a persistent database in config.edn"
    exit 1
  fi
  SKIP_LOGINS=$(clj -M -e "(-> \"config.edn\" slurp read-string :dangerously-skip-logins? true?)")
  if [ "$SKIP_LOGINS" = "true" ]; then
    echo "Error: Cannot start in production mode with :dangerously-skip-logins? enabled."
    echo "Please remove or set :dangerously-skip-logins? to false in config.edn"
    exit 1
  fi
  echo "Starting in production mode on port $PORT..."
  echo "Building uberjar..."
  clj -T:build uber
  echo "Running jar..."
  export ADMIN_PASSWORD=${ADMIN_PASSWORD:-abcdef}
  java --add-opens=java.base/java.nio=ALL-UNNAMED \
       -Dio.netty.tryReflectionSetAccessible=true \
       --enable-native-access=ALL-UNNAMED \
       -jar target/personalist-0.0.1-standalone.jar
else
  SHADOW_MODE=$(clj -M -e "(-> \"config.edn\" slurp read-string :shadow? true?)")
  if [ "$SHADOW_MODE" = "true" ]; then
    echo "Starting with shadow-cljs watch (hot reload)..."
    npx shadow-cljs watch app &
    echo $! > .shadow-cljs.pid
    sleep 5
  else
    echo "Starting with shadow-cljs release (no hot reload)..."
    npx shadow-cljs release app
  fi
  export DEV=true
  clojure -X:xtdb
fi
