(ns et.pe.logging
  (:require [taoensso.telemere :as tel]))

(defn- output-fn [format]
  (case format
    :edn (tel/pr-signal-fn {:pr-fn :edn})
    :json (tel/pr-signal-fn {:pr-fn :json})
    nil))

(defn init! [{:keys [file level format]}]
  (tel/set-ns-filter! {:disallow "xtdb.metrics" :allow "*"})
  (when level
    (tel/set-min-level! level))
  (let [output-fn (output-fn format)]
    (tel/add-handler! :console (tel/handler:console (cond-> {}
                                                      output-fn (assoc :output-fn output-fn))))
    (when file
      (tel/add-handler! :file (tel/handler:file (cond-> {:path file}
                                                  output-fn (assoc :output-fn output-fn)))))))

(defn stop! []
  (tel/stop-handlers!))
