#!/bin/bash

# Sourced by the other scripts. config.edn carries aero reader tags
# (#long #or #env), so plain read-string cannot parse it — this is the same
# reader the app uses, so an external PORT override resolves identically here.
read_config() {
  clj -M -e "(require '[aero.core :as aero]) (-> (aero/read-config \"config.edn\") $1)"
}
