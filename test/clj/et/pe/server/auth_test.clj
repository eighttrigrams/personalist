(ns et.pe.server.auth-test
  (:require [clojure.test :refer [deftest testing is]]
            [et.pe.server :as server]
            [et.pe.server.handlers :as handlers]))

(def ^:private create-token #'handlers/create-token)

(defn- echo-handler [_req] {:status 200 :body {:reached true}})

(defn- request
  ([method uri] (request method uri nil))
  ([method uri token]
   (cond-> {:request-method method :uri uri}
     token (assoc :headers {"authorization" (str "Bearer " token)}))))

(defn- guarded
  "Run `req` through wrap-auth as prod would see it. prod-mode? is redefed
   rather than driven through the environment, since it reads env vars a JVM
   cannot set for itself. Signing and verifying share one secret either way."
  [req]
  (with-redefs [server/prod-mode? (constantly true)]
    ((server/wrap-auth echo-handler) req)))

(defn- unguarded [req]
  (with-redefs [server/prod-mode? (constantly false)]
    ((server/wrap-auth echo-handler) req)))

(def ^:private a-token (delay (create-token :aaa)))
(def ^:private b-token (delay (create-token :bbb)))
(def ^:private admin-token (delay (create-token :admin)))

(deftest token-round-trip
  (testing "the claims wrap-auth reads back carry the persona id as a string"
    (is (= "aaa" (:persona (handlers/verify-token-check @a-token))))
    (is (= "admin" (:persona (handlers/verify-token-check @admin-token))))
    (is (nil? (handlers/verify-token-check "not-a-token")))))

(deftest persona-creation-needs-a-token
  (testing "anonymous POST /api/personas is refused"
    (is (= 401 (:status (guarded (request :post "/api/personas"))))))

  (testing "an unverifiable token is refused"
    (is (= 401 (:status (guarded (request :post "/api/personas" "garbage"))))))

  (testing "any valid token may mint a persona"
    (is (= 200 (:status (guarded (request :post "/api/personas" @a-token)))))
    (is (= 200 (:status (guarded (request :post "/api/personas" @admin-token)))))))

(deftest writes-are-confined-to-the-token-s-own-persona
  (testing "A writing under A goes through"
    (is (= 200 (:status (guarded (request :post "/api/personas/aaa/identities" @a-token)))))
    (is (= 200 (:status (guarded (request :put "/api/personas/aaa/identities/x" @a-token)))))
    (is (= 200 (:status (guarded (request :put "/api/personas/aaa" @a-token))))))

  (testing "B writing under B goes through too"
    (is (= 200 (:status (guarded (request :post "/api/personas/bbb/identities" @b-token))))))

  (testing "A writing under B is forbidden, not merely unauthenticated"
    (is (= 403 (:status (guarded (request :post "/api/personas/bbb/identities" @a-token)))))
    (is (= 403 (:status (guarded (request :put "/api/personas/bbb/identities/x" @a-token)))))
    (is (= 403 (:status (guarded (request :put "/api/personas/bbb" @a-token))))))

  (testing "admin writes under anyone — the Settings tab edits other personas"
    (is (= 200 (:status (guarded (request :put "/api/personas/aaa" @admin-token)))))
    (is (= 200 (:status (guarded (request :put "/api/personas/bbb" @admin-token)))))
    (is (= 200 (:status (guarded (request :post "/api/personas/bbb/identities" @admin-token))))))

  (testing "a persona whose id merely prefixes another's is still a stranger"
    (is (= 403 (:status (guarded (request :put "/api/personas/aaaa" @a-token)))))))

(deftest reads-are-never-guarded
  (testing "every GET goes through, with no token and with someone else's"
    (doseq [uri ["/api/personas"
                 "/api/personas/bbb/identities"
                 "/api/personas/bbb/identities/x"
                 "/api/personas/bbb/identities/x/history"
                 "/api/describe"]]
      (is (= 200 (:status (guarded (request :get uri)))) uri)
      (is (= 200 (:status (guarded (request :get uri @a-token)))) uri))))

(deftest login-stays-public
  (testing "you could not obtain a token otherwise"
    (is (= 200 (:status (guarded (request :post "/api/auth/login")))))))

(deftest non-api-writes-are-not-this-middleware-s-business
  (is (= 200 (:status (guarded (request :post "/whatever"))))))

(deftest dev-mode-is-unaffected
  (testing "nothing is guarded when prod-mode? is false"
    (doseq [req [(request :post "/api/personas")
                 (request :post "/api/personas/bbb/identities")
                 (request :put "/api/personas/bbb")]]
      (is (= 200 (:status (unguarded req)))))))
