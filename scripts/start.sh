#!/bin/bash

. "$(dirname "$0")/config.sh"

if [ ! -f config.edn ]; then
  echo "No config.edn found. Creating default configuration..."
  cat > config.edn << 'CONFIG'
{:db {:type :sqlite-in-memory}
 :devel {
   :pre-seed? true
   :shadow? true
   :dangerously-skip-logins? true
 }
 :logging {
   :level :debug
   :format :human
 }
 :server {
   :port #long #or [#env PORT 3120]
 }
}
CONFIG
  echo "Created config.edn"
fi

# The app takes its port from config.edn, where #env PORT is an optional
# override. Resolve it the same way rather than defaulting here, so
# .server.port holds the port stop.sh will actually find the process on.
PORT=$(read_config ":server :port")
echo $PORT > .server.port

if [ "$1" = "prod" ]; then
  IN_MEMORY=$(read_config ":db :type (= :sqlite-in-memory)")
  if [ "$IN_MEMORY" = "true" ]; then
    echo "Error: Cannot start in production mode with in-memory database."
    echo "Please configure a persistent database in config.edn"
    exit 1
  fi
  SKIP_LOGINS=$(read_config ":devel :dangerously-skip-logins? true?")
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
  java -jar target/personalist-0.0.1-standalone.jar
else
  SHADOW_MODE=$(read_config ":devel :shadow? true?")
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
  clojure -X:run
fi
