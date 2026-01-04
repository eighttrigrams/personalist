(ns et.pe.middleware.rater-limit-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.pe.middleware.rate-limit :as rl]))

(defn fresh-limiter-fixture [f]
  (f))

(use-fixtures :each fresh-limiter-fixture)

(deftest check-ip-allowed-single-request
  (testing "first request from an IP is allowed"
    (let [limiter (rl/create-limiter)]
      (is (true? (rl/check-ip-allowed limiter "1.2.3.4" 10 60000 1000))))))

(deftest check-ip-allowed-within-limit
  (testing "requests within limit are allowed"
    (let [limiter (rl/create-limiter)]
      (is (true? (rl/check-ip-allowed limiter "1.2.3.4" 3 60000 1000)))
      (is (true? (rl/check-ip-allowed limiter "1.2.3.4" 3 60000 2000)))
      (is (true? (rl/check-ip-allowed limiter "1.2.3.4" 3 60000 3000))))))

(deftest check-ip-allowed-exceeds-limit
  (testing "request exceeding limit is denied"
    (let [limiter (rl/create-limiter)]
      (is (true? (rl/check-ip-allowed limiter "1.2.3.4" 2 60000 1000)))
      (is (true? (rl/check-ip-allowed limiter "1.2.3.4" 2 60000 2000)))
      (is (false? (rl/check-ip-allowed limiter "1.2.3.4" 2 60000 3000))))))

(deftest check-ip-allowed-different-ips-independent
  (testing "different IPs have independent limits"
    (let [limiter (rl/create-limiter)]
      (is (true? (rl/check-ip-allowed limiter "1.1.1.1" 1 60000 1000)))
      (is (false? (rl/check-ip-allowed limiter "1.1.1.1" 1 60000 2000)))
      (is (true? (rl/check-ip-allowed limiter "2.2.2.2" 1 60000 3000))))))

(deftest check-ip-allowed-window-expiration
  (testing "old requests expire and new ones are allowed"
    (let [limiter (rl/create-limiter)
          window-ms 1000]
      (is (true? (rl/check-ip-allowed limiter "1.2.3.4" 2 window-ms 1000)))
      (is (true? (rl/check-ip-allowed limiter "1.2.3.4" 2 window-ms 1500)))
      (is (false? (rl/check-ip-allowed limiter "1.2.3.4" 2 window-ms 1800)))
      (is (true? (rl/check-ip-allowed limiter "1.2.3.4" 2 window-ms 2100))))))

(deftest check-global-allowed-single-request
  (testing "first global request is allowed"
    (let [limiter (rl/create-limiter)]
      (is (true? (rl/check-global-allowed limiter 10 60000 1000))))))

(deftest check-global-allowed-within-limit
  (testing "global requests within limit are allowed"
    (let [limiter (rl/create-limiter)]
      (is (true? (rl/check-global-allowed limiter 3 60000 1000)))
      (is (true? (rl/check-global-allowed limiter 3 60000 2000)))
      (is (true? (rl/check-global-allowed limiter 3 60000 3000))))))

(deftest check-global-allowed-exceeds-limit
  (testing "global request exceeding limit is denied"
    (let [limiter (rl/create-limiter)]
      (is (true? (rl/check-global-allowed limiter 2 60000 1000)))
      (is (true? (rl/check-global-allowed limiter 2 60000 2000)))
      (is (false? (rl/check-global-allowed limiter 2 60000 3000))))))

(deftest check-global-allowed-window-expiration
  (testing "old global requests expire"
    (let [limiter (rl/create-limiter)
          window-ms 1000]
      (is (true? (rl/check-global-allowed limiter 2 window-ms 1000)))
      (is (true? (rl/check-global-allowed limiter 2 window-ms 1500)))
      (is (false? (rl/check-global-allowed limiter 2 window-ms 1800)))
      (is (true? (rl/check-global-allowed limiter 2 window-ms 2100))))))

(deftest ip-and-global-limits-combined
  (testing "both IP and global limits apply"
    (let [limiter (rl/create-limiter)
          window-ms 60000]
      (is (true? (rl/check-ip-allowed limiter "1.1.1.1" 10 window-ms 1000)))
      (is (true? (rl/check-global-allowed limiter 2 window-ms 1000)))
      (is (true? (rl/check-ip-allowed limiter "2.2.2.2" 10 window-ms 2000)))
      (is (true? (rl/check-global-allowed limiter 2 window-ms 2000)))
      (is (true? (rl/check-ip-allowed limiter "3.3.3.3" 10 window-ms 3000)))
      (is (false? (rl/check-global-allowed limiter 2 window-ms 3000))))))

(deftest reset-limiter-clears-state
  (testing "reset clears all tracked requests"
    (let [limiter (rl/create-limiter)]
      (rl/check-ip-allowed limiter "1.2.3.4" 1 60000 1000)
      (rl/check-global-allowed limiter 1 60000 1000)
      (is (false? (rl/check-ip-allowed limiter "1.2.3.4" 1 60000 2000)))
      (is (false? (rl/check-global-allowed limiter 1 60000 2000)))
      (rl/reset-limiter limiter)
      (is (true? (rl/check-ip-allowed limiter "1.2.3.4" 1 60000 3000)))
      (is (true? (rl/check-global-allowed limiter 1 60000 3000))))))

(deftest rapid-requests-same-timestamp
  (testing "multiple requests at exact same timestamp count correctly"
    (let [limiter (rl/create-limiter)]
      (is (true? (rl/check-ip-allowed limiter "1.2.3.4" 3 60000 1000)))
      (is (true? (rl/check-ip-allowed limiter "1.2.3.4" 3 60000 1000)))
      (is (true? (rl/check-ip-allowed limiter "1.2.3.4" 3 60000 1000)))
      (is (false? (rl/check-ip-allowed limiter "1.2.3.4" 3 60000 1000))))))

(deftest should-warn-ip-first-time
  (testing "first warning for an IP returns true"
    (let [limiter (rl/create-limiter)]
      (is (true? (rl/should-warn-ip? limiter "1.2.3.4" 1000))))))

(deftest should-warn-ip-cooldown
  (testing "warnings within cooldown period are suppressed"
    (let [limiter (rl/create-limiter)]
      (is (true? (rl/should-warn-ip? limiter "1.2.3.4" 1000)))
      (is (nil? (rl/should-warn-ip? limiter "1.2.3.4" 5000)))
      (is (nil? (rl/should-warn-ip? limiter "1.2.3.4" 10000)))
      (is (true? (rl/should-warn-ip? limiter "1.2.3.4" 11001))))))

(deftest should-warn-ip-per-ip
  (testing "cooldown is per-IP"
    (let [limiter (rl/create-limiter)]
      (is (true? (rl/should-warn-ip? limiter "1.1.1.1" 1000)))
      (is (nil? (rl/should-warn-ip? limiter "1.1.1.1" 2000)))
      (is (true? (rl/should-warn-ip? limiter "2.2.2.2" 2000))))))

(deftest should-warn-global-first-time
  (testing "first global warning returns true"
    (let [limiter (rl/create-limiter)]
      (is (true? (rl/should-warn-global? limiter 1000))))))

(deftest should-warn-global-cooldown
  (testing "global warnings within cooldown period are suppressed"
    (let [limiter (rl/create-limiter)]
      (is (true? (rl/should-warn-global? limiter 1000)))
      (is (nil? (rl/should-warn-global? limiter 5000)))
      (is (nil? (rl/should-warn-global? limiter 10000)))
      (is (true? (rl/should-warn-global? limiter 11001))))))

