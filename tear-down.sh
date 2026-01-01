#!/bin/bash

PORT=${PORT:-3017}

echo "Tearing down application on port $PORT..."

PID=$(lsof -ti:$PORT)

if [ -n "$PID" ]; then
  kill $PID
  echo "Killed process $PID"
else
  echo "No process found on port $PORT"
fi

rm -f .nrepl-port
