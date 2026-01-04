(ns et.pe.middleware.rate-limit)

(defn create-limiter []
  {:global-log (atom [])
   :ip-logs (atom {})
   :last-ip-warn (atom {})
   :last-global-warn (atom nil)})

(defn- prune-old-requests [logs now-ms window-ms]
  (let [cutoff (- now-ms window-ms)]
    (filterv #(> % cutoff) logs)))

(defn check-ip-allowed [{:keys [ip-logs]} ip max-requests window-ms now-ms]
  (let [logs (get @ip-logs ip [])
        pruned (prune-old-requests logs now-ms window-ms)]
    (swap! ip-logs assoc ip pruned)
    (if (< (count pruned) max-requests)
      (do
        (swap! ip-logs update ip conj now-ms)
        true)
      false)))

(defn check-global-allowed [{:keys [global-log]} max-requests window-ms now-ms]
  (let [pruned (prune-old-requests @global-log now-ms window-ms)]
    (reset! global-log pruned)
    (if (< (count pruned) max-requests)
      (do
        (swap! global-log conj now-ms)
        true)
      false)))

(defn reset-limiter [{:keys [global-log ip-logs last-ip-warn last-global-warn]}]
  (reset! global-log [])
  (reset! ip-logs {})
  (reset! last-ip-warn {})
  (reset! last-global-warn nil))

(def ^:private warn-cooldown-ms 10000)

(defn should-warn-ip? [{:keys [last-ip-warn]} ip now-ms]
  (let [last-warn (get @last-ip-warn ip)]
    (when (or (nil? last-warn) (> (- now-ms last-warn) warn-cooldown-ms))
      (swap! last-ip-warn assoc ip now-ms)
      true)))

(defn should-warn-global? [{:keys [last-global-warn]} now-ms]
  (let [last-warn @last-global-warn]
    (when (or (nil? last-warn) (> (- now-ms last-warn) warn-cooldown-ms))
      (reset! last-global-warn now-ms)
      true)))
