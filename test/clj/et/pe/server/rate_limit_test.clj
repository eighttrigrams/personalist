(ns et.pe.server.rate-limit-test
  (:require [clojure.test :refer [deftest testing is]]
            [et.pe.server :as server]))

(defn- env-int [name default]
  (or (some-> (System/getenv name) Integer/parseInt) default))

(def ^:private ip-limit (env-int "PER_IP_RATE_LIMIT" 60))
(def ^:private global-limit (env-int "GLOBAL_RATE_LIMIT" 180))

(def ^:private limit
  "Whichever ceiling a single IP hits first."
  (min ip-limit global-limit))

(defn- request [ip]
  {:request-method :get :uri "/api/describe" :remote-addr ip :headers {}})

(defn- statuses
  "`n` requests through `handler`, all from `ip`. prod-mode? is redefed off so
   wrap-auth stays out of the way — the limiter wraps it from outside and does
   not care either way. /api/describe touches no database."
  [handler n ip]
  (with-redefs [server/prod-mode? (constantly false)]
    (mapv (fn [_] (:status (handler (request ip)))) (range n))))

(defn- first-429 [sts]
  (some (fn [[i st]] (when (= 429 st) (inc i)))
        (map-indexed vector sts)))

(deftest limiter-state-survives-between-requests
  (testing "a prod-shaped app refuses an IP once its window is full"
    (let [handler (server/app {:devel {:shadow? false}})
          sts (statuses handler (+ limit 140) "1.1.1.1")]
      (is (some? (first-429 sts))
          (str "no 429 in " (count sts) " requests from one IP — the limiter is "
               "being rebuilt per request and counts nothing"))
      (is (= (inc limit) (first-429 sts))
          "the first refusal should land one request past the limit")
      (is (= #{200 429} (set sts))))))

(deftest per-ip-buckets-are-independent
  (testing "exhausting one IP leaves another untouched"
    (let [handler (server/app {:devel {:shadow? false}})]
      (statuses handler limit "1.1.1.1")
      (is (= [429] (statuses handler 1 "1.1.1.1")))
      (is (= [200] (statuses handler 1 "2.2.2.2"))))))

(deftest shadow-mode-is-unlimited
  (testing "dev bypasses the limiter entirely"
    (let [handler (server/app {:devel {:shadow? true}})
          sts (statuses handler (+ limit 140) "1.1.1.1")]
      (is (= #{200} (set sts)))
      (is (nil? (first-429 sts))))))

(deftest fly-client-ip-outranks-remote-addr
  (testing "the header decides the bucket, which is what makes per-IP limiting
            work behind fly's proxy"
    (let [handler (server/app {:devel {:shadow? false}})]
      (statuses handler limit "1.1.1.1")
      (is (= [429] (statuses handler 1 "1.1.1.1")))
      (is (= 200 (with-redefs [server/prod-mode? (constantly false)]
                   (:status (handler (assoc (request "1.1.1.1")
                                            :headers {"fly-client-ip" "3.3.3.3"})))))))))
