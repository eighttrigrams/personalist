(ns et.pe.middleware.rate-limit
  (:require [clojure.tools.logging :as log]))

(def ^:private global-request-log (atom []))
(def ^:private ip-request-logs (atom {}))

(defn- current-time-ms []
  (System/currentTimeMillis))

(defn- prune-old-requests [logs window-ms]
  (let [cutoff (- (current-time-ms) window-ms)]
    (filterv #(> % cutoff) logs)))

(defn- get-client-ip [request]
  (or (get-in request [:headers "x-forwarded-for"])
      (get-in request [:headers "x-real-ip"])
      (:remote-addr request)
      "unknown"))

(defn- global-allowed? [max-requests window-ms]
  (let [now (current-time-ms)
        pruned (prune-old-requests @global-request-log window-ms)]
    (reset! global-request-log pruned)
    (if (< (count pruned) max-requests)
      (do
        (swap! global-request-log conj now)
        true)
      false)))

(defn- ip-allowed? [ip max-requests window-ms]
  (let [now (current-time-ms)
        ip-logs (get @ip-request-logs ip [])
        pruned (prune-old-requests ip-logs window-ms)]
    (swap! ip-request-logs assoc ip pruned)
    (if (< (count pruned) max-requests)
      (do
        (swap! ip-request-logs update ip (fnil conj []) now)
        true)
      false)))

(defn- parse-int [s default]
  (if s
    (try (Integer/parseInt s) (catch Exception _ default))
    default))

(defn wrap-rate-limit
  ([handler]
   (wrap-rate-limit handler {:global-max-requests (parse-int (System/getenv "GLOBAL_RATE_LIMIT") 180)
                             :ip-max-requests (parse-int (System/getenv "PER_IP_RATE_LIMIT") 60)
                             :window-seconds 60}))
  ([handler {:keys [global-max-requests ip-max-requests window-seconds]}]
   (let [window-ms (* window-seconds 1000)]
     (fn [request]
       (let [client-ip (get-client-ip request)
             ip-ok? (ip-allowed? client-ip ip-max-requests window-ms)
             global-ok? (if ip-ok?
                          (global-allowed? global-max-requests window-ms)
                          false)]
         (cond
           (not ip-ok?)
           (do
             (log/warn "Rate limit exceeded for IP:" client-ip)
             {:status 429
              :headers {"Content-Length" "0"}
              :body ""})

           (not global-ok?)
           (do
             (log/warn "Global rate limit exceeded, triggered by IP:" client-ip)
             {:status 429
              :headers {"Content-Length" "0"}
              :body ""})

           :else
           (handler request)))))))
