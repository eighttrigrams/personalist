(ns et.pe.server.auth-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [buddy.sign.jwt :as jwt]
            [et.pe.ds :as ds]
            [et.pe.server :as server]
            [et.pe.server.handlers :as handlers]))

(def ^:private create-token #'handlers/create-token)
(def ^:private create-admin-token #'handlers/create-admin-token)
(def ^:private jwt-secret #'handlers/jwt-secret)

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

;; ---------------------------------------------------------------------------
;; Ownership is a lookup now, not a string comparison: the token carries an
;; account id, the URI carries a persona id, and the guard asks the database
;; which account holds that persona. So every case below needs real rows —
;; a token alone no longer means anything.
;;
;;   account A  -> personas aaa, also-mine     (two faces of one login)
;;   account B  -> personas bbb, aaaa          (aaaa: the prefix trap)
;;   account T  -> persona  targetx            (F2's victim)
;;   account E  -> persona  %74%61%72%67%65%74%78  (F2's attacker)
;;   account P  -> persona  %zz                (the malformed escape)
;; ---------------------------------------------------------------------------

(def ^:private enc "%74%61%72%67%65%74%78")   ; url-encoding of "targetx"

(def ^:private accounts (atom nil))
(def ^:private db (atom nil))

(defn- seeded-db [f]
  (let [conn (ds/init-conn :sqlite-in-memory {})]
    (reset! db conn)
    (handlers/set-conn! conn)
    (let [a (ds/add-account conn "a@et.n" nil)
          b (ds/add-account conn "b@et.n" nil)
          t (ds/add-account conn "t@et.n" nil)
          e (ds/add-account conn "e@et.n" nil)
          p (ds/add-account conn "p@et.n" nil)]
      (ds/add-persona conn a :aaa "A")
      (ds/add-persona conn a :also-mine "A's other face")
      (ds/add-persona conn b :bbb "B")
      (ds/add-persona conn b :aaaa "B's confusable")
      (ds/add-persona conn t :targetx "Target")
      (ds/add-persona conn e (keyword enc) "Attacker")
      (ds/add-persona conn p (keyword "%zz") "Malformed")
      (reset! accounts {:a a :b b :t t :e e :p p}))
    (try
      (f)
      (finally
        (handlers/set-conn! nil)
        (ds/close-conn conn)
        (reset! db nil)))))

(use-fixtures :once seeded-db)

(defn- token-for [k] (create-token (get @accounts k)))

(deftest token-round-trip
  (testing "the claims wrap-auth reads back name the account, not a persona"
    (is (= {:account (:a @accounts)} (handlers/verify-token-check (token-for :a))))
    (is (nil? (:persona (handlers/verify-token-check (token-for :a))))
        "the persona claim is gone — one login now wears several faces")
    (is (true? (:admin (handlers/verify-token-check (create-admin-token)))))
    (is (nil? (handlers/verify-token-check "not-a-token")))))

(deftest persona-creation-needs-a-token
  (testing "anonymous POST /api/personas is refused"
    (is (= 401 (:status (guarded (request :post "/api/personas"))))))

  (testing "an unverifiable token is refused"
    (is (= 401 (:status (guarded (request :post "/api/personas" "garbage"))))))

  (testing "any valid token gets past the guard — whose account it mints under is the handler's business"
    (is (= 200 (:status (guarded (request :post "/api/personas" (token-for :a))))))
    (is (= 200 (:status (guarded (request :post "/api/personas" (create-admin-token))))))))

(deftest writes-are-confined-to-the-token-s-own-account
  (testing "A writing under A goes through"
    (is (= 200 (:status (guarded (request :post "/api/personas/aaa/identities" (token-for :a))))))
    (is (= 200 (:status (guarded (request :put "/api/personas/aaa/identities/x" (token-for :a))))))
    (is (= 200 (:status (guarded (request :put "/api/personas/aaa" (token-for :a)))))))

  (testing "and under every other persona of the same account — this is the whole point of the split"
    (is (= 200 (:status (guarded (request :post "/api/personas/also-mine/identities" (token-for :a))))))
    (is (= 200 (:status (guarded (request :put "/api/personas/also-mine" (token-for :a))))))
    (is (= 200 (:status (guarded (request :delete "/api/personas/also-mine" (token-for :a)))))))

  (testing "B writing under B goes through too"
    (is (= 200 (:status (guarded (request :post "/api/personas/bbb/identities" (token-for :b)))))))

  (testing "A writing under B is forbidden, not merely unauthenticated"
    (is (= 403 (:status (guarded (request :post "/api/personas/bbb/identities" (token-for :a))))))
    (is (= 403 (:status (guarded (request :put "/api/personas/bbb/identities/x" (token-for :a))))))
    (is (= 403 (:status (guarded (request :put "/api/personas/bbb" (token-for :a))))))
    (is (= 403 (:status (guarded (request :delete "/api/personas/bbb" (token-for :a)))))))

  (testing "admin writes under anyone — the Settings tab edits other accounts"
    (is (= 200 (:status (guarded (request :put "/api/personas/aaa" (create-admin-token))))))
    (is (= 200 (:status (guarded (request :put "/api/personas/bbb" (create-admin-token))))))
    (is (= 200 (:status (guarded (request :post "/api/personas/bbb/identities" (create-admin-token)))))))

  (testing "a persona whose id merely prefixes another's is still a stranger"
    (is (= 403 (:status (guarded (request :put "/api/personas/aaaa" (token-for :a)))))))

  (testing "a persona nobody holds is owned by nobody"
    (is (= 403 (:status (guarded (request :put "/api/personas/no-such-persona" (token-for :a))))))
    (is (= 200 (:status (guarded (request :put "/api/personas/no-such-persona" (create-admin-token)))))
        "except by admin, whose exemption asks no database")))

(deftest reads-are-not-this-middleware-s-business
  (testing "wrap-auth lets every GET through, with no token and with a stranger's.
            /api/me and /api/accounts are guarded too, but inside their handlers,
            where the answer is about an account rather than about the site"
    (doseq [uri ["/api/personas"
                 "/api/personas/bbb/identities"
                 "/api/personas/bbb/identities/x"
                 "/api/personas/bbb/identities/x/history"
                 "/api/me"
                 "/api/accounts"
                 "/api/describe"]]
      (is (= 200 (:status (guarded (request :get uri)))) uri)
      (is (= 200 (:status (guarded (request :get uri (token-for :a))))) uri))))

(deftest login-stays-public
  (testing "you could not obtain a token otherwise"
    (is (= 200 (:status (guarded (request :post "/api/auth/login")))))))

(deftest non-api-writes-are-not-this-middleware-s-business
  (is (= 200 (:status (guarded (request :post "/whatever"))))))

(deftest dev-mode-is-unaffected
  (testing "nothing is guarded when prod-mode? is false"
    (doseq [req [(request :post "/api/personas")
                 (request :post "/api/personas/bbb/identities")
                 (request :delete "/api/personas/bbb")
                 (request :put "/api/personas/bbb")]]
      (is (= 200 (:status (unguarded req)))))))

;; F1 — no ordinary token may claim the blanket exemption. Before the fix the
;; guard tested (= (:persona claims) "admin"), so a persona row named "admin",
;; entered by email login, cleared owns-persona? for everyone. The exemption
;; keys on the un-mintable :admin claim instead, and since the split a token
;; carries an account id and no persona name at all — there is no longer a
;; string in it that could be mistaken for admin.
(deftest only-the-admin-claim-is-exempt
  (testing "an ordinary account token writes under its own personas and nothing else"
    (is (= 200 (:status (guarded (request :put "/api/personas/aaa" (token-for :a))))))
    (is (= 403 (:status (guarded (request :put "/api/personas/bbb" (token-for :a))))))
    (is (= 403 (:status (guarded (request :put "/api/personas/targetx" (token-for :a)))))))
  (testing "a hand-forged {:admin false} claim is not an exemption either"
    (is (= 403 (:status (guarded (request :put "/api/personas/bbb"
                                          (jwt/sign {:account (:a @accounts) :admin false}
                                                    (jwt-secret))))))))
  (testing "only the ADMIN_PASSWORD login's token is exempt everywhere"
    (is (= 200 (:status (guarded (request :put "/api/personas/bbb" (create-admin-token))))))
    (is (= 200 (:status (guarded (request :post "/api/personas/aaa/identities" (create-admin-token))))))))

;; F1's second latch — the confusing "admin" row cannot be created at all —
;; lives in handlers-test/reserved-persona-ids-are-refused now: it exercises a
;; handler rather than this middleware, and since the split it has to check the
;; account side too (a refusal must not leave an orphan account behind).

;; F2 — a token whose account holds the persona whose id is the percent-encoding
;; of another persona's id must not write under that other persona via the
;; encoded URI. wrap-auth reads the persona off the raw :uri; compojure
;; URL-decodes route params before the handler, so pre-fix the guard saw "%74…"
;; while the handler saw "targetx". persona-in-uri decodes the segment the same
;; way, closing the gap.
(deftest percent-encoded-persona-segment-is-decoded-before-comparison
  (testing "control — plain URI for targetx with the attacker's token is 403"
    (is (= 403 (:status (guarded (request :put "/api/personas/targetx" (token-for :e)))))))
  (testing "bypass attempt — encoded URI is now also 403, not 200"
    (is (= 403 (:status (guarded (request :put (str "/api/personas/" enc) (token-for :e))))))
    (is (= 403 (:status (guarded (request :post (str "/api/personas/" enc "/identities") (token-for :e)))))))
  (testing "lowercase-hex form is decoded identically (Jetty may upcase, decode does not care)"
    (is (= 403 (:status (guarded (request :put "/api/personas/%74%61%72%67%65%74%78" (token-for :e)))))))
  (testing "the legitimate owner of targetx still writes under the encoded URI"
    (is (= 200 (:status (guarded (request :put (str "/api/personas/" enc) (token-for :t)))))
        "a token for the account holding the decoded id targetx owns the decoded URI, encoded or not")))

;; A malformed escape must neither throw (500) nor slip past: url-decode leaves
;; it verbatim on both the guard and handler side, so the lookup still finds the
;; persona actually named "%zz" and nobody else's.
(deftest malformed-escape-neither-500s-nor-bypasses
  (testing "a stranger's token on a malformed-escape URI is a clean 403, not 500"
    (is (= 403 (:status (guarded (request :put "/api/personas/%zz" (token-for :a)))))))
  (testing "the account holding the verbatim id still writes under it"
    (is (= 200 (:status (guarded (request :put "/api/personas/%zz" (token-for :p))))))))
