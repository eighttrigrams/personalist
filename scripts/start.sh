#!/bin/bash

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
   :port 3017
 }
}
CONFIG
  echo "Created config.edn"
fi

PORT=${PORT:-3017}
echo $PORT > .server.port

# config.edn carries aero reader tags (#long #or #env), so plain read-string
# cannot parse it. Same reader the app itself uses.
read_config() {
  clj -M -e "(require '[aero.core :as aero]) (-> (aero/read-config \"config.edn\") $1)"
}

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
