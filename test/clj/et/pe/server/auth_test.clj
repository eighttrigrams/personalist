(ns et.pe.server.auth-test
  (:require [clojure.test :refer [deftest testing is]]
            [et.pe.ds :as ds]
            [et.pe.server :as server]
            [et.pe.server.handlers :as handlers]))

(def ^:private create-token #'handlers/create-token)
(def ^:private create-admin-token #'handlers/create-admin-token)

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
;; The exempt admin token is the one the ADMIN_PASSWORD login mints, which
;; carries an un-mintable :admin claim — not merely a :persona of "admin".
(def ^:private admin-token (delay (create-admin-token)))
;; What email-login on a persona row whose id is "admin" would mint: a plain
;; token with :persona "admin" and no :admin claim. This must NOT be exempt.
(def ^:private admin-string-token (delay (create-token :admin)))

(deftest token-round-trip
  (testing "the claims wrap-auth reads back carry the persona id as a string"
    (is (= "aaa" (:persona (handlers/verify-token-check @a-token))))
    (is (= "admin" (:persona (handlers/verify-token-check @admin-token))))
    (is (true? (:admin (handlers/verify-token-check @admin-token))))
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

;; F1 — a persona row named "admin", entered by email login, must not grant a
;; blanket exemption. Email-login mints (create-token (:id persona)); for an
;; "admin" row that is (create-token :admin) → {:persona "admin"} with no :admin
;; claim. Against the pre-fix guard (which tested (= (:persona claims) "admin"))
;; this cleared owns-persona? for everyone; now the exemption keys on the
;; un-mintable :admin claim instead.
(deftest admin-string-persona-grants-no-exemption
  (testing "a bare {:persona \"admin\"} token writes only under the admin-id persona"
    (is (= 200 (:status (guarded (request :put "/api/personas/admin" @admin-string-token))))
        "it owns the persona literally named admin, and nothing more"))
  (testing "it is forbidden under any other persona — no blanket exemption"
    (is (= 403 (:status (guarded (request :put "/api/personas/bbb" @admin-string-token)))))
    (is (= 403 (:status (guarded (request :post "/api/personas/bbb/identities" @admin-string-token)))))
    (is (= 403 (:status (guarded (request :put "/api/personas/aaa" @admin-string-token))))))
  (testing "only the ADMIN_PASSWORD login's token is exempt everywhere"
    (is (= 200 (:status (guarded (request :put "/api/personas/bbb" @admin-token)))))
    (is (= 200 (:status (guarded (request :post "/api/personas/aaa/identities" @admin-token)))))))

;; F1, second latch — the confusing "admin" row cannot be created at all.
(deftest reserved-persona-ids-are-refused
  (let [conn (ds/init-conn :sqlite-in-memory {})]
    (handlers/set-conn! conn)
    (try
      (testing "POST /api/personas with a reserved id is 400, not 201"
        (is (= 400 (:status (handlers/add-persona-handler
                             {:body {:id "admin" :email "pwn@evil.example"
                                     :password "pwnedpw" :name "pwn"}})))))
      (testing "no such row was written"
        (is (nil? (ds/get-persona-by-id conn :admin))))
      (testing "an ordinary id still mints a persona"
        (is (= 201 (:status (handlers/add-persona-handler
                             {:body {:id "regular" :email "r@example.com"
                                     :password "pw" :name "Regular"}})))))
      (finally
        (handlers/set-conn! nil)
        (ds/close-conn conn)))))

;; F2 — a token whose persona is the percent-encoding of another persona's id
;; must not write under that persona via the encoded URI. wrap-auth reads the
;; persona off the raw :uri; compojure URL-decodes route params before the
;; handler, so pre-fix the guard saw "%74…" while the handler saw "targetx".
;; persona-in-uri now decodes the segment the same way, closing the gap.
(deftest percent-encoded-persona-segment-is-decoded-before-comparison
  (let [enc "%74%61%72%67%65%74%78"                 ; url-encoding of "targetx"
        enc-token (create-token (keyword enc))]       ; attacker owns the enc-id persona
    (testing "control — plain URI for targetx with the enc token is 403"
      (is (= 403 (:status (guarded (request :put "/api/personas/targetx" enc-token))))))
    (testing "bypass attempt — encoded URI is now also 403, not 200"
      (is (= 403 (:status (guarded (request :put (str "/api/personas/" enc) enc-token)))))
      (is (= 403 (:status (guarded (request :post (str "/api/personas/" enc "/identities") enc-token))))))
    (testing "lowercase-hex form is decoded identically (Jetty may upcase, decode does not care)"
      (is (= 403 (:status (guarded (request :put "/api/personas/%74%61%72%67%65%74%78" enc-token))))))
    (testing "the legitimate owner of targetx still writes under the encoded URI"
      (is (= 200 (:status (guarded (request :put (str "/api/personas/" enc)
                                            (create-token :targetx)))))
          "a token for the decoded id targetx owns the decoded URI, encoded or not"))))

;; A malformed escape must neither throw (500) nor slip past: url-decode leaves
;; it verbatim on both the guard and handler side, so the comparison still holds.
(deftest malformed-escape-neither-500s-nor-bypasses
  (testing "a stranger's token on a malformed-escape URI is a clean 403, not 500"
    (is (= 403 (:status (guarded (request :put "/api/personas/%zz" @a-token))))))
  (testing "the owner of the verbatim id still writes under it"
    (is (= 200 (:status (guarded (request :put "/api/personas/%zz"
                                          (create-token (keyword "%zz")))))))))
