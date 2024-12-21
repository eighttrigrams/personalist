(ns go
  (:require [xtdb.api :as xt]
            [xtdb.node :as xtn]))

(defn -main
  [& _args]
  (let [node (xtn/start-node)]
    (prn (xt/status node))
    (.close (node))))
