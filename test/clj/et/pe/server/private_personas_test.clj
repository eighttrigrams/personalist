(ns et.pe.server.private-personas-test
  "Private personas (migration 006). A private persona is **unreadable**, not
   merely unlisted: it is absent from the public list *and* every read beneath it
   answers exactly as an id nobody has ever used does.

   Most of this drives `server/base-app` rather than the handlers, on purpose.
   The rule lives in one middleware so that a read route added later cannot
   forget it, and a middleware that exists but was never threaded into the stack
   is precisely the failure that shape is meant to rule out — so the requests
   below go in at the top and come back as JSON, the way the browser sends them."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [et.pe.ds :as ds]
            [et.pe.server :as server]
            [et.pe.server.handlers :as handlers]))

(def ^:private create-token #'handlers/create-token)
(def ^:private create-admin-token #'handlers/create-admin-token)

;; Every GET that hangs under /api/personas/:name. The guard is one middleware
;; above all of them, and this is the list it has to cover — including
;; /provenance, which guards itself as well and must not get the chance to answer
;; 401 or 403 about a persona the caller is not allowed to know exists.
(def ^:private read-paths
  ["/api/personas/%s/identities"
   "/api/personas/%s/identities/recent"
   "/api/personas/%s/identities/search?q=a"
   "/api/personas/%s/identities/thing"
   "/api/personas/%s/identities/thing/at?time=2026-01-01T00:00:00Z"
   "/api/personas/%s/identities/thing/history"
   "/api/personas/%s/identities/thing/relations"
   "/api/personas/%s/identities/thing/provenance"])

(defn- request
  "A ring request as jetty hands one over: the query string is still a string
   here, which is what wrap-params exists to split — and why the privacy guard is
   threaded inside it."
  [method uri & {:keys [token]}]
  (let [[path qs] (str/split uri #"\?" 2)]
    (cond-> {:request-method method :uri path}
      qs (assoc :query-string qs)
      token (assoc :headers {"authorization" (str "Bearer " token)}))))

(defn- write-request
  "A JSON PUT as the browser sends one — wrap-json-body reads the body off a
   stream, so it has to be one."
  [uri token payload]
  {:request-method :put
   :uri uri
   :headers {"authorization" (str "Bearer " token)
             "content-type" "application/json"}
   :body (java.io.ByteArrayInputStream. (.getBytes (json/write-str payload)))})

(defn- body-of [res]
  (let [b (:body res)]
    (if (string? b) (json/read-str b :key-fn keyword) b)))

(defn- with-app
  "A fresh database wired into the handlers, run at `prod?` with `devel`.

     account A  -> :open   (public)
                   :secret (private)
     account B  -> :other  (public)
     machine `a-machine`   under A, granted :secret
     machine `blind-machine` under A, granted :open only"
  [{:keys [prod? devel]} f]
  (let [conn (ds/init-conn :sqlite-in-memory {})]
    (handlers/set-conn! conn)
    (handlers/set-config! {:devel (or devel {:dangerously-skip-logins? false})})
    (try
      (let [a (ds/add-account conn "a@et.n" nil)
            b (ds/add-account conn "b@et.n" nil)]
        (ds/add-persona conn a :open "Open")
        (ds/add-persona conn a :secret "Secret" {:private? true})
        (ds/add-persona conn b :other "Other")
        (ds/add-identity conn (ds/get-persona-by-id conn :secret) "Thing" "a line" "human" {:id :thing})
        (ds/add-identity conn (ds/get-persona-by-id conn :open) "Thing" "a line" "human" {:id :thing})
        (ds/add-machine-user conn a "a-machine" {})
        (ds/add-machine-user conn a "blind-machine" {})
        (ds/grant-persona conn (:id (ds/get-machine-user conn "a-machine")) :secret)
        (ds/grant-persona conn (:id (ds/get-machine-user conn "blind-machine")) :open)
        (with-redefs [server/prod-mode? (constantly (boolean prod?))]
          (f {:conn conn :a a :b b
              :call (fn [req] (server/base-app req))})))
      (finally
        (handlers/set-conn! nil)
        (handlers/set-config! nil)
        (ds/close-conn conn)))))

(defn- machine-token!
  "Mint a token for a machine user, the way the UI does."
  [nm]
  (#'handlers/mint-machine-token! nm))

;; ---------------------------------------------------------------------------
;; The rule itself
;; ---------------------------------------------------------------------------

(deftest a-private-persona-does-not-exist-for-a-stranger
  (with-app {:prod? true}
    (fn [{:keys [call]}]
      (testing "every read under it answers exactly as an unknown id does — 404,
                and never a 403, because a 403 would confirm that it is there and
                let a stranger map an account by probing ids"
        (doseq [p read-paths]
          (let [hidden (call (request :get (format p "secret")))
                unknown (call (request :get (format p "no-such-persona-at-all")))]
            (is (= 404 (:status hidden)) p)
            (is (= {:error "Persona not found"} (body-of hidden)) p)
            (is (= (:body unknown) (:body hidden))
                (str p " — byte for byte the answer an id nobody has ever used gets")))))

      (testing "while the account's public persona is served as it always was"
        (doseq [p read-paths]
          (is (not= 404 (:status (call (request :get (format p "open")))))
              p))))))

(deftest the-account-that-holds-it-reads-it
  (with-app {:prod? true}
    (fn [{:keys [call a b]}]
      (testing "its own token opens every read"
        (doseq [p read-paths]
          (is (= 200 (:status (call (request :get (format p "secret") :token (create-token a)))))
              p)))

      (testing "another account's token is the same 404 a stranger gets, not a 403 —
                the token proves who *it* is, not that this persona exists"
        (doseq [p read-paths]
          (let [res (call (request :get (format p "secret") :token (create-token b)))]
            (is (= 404 (:status res)) p)
            (is (= {:error "Persona not found"} (body-of res)) p))))

      (testing "admin reads it anywhere, by the authority that lets Settings edit
                another account's persona"
        (doseq [p read-paths]
          (is (= 200 (:status (call (request :get (format p "secret") :token (create-admin-token)))))
              p))))))

(deftest a-grant-is-what-lets-a-machine-user-in
  (with-app {:prod? true}
    (fn [{:keys [call]}]
      (let [granted (machine-token! "a-machine")
            blind (machine-token! "blind-machine")]
        (testing "a machine user granted the persona reads it — it would be a strange
                  grant that let an agent write something it cannot fetch"
          (doseq [p (remove #(str/ends-with? % "/provenance") read-paths)]
            (is (= 200 (:status (call (request :get (format p "secret") :token granted))))
                p)))

        (testing "the guarded /provenance stays narrower than the grant: it is the
                  human's view, and a machine token is refused there as before —
                  but now only once it has got past the existence question"
          (is (= 403 (:status (call (request :get (format (last read-paths) "secret") :token granted))))))

        (testing "a sibling machine user of the same account, granted only the public
                  persona, is told nothing — a grant is per persona and this is what
                  says so on the read side too"
          (doseq [p read-paths]
            (let [res (call (request :get (format p "secret") :token blind))]
              (is (= 404 (:status res)) p)
              (is (= {:error "Persona not found"} (body-of res)) p))))))))

(deftest a-bad-token-is-no-better-than-no-token
  (with-app {:prod? true}
    (fn [{:keys [call]}]
      (testing "a forged JWT and a machine token nothing was ever minted for both
                fall to the anonymous answer rather than throwing"
        (doseq [token ["not-a-jwt-at-all" "pmu_never-minted"]]
          (let [res (call (request :get "/api/personas/secret/identities" :token token))]
            (is (= 404 (:status res)) token)
            (is (= {:error "Persona not found"} (body-of res)) token)))))))

;; ---------------------------------------------------------------------------
;; What the rule deliberately does not touch
;; ---------------------------------------------------------------------------

(deftest writing-is-still-wrap-auth-s-question
  (with-app {:prod? true}
    (fn [{:keys [call a b conn]}]
      (testing "the owner writes its private persona exactly as before"
        (let [res (call (write-request "/api/personas/secret/identities/thing"
                                       (create-token a) {:name "Thing" :text "rewritten"}))]
          (is (= 200 (:status res)))
          (is (= "rewritten" (:text (ds/get-identity conn (ds/get-persona-by-id conn :secret) :thing))))))

      (testing "and a stranger's write is refused as it always was — 403, wrap-auth's
                answer, because being allowed to write is a different question from
                being allowed to know it is there. The read guard only covers GETs;
                hiding a 403 behind a 404 here would tell the owner's own machine
                user that its grant had vanished."
        (let [res (call (write-request "/api/personas/secret/identities/thing"
                                       (create-token b) {:name "Thing" :text "no"}))]
          (is (= 403 (:status res))))))))

;; ---------------------------------------------------------------------------
;; The list
;; ---------------------------------------------------------------------------

(defn- listed [res]
  (into {} (map (juxt :id :private)) (body-of res)))

(deftest the-public-list-shows-what-the-caller-may-read
  (with-app {:prod? true}
    (fn [{:keys [call a b]}]
      (testing "an anonymous reader learns the public personas exist and no more"
        (is (= {"open" false "other" false}
               (listed (call (request :get "/api/personas"))))))

      (testing "the account that holds it sees its own private persona, flagged —
                the flag is only ever on a row the caller was entitled to, so it
                tells a stranger nothing"
        (is (= {"open" false "other" false "secret" true}
               (listed (call (request :get "/api/personas" :token (create-token a)))))))

      (testing "another account sees what the anonymous reader sees"
        (is (= {"open" false "other" false}
               (listed (call (request :get "/api/personas" :token (create-token b)))))))

      (testing "admin sees everything"
        (is (= {"open" false "other" false "secret" true}
               (listed (call (request :get "/api/personas" :token (create-admin-token)))))))

      (testing "a machine user sees the private personas it was granted, and not
                its siblings'"
        (is (= {"open" false "other" false "secret" true}
               (listed (call (request :get "/api/personas" :token (machine-token! "a-machine"))))))
        (is (= {"open" false "other" false}
               (listed (call (request :get "/api/personas" :token (machine-token! "blind-machine"))))))))))

(deftest in-dev-the-list-is-the-login-screen
  (with-app {:prod? false :devel {:dangerously-skip-logins? true}}
    (fn [{:keys [call]}]
      (testing "so it offers every persona, private ones included and flagged:
                filtering here would take away a way in rather than protect a
                secret, and an account whose only persona is private could not be
                logged into at all"
        (is (= {"admin" false "open" false "other" false "secret" true}
               (listed (call (request :get "/api/personas"))))))

      (testing "but the reads still bite in that mode — :dangerously-skip-logins?
                skips *logins*, and this is the feature rather than a credential
                check, so a caller naming nobody is told nothing"
        (is (= 404 (:status (call (request :get "/api/personas/secret/identities"))))))

      (testing "naming the persona whose account to act as is how dev says who is
                asking, exactly as it does for /api/me"
        (is (= 200 (:status (call (request :get "/api/personas/secret/identities?persona=open")))))
        (is (= 200 (:status (call (request :get "/api/personas/secret/identities?persona=admin"))))))

      (testing "and naming somebody else's persona does not open it"
        (is (= 404 (:status (call (request :get "/api/personas/secret/identities?persona=other")))))))))

;; ---------------------------------------------------------------------------
;; Making one, and changing one's mind
;; ---------------------------------------------------------------------------

(deftest a-persona-can-be-private-from-its-first-instant
  (with-app {:prod? true}
    (fn [{:keys [conn a]}]
      (let [res ((handlers/add-persona-handler true)
                 {:headers {"authorization" (str "Bearer " (create-token a))}
                  :body {:id "born-hidden" :name "Born hidden" :private true}})]
        (is (= 201 (:status res)))
        (is (true? (:private? (ds/get-persona-by-id conn :born-hidden)))
            "rather than public for as long as it takes to toggle it afterwards"))

      (testing "and saying nothing still means public, which is what personas were"
        ((handlers/add-persona-handler true)
         {:headers {"authorization" (str "Bearer " (create-token a))}
          :body {:id "born-open" :name "Born open"}})
        (is (false? (:private? (ds/get-persona-by-id conn :born-open))))))))

(deftest the-toggle-goes-both-ways-and-a-rename-does-not-publish
  (with-app {:prod? true}
    (fn [{:keys [conn call a]}]
      (let [put (fn [body]
                  (handlers/update-persona-handler
                   {:params {:name "secret"} :body body}))]

        (testing "a plain rename says nothing about privacy, so it cannot publish
                  by omission — the whole reason `contains?` and not `or`"
          (put {:name "Renamed"})
          (is (true? (:private? (ds/get-persona-by-id conn :secret))))
          (is (= "Renamed" (:name (ds/get-persona-by-id conn :secret)))))

        (testing "false publishes it, and the address starts resolving for everyone"
          (put {:private false})
          (is (false? (:private? (ds/get-persona-by-id conn :secret))))
          (is (= 200 (:status (call (request :get "/api/personas/secret/identities"))))))

        (testing "true hides it again, and the same reader is told it is not there"
          (put {:private true})
          (is (true? (:private? (ds/get-persona-by-id conn :secret))))
          (is (= 404 (:status (call (request :get "/api/personas/secret/identities"))))))

        (testing "the owner's own read never stopped working through any of it"
          (is (= 200 (:status (call (request :get "/api/personas/secret/identities"
                                             :token (create-token a)))))))))))

(deftest a-hidden-persona-keeps-its-address-spent
  (with-app {:prod? true}
    (fn [{:keys [conn call b]}]
      (testing "the id of a private persona is not free — handing it out would
                leak that persona's existence to whoever asked for it next"
        (is (false? (ds/add-persona conn b :secret "Mine now")))
        (let [res ((handlers/add-persona-handler true)
                   {:headers {"authorization" (str "Bearer " (create-token b))}
                    :body {:id "secret" :name "Mine now"}})]
          (is (= 400 (:status res)))))

      (testing "and generate-id never proposes it"
        (dotimes [_ 20]
          (let [res (handlers/generate-id-handler {})]
            (is (not= "secret" (:id (:body res)))))))

      (testing "the persona is still the account's own, untouched by the attempt"
        (is (= "Secret" (:name (ds/get-persona-by-id conn :secret))))
        (is (= 404 (:status (call (request :get "/api/personas/secret/identities")))))))))
