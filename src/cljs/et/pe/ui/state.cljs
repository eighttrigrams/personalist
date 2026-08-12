(ns et.pe.ui.state
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [ajax.core :refer [GET POST PUT DELETE]]))

(defonce app-state (r/atom {:personas []
                            :current-user nil
                            :auth-user nil
                            ;; The account behind the credentials: {:email :personas}
                            ;; for an ordinary user, {:admin true} for admin, nil
                            ;; when nobody is logged in. It comes from GET /api/me
                            ;; and nowhere else — the public persona list stopped
                            ;; saying who holds what.
                            :account nil
                            :accounts []
                            :current-tab :main
                            :show-login-modal false
                            :show-auth-modal false
                            :identities []
                            :recent-identities []
                            :recent-identities-offset 0
                            :recent-identities-has-more false
                            :selected-identity nil
                            :identity-history []
                            :editing-name ""
                            :editing-text ""
                            :slider-value 0
                            :new-identity-name ""
                            :new-identity-text ""
                            :relations []
                            :pending-relation-adds []
                            :pending-relation-removes #{}
                            ;; The ranking the user has dragged the relations
                            ;; into, as relation ids, or empty while they have
                            ;; not dragged at all — which is not the same thing
                            ;; as the default order, and only the first is
                            ;; allowed to reach the server. See reorder-relation.
                            :pending-relation-order []
                            ;; the relation being dragged, and the one the pointer
                            ;; is currently over, both by relation id
                            :drag-relation nil
                            :drag-over-relation nil
                            :show-add-relation-modal false
                            :show-search-modal false
                            :show-add-identity-modal false
                            :relation-search-query ""
                            :relation-search-results []
                            :nav-search-query ""
                            :nav-search-results []
                            :search-valid-at nil
                            ;; "fixed" (single time-slice) exploring mode: a non-logged-in
                            ;; user searched with a date, so every identity is viewed as of
                            ;; :fixed-time and the version picker is hidden.
                            :fixed-mode? false
                            :fixed-time nil
                            :show-beta-modal false
                            :not-found-persona nil
                            :not-found-identity nil
                            :password-required false
                            :login-password ""
                            :login-email ""
                            :login-error nil
                            :auth-token nil
                            :notification nil
                            ;; Which reading of the selected identity's text is on
                            ;; screen — :edit, :view, or :provenance. One tab row
                            ;; over one field, so this is the whole answer and
                            ;; there is no second flag saying the panel is open.
                            :text-editor-mode :edit
                            ;; Who wrote which lines of the selected identity's
                            ;; text: {:legend :ranges :versions} as the guarded
                            ;; provenance read answers it, or nil when it has not
                            ;; been asked for. Only ever fetched for a persona the
                            ;; logged-in account holds — see own-persona?
                            :provenance nil
                            :url-edit-mode false}))

(def api-base "")

(defn- save-auth-token [token]
  (if token
    (.setItem js/localStorage "auth-token" token)
    (.removeItem js/localStorage "auth-token")))

(defn- load-auth-token []
  (.getItem js/localStorage "auth-token"))

(defn- save-auth-persona [persona-id]
  (if persona-id
    (.setItem js/localStorage "auth-persona" persona-id)
    (.removeItem js/localStorage "auth-persona")))

(defn- load-auth-persona []
  (.getItem js/localStorage "auth-persona"))

(defn valid-email? [email]
  (and (string? email)
       (re-matches #"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$" email)))

(defn auth-headers []
  (if-let [token (:auth-token @app-state)]
    {"Authorization" (str "Bearer " token)}
    {}))

(defn- acting-persona
  "The persona id whose account this client acts as: the *logged-in* one, never
   the one being explored. Exploring somebody is not becoming them."
  []
  (or (:id (:auth-user @app-state)) (load-auth-persona) ""))

(defn- acting-as
  "Every call that acts *as an account* goes through here. In prod the Bearer
   token names it. In dev with :dangerously-skip-logins? nothing mints a token
   at all, so the server takes the acting account off ?persona=<id> instead —
   and this is the one place the client knows about that.

   **Every persona-scoped read goes through this now, not only the three calls
   that ask about an account.** A persona may be private since 006, and a read
   that named nobody would be answered 'no such persona' — including the owner's
   own browsing of his own persona. A public read carries the credential and is
   answered exactly as it was before; there is nothing here for the server to act
   on until the persona is private."
  [url]
  (if (:auth-token @app-state)
    url
    (str url (if (str/includes? url "?") "&" "?")
         "persona=" (js/encodeURIComponent (acting-persona)))))

(defn- acting-params
  "As `acting-as`, but folded into ajax's :params instead of the URL — for the
   reads that already pass params, where two things building the same query
   string would both have to be right about `?` and `&`."
  [params]
  (if (:auth-token @app-state)
    params
    (assoc params :persona (acting-persona))))

(defn fetch-me
  "Load the account behind the current credentials into :account: its email and
   its own personas, or {:admin true} for admin. This is the only thing that
   knows who is logged in — the token names an account rather than a persona,
   and the public persona list deliberately no longer says who holds what."
  [on-success on-failure]
  (GET (acting-as (str api-base "/api/me"))
    {:handler (fn [res]
                (swap! app-state assoc :account res)
                (when on-success (on-success res)))
     :headers (auth-headers)
     :response-format :json
     :keywords? true
     :error-handler (fn [err]
                      (swap! app-state assoc :account nil)
                      (when on-failure (on-failure err)))}))

(defn fetch-personas []
  (GET (acting-as (str api-base "/api/personas"))
    {:handler (fn [res]
                (swap! app-state assoc :personas res))
     :headers (auth-headers)
     :response-format :json
     :keywords? true
     :error-handler #(js/console.error "Error fetching personas" %)}))

(defn update-persona
  "Change one of the account's personas: its display `:name`, whether it is
   `:private`, or both. A key left out of `updates` is left alone by the server,
   so a rename cannot publish a private persona by omission."
  [persona-id updates on-success on-error]
  (PUT (acting-as (str api-base "/api/personas/" persona-id))
    {:params updates
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [res]
                (if (:success res)
                  (on-success)
                  (on-error "Update failed")))
     :error-handler (fn [err]
                      (let [error-msg (or (get-in err [:response :error]) "Update failed")]
                        (on-error error-msg)))}))

(defn check-password-required []
  (GET (str api-base "/api/auth/required")
    {:handler (fn [res]
                (swap! app-state assoc :password-required (:required res)))
     :response-format :json
     :keywords? true
     :error-handler #(js/console.error "Error checking password required" %)}))

(defn fetch-identities [persona-name]
  (GET (acting-as (str api-base "/api/personas/" persona-name "/identities"))
    {:handler (fn [res]
                (swap! app-state assoc :identities res))
     :headers (auth-headers)
     :response-format :json
     :keywords? true
     :error-handler #(js/console.error "Error fetching identities" %)}))

(defn fetch-recent-identities [persona-name]
  (GET (acting-as (str api-base "/api/personas/" persona-name "/identities/recent"))
    {:handler (fn [res]
                (swap! app-state assoc
                       :recent-identities (:items res)
                       :recent-identities-offset 0
                       :recent-identities-has-more (:has-more res)))
     :headers (auth-headers)
     :response-format :json
     :keywords? true
     ;; A 404 here is the server saying this persona is not there *for you* — it
     ;; is private and you are not its account. load-from-url found it in the
     ;; persona list, which in dev lists every one because that list is the login
     ;; screen; this is where the splash page learns better, rather than showing
     ;; a name with nothing under it.
     :error-handler (fn [err]
                      (if (= 404 (:status err))
                        (swap! app-state assoc :not-found-persona persona-name :current-user nil)
                        (js/console.error "Error fetching recent identities" err)))}))

(defn fetch-more-recent-identities [persona-name offset]
  (GET (acting-as (str api-base "/api/personas/" persona-name "/identities/recent?offset=" offset))
    {:handler (fn [res]
                (swap! app-state assoc
                       :recent-identities (:items res)
                       :recent-identities-offset offset
                       :recent-identities-has-more (:has-more res)))
     :headers (auth-headers)
     :response-format :json
     :keywords? true
     :error-handler #(js/console.error "Error fetching more recent identities" %)}))

(defn- normalize-time [t]
  (when t
    (-> t
        (str/replace #"Z$" "")
        (str/replace #"\.\d+$" ""))))

(defn- find-slider-index-for-time [history time-str]
  (if (or (nil? time-str) (empty? history))
    (dec (count history))
    (let [norm-time (normalize-time time-str)
          indexed (map-indexed vector history)
          matching (filter (fn [[_ entry]]
                            (<= (compare (normalize-time (:valid-from entry)) norm-time) 0))
                          indexed)]
      (if (seq matching)
        (first (last matching))
        0))))

(declare update-url-with-time)

(defn fetch-identity-history
  ([identity-id] (fetch-identity-history identity-id nil))
  ([identity-id url-time]
   (let [{:keys [current-user]} @app-state]
     (GET (acting-as (str api-base "/api/personas/" (:id current-user) "/identities/" identity-id "/history"))
       {:headers (auth-headers)
        :handler (fn [res]
                   (swap! app-state assoc :identity-history res)
                   (when (seq res)
                     (let [slider-idx (find-slider-index-for-time res url-time)
                           selected-entry (get res slider-idx)]
                       (swap! app-state assoc
                              :slider-value slider-idx
                              :editing-name (:name selected-entry)
                              :editing-text (:text selected-entry))
                       (when (and (> (count res) 1) (nil? url-time))
                         ;; canonicalise the URL to the latest version's time without
                         ;; adding a back-button stop
                         (update-url-with-time (:valid-from selected-entry) true)))))
        :response-format :json
        :keywords? true
        :error-handler #(js/console.error "Error fetching history" %)}))))

(defn fetch-identity-at [identity-id time-str]
  (let [{:keys [current-user]} @app-state]
    (GET (str api-base "/api/personas/" (:id current-user) "/identities/" identity-id "/at")
      {:params (acting-params {:time time-str})
       :headers (auth-headers)
       :handler (fn [res]
                  (swap! app-state assoc :editing-name (:name res) :editing-text (:text res)))
       :response-format :json
       :keywords? true
       :error-handler #(js/console.error "Error fetching identity at time" %)})))

(defn fetch-relations
  ([identity-id] (fetch-relations identity-id nil))
  ([identity-id time-str]
   (let [{:keys [current-user]} @app-state
         url (str api-base "/api/personas/" (:id current-user) "/identities/" identity-id "/relations")]
     (GET (acting-as (if time-str (str url "?time=" (js/encodeURIComponent time-str)) url))
       {:headers (auth-headers)
        :handler (fn [res]
                   (swap! app-state assoc :relations res))
        :response-format :json
        :keywords? true
        :error-handler #(js/console.error "Error fetching relations" %)}))))

;; ---------------------------------------------------------------------------
;; Provenance — who wrote which lines, for the account that holds the persona
;;
;; The read is guarded (401/403), so the button that opens it is offered only
;; when the client can already tell the answer is the caller's to have. That is
;; not a security measure — the server's guard is — it is what keeps a visitor
;; from being shown a control that would only ever refuse them.
;; ---------------------------------------------------------------------------

(defn own-persona?
  "Whether the persona currently being looked at is one the logged-in account
   holds. False for a visitor, for a logged-out reader, and for a logged-in
   account looking at somebody else's persona.

   Admin holds no personas of its own and may see any of them, the same
   authority by which Settings edits them."
  []
  (let [{:keys [account current-user]} @app-state]
    (boolean (and account
                  current-user
                  (or (:admin account)
                      (some #(= (:id %) (:id current-user)) (:personas account)))))))

(defn fetch-provenance
  "Load the provenance of one identity into :provenance. Silent on failure, and
   the field falls back to the rendered text: the answer is the account's own, so
   a 401 or a 403 here means the client offered a tab it should not have, and the
   honest thing on screen is the text rather than an error about somebody else's
   persona."
  [identity-id]
  (let [{:keys [current-user]} @app-state]
    (GET (acting-as (str api-base "/api/personas/" (:id current-user)
                         "/identities/" identity-id "/provenance"))
      {:handler (fn [res] (swap! app-state assoc :provenance res))
       :headers (auth-headers)
       :response-format :json
       :keywords? true
       :error-handler (fn [err]
                        (swap! app-state assoc :provenance nil :text-editor-mode :view)
                        (js/console.error "Error fetching provenance" err))})))

(defn select-text-mode
  "Put one of the three readings of the text on screen: the editor, the rendered
   markdown, or who wrote which lines. The tab row is the only thing that says
   which, so this is the only thing that has to be set.

   Choosing provenance fetches it, every time it is chosen rather than cached
   against the identity: a save writes a new version, which is exactly when the
   answer changes, and a stale spectrum is a claim about who wrote lines that are
   no longer there."
  [mode]
  (let [{:keys [selected-identity]} @app-state]
    (swap! app-state assoc :text-editor-mode mode)
    (when (and (= mode :provenance) selected-identity)
      (fetch-provenance (:identity selected-identity)))))

(defn format-time-for-url [time-str]
  ;; Keep full (millisecond) precision: a version's valid-from can carry sub-second
  ;; precision, and the relations/identity-at queries match on `valid_from <= t`.
  ;; Truncating to whole seconds would put the URL time just before a freshly-saved
  ;; version, hiding that version and the relations committed with it on reload.
  (when time-str
    (if (str/ends-with? time-str "Z")
      time-str
      (str time-str "Z"))))

(defn update-url
  ([persona-id identity-id editing?]
   (update-url persona-id identity-id editing? nil false))
  ([persona-id identity-id editing? time-str]
   (update-url persona-id identity-id editing? time-str false))
  ([persona-id identity-id editing? time-str replace?]
   (let [{:keys [fixed-mode? fixed-time]} @app-state
         ;; in fixed mode the whole session stays pinned to a single time-slice
         time-str (if fixed-mode? fixed-time time-str)
         params (cond-> []
                  editing? (conj "edit=true")
                  fixed-mode? (conj "fixed=true")
                  time-str (conj (str "time=" (js/encodeURIComponent (format-time-for-url time-str)))))
         query (when (seq params) (str "?" (str/join "&" params)))
         path (if identity-id
                (str "/" persona-id "/" identity-id query)
                (if persona-id
                  (str "/" persona-id)
                  "/"))]
     (swap! app-state assoc :url-edit-mode editing?)
     ;; replace? rewrites the current entry instead of pushing a new one, so the
     ;; canonical (time-pinned) URL doesn't leave a spurious back-button stop
     (if replace?
       (.replaceState js/history nil "" path)
       (.pushState js/history nil "" path)))))

(defn- can-edit? []
  (some? (:auth-user @app-state)))

(defn update-url-with-time
  ([time-str] (update-url-with-time time-str false))
  ([time-str replace?]
   (let [{:keys [current-user selected-identity]} @app-state]
     (when (and current-user selected-identity)
       (update-url (:id current-user) (:identity selected-identity) (can-edit?) time-str replace?)))))

(defn parse-url []
  (let [pathname (.-pathname js/window.location)
        search (.-search js/window.location)
        parts (vec (filter seq (str/split pathname #"/")))
        editing? (str/includes? search "edit=true")
        fixed? (str/includes? search "fixed=true")
        time-match (re-find #"time=([^&]+)" search)
        time-param (when time-match (js/decodeURIComponent (second time-match)))]
    {:persona-id (first parts)
     :identity-id (second parts)
     :editing? editing?
     :fixed? fixed?
     :time time-param}))

(def ^:private admin-user
  "Admin has no account and therefore no persona; the header and the tabs still
   want something to name."
  {:id "admin" :name "Admin"})

(defn restore-auth
  "Re-establish who is logged in after a reload, then call `on-done` — the rest
   of the boot depends on the answer (fixed mode is an exploring-user feature,
   so it must know). The stored token names an account, not a persona, so this
   asks /api/me and lands on the persona that was last active, or the account's
   first. A token that no longer resolves is dropped rather than believed."
  [on-done]
  (if-not (or (load-auth-token) (load-auth-persona))
    (on-done)
    (do
      (when-let [token (load-auth-token)]
        (swap! app-state assoc :auth-token token))
      (fetch-me
       (fn [account]
         (if (:admin account)
           (swap! app-state assoc :auth-user admin-user)
           (let [last-active (load-auth-persona)
                 persona (or (first (filter #(= (:id %) last-active) (:personas account)))
                             (first (:personas account)))]
             ;; An account may hold none. It is logged in all the same — :account
             ;; is what says so — with no active persona until it makes one, and
             ;; the profile page is where it makes one. Only when the URL names
             ;; no persona of its own: arriving at /someone-else means to look at
             ;; someone else, and being logged in holding nothing does not change
             ;; that.
             (swap! app-state assoc :auth-user persona)
             (when (and (nil? persona) (nil? (:persona-id (parse-url))))
               (swap! app-state assoc :current-tab :profile))
             (save-auth-persona (:id persona))))
         (on-done))
       (fn [_]
         (save-auth-token nil)
         (save-auth-persona nil)
         (swap! app-state assoc :auth-token nil :auth-user nil :account nil)
         (on-done))))))

(defn load-from-url [on-complete]
  (let [{:keys [persona-id identity-id editing? fixed? time]} (parse-url)]
    (swap! app-state assoc :url-edit-mode editing? :not-found-persona nil :not-found-identity nil)
    (GET (acting-as (str api-base "/api/personas"))
      {:headers (auth-headers)
       :handler (fn [personas]
                  (swap! app-state assoc :personas personas)
                  ;; fixed mode is an exploring-user feature; a logged-in user ignores it
                  (let [fixed? (and fixed? (nil? (:auth-user @app-state)))]
                    (swap! app-state assoc :fixed-mode? fixed? :fixed-time (when fixed? time)))
                  (when persona-id
                    (if-let [persona (first (filter #(= (:id %) persona-id) personas))]
                      (do
                        (swap! app-state assoc
                               :current-user persona
                               :identities []
                               :recent-identities []
                               :recent-identities-offset 0
                               :recent-identities-has-more false
                               :selected-identity nil)
                        (fetch-recent-identities persona-id)
                        (when identity-id
                          (GET (acting-as (str api-base "/api/personas/" persona-id "/identities/" (name identity-id)))
                            {:headers (auth-headers)
                             :handler (fn [identity]
                                        (swap! app-state assoc
                                               :selected-identity identity
                                               :editing-name (:name identity)
                                               :editing-text (:text identity))
                                        (fetch-identity-history identity-id time)
                                        (fetch-relations identity-id time)
                                        (when time
                                          (fetch-identity-at identity-id time))
                                        (when on-complete (on-complete editing?)))
                             :response-format :json
                             :keywords? true
                             :error-handler (fn [err]
                                              (swap! app-state assoc :not-found-identity identity-id)
                                              (js/console.error "Error fetching identity" err)
                                              (when on-complete (on-complete editing?)))}))
                        (when (and (not identity-id) on-complete) (on-complete editing?)))
                      (swap! app-state assoc :not-found-persona persona-id)))
                  ;; root URL ("/"): reset to the landing state (e.g. back button)
                  (when-not persona-id
                    (swap! app-state assoc
                           :current-user nil
                           :selected-identity nil
                           :identities []
                           :recent-identities []
                           :not-found-persona nil
                           :not-found-identity nil))
                  (when (and (not persona-id) on-complete) (on-complete editing?)))
       :response-format :json
       :keywords? true
       :error-handler #(js/console.error "Error fetching personas" %)})))

(defn select-identity
  ([identity] (select-identity identity nil))
  ([identity time-str]
   (let [{:keys [current-user fixed-mode? fixed-time text-editor-mode]} @app-state
         ;; fixed mode keeps every identity pinned to the same time-slice
         time-str (if fixed-mode? fixed-time time-str)]
     (swap! app-state assoc
            :selected-identity identity
            :editing-name (:name identity)
            :editing-text (:text identity)
            :relations []
            :pending-relation-adds []
            :pending-relation-removes #{}
            :pending-relation-order []
            :drag-relation nil
            :drag-over-relation nil
            ;; the answer is about one identity's text, so it goes with the
            ;; identity rather than lingering over the next one
            :provenance nil
            ;; ...and neither does the tab that shows it: the next identity's
            ;; answer has not been asked for, and may not even be the caller's to
            ;; have. The edit/view choice does carry over.
            :text-editor-mode (if (= :provenance text-editor-mode) :edit text-editor-mode)
            :not-found-identity nil)
     (update-url (:id current-user) (:identity identity) (can-edit?) time-str)
     (fetch-identity-history (:identity identity) time-str)
     (fetch-relations (:identity identity) time-str)
     (when time-str
       (fetch-identity-at (:identity identity) time-str)))))

(defn exit-fixed-mode
  "Leave the single-time-slice exploring mode and return to normal browsing
   (version picker available again), staying on the very version that was in view
   at the moment of leaving."
  []
  (let [{:keys [current-user selected-identity identity-history slider-value]} @app-state
        version-time (:valid-from (get identity-history slider-value))]
    (swap! app-state assoc :fixed-mode? false :fixed-time nil)
    (if selected-identity
      (select-identity selected-identity version-time)
      (update-url (:id current-user) nil false))))

(defn add-identity []
  (let [{:keys [current-user new-identity-name new-identity-text]} @app-state]
    (when (and current-user (seq new-identity-name) (seq new-identity-text))
      (let [name-to-select new-identity-name
            text-to-select new-identity-text]
        (POST (str api-base "/api/personas/" (:id current-user) "/identities")
          {:params {:name new-identity-name :text new-identity-text}
           :format :json
           :response-format :json
           :keywords? true
           :headers (auth-headers)
           :handler (fn [res]
                      (let [generated-id (:id res)]
                        (swap! app-state assoc
                               :new-identity-name ""
                               :new-identity-text ""
                               :show-add-identity-modal false)
                        (fetch-identities (:id current-user))
                        (fetch-recent-identities (:id current-user))
                        (select-identity {:identity generated-id :name name-to-select :text text-to-select})))
           :error-handler (fn [err]
                        (js/console.error "Error adding identity" err)
                        (swap! app-state assoc :notification {:message "Failed to add identity. Please try again." :type :error})
                        (js/setTimeout #(swap! app-state assoc :notification nil) 5000))})))))

(defn- relation-target
  "The target id in a relation id of the form \"source/target\". The server's own
   helper by the same name is the authority on that shape (ds.sqlite)."
  [rel-id]
  (subs rel-id (inc (.indexOf rel-id "/"))))

(defn update-identity [identity-id name text]
  (let [{:keys [current-user pending-relation-adds pending-relation-removes
                pending-relation-order]} @app-state]
    (PUT (str api-base "/api/personas/" (:id current-user) "/identities/" identity-id)
      {:params {:name name
                :text text
                ;; commit pending relation changes tagged with this version's timestamp
                :relation_adds (mapv :target pending-relation-adds)
                :relation_removes (vec pending-relation-removes)
                ;; ...and the ranking, but only if the user actually dragged
                ;; something: an empty list says nothing about the order, and
                ;; that is what every plain edit has to say, or saving twice
                ;; would flatten a ranking nobody touched.
                :relation_order (mapv relation-target pending-relation-order)}
       :format :json
       :headers (auth-headers)
       :handler (fn [_]
                  (swap! app-state assoc
                         :pending-relation-adds []
                         :pending-relation-removes #{}
                         :pending-relation-order [])
                  (fetch-identities (:id current-user))
                  (fetch-recent-identities (:id current-user))
                  (fetch-identity-history identity-id)
                  ;; the new version is now latest -> show its (current) relations
                  (fetch-relations identity-id)
                  ;; and it is a new version, which is precisely when who-wrote-what
                  ;; changes — a field left showing the previous answer would be a
                  ;; claim about lines that are no longer there
                  (when (= :provenance (:text-editor-mode @app-state))
                    (fetch-provenance identity-id)))
       :error-handler (fn [err]
                        (js/console.error "Error updating identity" err)
                        (swap! app-state assoc :notification {:message "Failed to save. Please try again." :type :error})
                        (js/setTimeout #(swap! app-state assoc :notification nil) 5000))})))

;; Relations are committed together with the identity version on Save, so adding,
;; removing and reordering a relation only stages a pending change here.
;; Navigating away (which resets the pending sets) discards unsaved changes.

(defn- base-relations
  "The relation set as it will stand after Save, in the order it is stored in:
   what the server sent, minus the ones staged for removal, plus the staged
   additions at the end — which is exactly what the server will build out of
   :relation_adds and :relation_removes."
  [{:keys [relations pending-relation-adds pending-relation-removes]}]
  (vec (concat (remove #(contains? pending-relation-removes (:id %)) relations)
               pending-relation-adds)))

(defn effective-relations
  "The relation list as the edit view previews it: the set above, ranked the way
   the user has dragged it.

   The ranking is applied the way the server applies it — the ids named in
   :pending-relation-order first, in that order, everything else keeping its
   place after them on a stable sort — so that what is on screen before Save is
   what comes back after it. A relation added *after* a drag is not named by it
   and so lands at the end, in both places."
  [{:keys [pending-relation-order] :as state}]
  (let [base (base-relations state)]
    (if (empty? pending-relation-order)
      base
      (let [rank (into {} (map-indexed (fn [i id] [id i]) pending-relation-order))]
        (vec (sort-by #(get rank (:id %) js/Infinity) base))))))

(defn relations-reordered?
  "Whether the previewed ranking differs from the stored one, which is what the
   view announces as unsaved. Dragging an item and putting it back is no change
   and is not announced as one."
  [state]
  (and (seq (:pending-relation-order state))
       (not= (mapv :id (effective-relations state))
             (mapv :id (base-relations state)))))

(defn already-related?
  "Whether the identity being edited already relates to `identity-id` — counting
   what is on the board *and* what has been added without saving yet, because both
   are relations as far as the next Save is concerned.

   A relation staged for *removal* is not on the board and so is not counted: its
   target may be offered again, and accepting it merely cancels the removal (see
   add-relation)."
  [state identity-id]
  (contains? (into #{} (map #(name (:target %))) (effective-relations state))
             (name identity-id)))

(defn offerable-as-relation?
  "Whether `identity-id` may be offered as something to relate *to*: neither the
   identity being edited — an identity does not relate to itself — nor one it is
   related to already.

   The whole rule lives here rather than half in the search and half in the list
   that renders it, so that a suggestion cannot come back by a route that only
   remembered one half of it."
  [state identity-id]
  (and (not= (some-> (:identity (:selected-identity state)) name)
             (name identity-id))
       (not (already-related? state identity-id))))

(defn- index-of [coll x]
  (first (keep-indexed (fn [i v] (when (= v x) i)) coll)))

(defn set-drag-relation [relation-id]
  (swap! app-state assoc :drag-relation relation-id))

(defn set-drag-over-relation
  "Which relation the pointer is over, and which half of it — the view draws the
   line where the drop would land, and the drop itself reads the same answer off
   its own event."
  [relation-id position]
  (swap! app-state assoc :drag-over-relation (when relation-id
                                               {:id relation-id :position position})))

(defn clear-relation-drag-state []
  (swap! app-state assoc :drag-relation nil :drag-over-relation nil))

(defn reorder-relation
  "Stage a ranking: `relation-id` moves to just before or just after
   `target-id` (`position` is \"before\" or \"after\") in the list as it is
   currently previewed.

   What is remembered is the whole resulting order rather than the one move,
   because the list on screen is what Save has to reproduce, and the server is
   handed that same order to sort by."
  [relation-id target-id position]
  (when (and relation-id target-id (not= relation-id target-id))
    (let [ids (mapv :id (effective-relations @app-state))
          without (vec (remove #(= % relation-id) ids))]
      (when-let [idx (index-of without target-id)]
        (let [at (if (= position "after") (inc idx) idx)]
          (swap! app-state assoc
                 :pending-relation-order (vec (concat (subvec without 0 at)
                                                     [relation-id]
                                                     (subvec without at))))))))
  (clear-relation-drag-state))

(defn add-relation [target-id target-name]
  (let [{:keys [selected-identity relations pending-relation-adds pending-relation-removes]} @app-state
        rel-id (str (:identity selected-identity) "/" (name target-id))
        existing? (or (some #(= (:id %) rel-id) relations)
                      (some #(= (:id %) rel-id) pending-relation-adds))]
    (swap! app-state assoc
           :show-add-relation-modal false
           :relation-search-query ""
           :relation-search-results [])
    (cond
      ;; re-adding one that was staged for removal -> just cancel the removal
      (contains? pending-relation-removes rel-id)
      (swap! app-state update :pending-relation-removes disj rel-id)
      ;; new, not-yet-existing relation -> stage it
      (not existing?)
      (swap! app-state update :pending-relation-adds conj
             {:id rel-id :target (name target-id) :target-name target-name :pending true}))))

(defn delete-relation [relation-id]
  (let [{:keys [pending-relation-adds]} @app-state]
    (if (some #(= (:id %) relation-id) pending-relation-adds)
      ;; an unsaved staged add -> just drop it
      (swap! app-state update :pending-relation-adds
             (fn [adds] (vec (remove #(= (:id %) relation-id) adds))))
      ;; a persisted relation -> stage its removal for the next Save
      (swap! app-state update :pending-relation-removes conj relation-id))))

(defn search-identities
  ([query callback] (search-identities query nil callback))
  ([query valid-at callback]
   (let [{:keys [current-user]} @app-state
         params (cond-> {:q query}
                  valid-at (assoc :valid_at valid-at))]
     (GET (str api-base "/api/personas/" (:id current-user) "/identities/search")
       {:params (acting-params params)
        :headers (auth-headers)
        :handler callback
        :response-format :json
        :keywords? true
        :error-handler #(js/console.error "Error searching identities" %)}))))

(defn open-search-modal
  "Open the search modal. In fixed mode the scoped date is pre-selected (and its
   results pre-loaded) so the user stays anchored to the same time-slice."
  []
  (let [{:keys [fixed-mode? fixed-time]} @app-state]
    (if (and fixed-mode? fixed-time)
      (let [date (first (str/split fixed-time #"T"))]
        (swap! app-state assoc :show-search-modal true :nav-search-query "" :search-valid-at date)
        (search-identities "" fixed-time
                           #(swap! app-state assoc :nav-search-results (take 5 %))))
      (swap! app-state assoc :show-search-modal true))))

(defn- enter-persona [persona]
  (swap! app-state assoc
         :current-user persona
         :identities []
         :recent-identities []
         :selected-identity nil
         :identity-history []
         :not-found-persona nil
         :not-found-identity nil)
  (update-url (:id persona) nil false)
  (fetch-identities (:id persona))
  (fetch-recent-identities (:id persona)))

(defn select-persona [persona]
  (swap! app-state assoc :show-login-modal false)
  (enter-persona persona))

(defn login-user
  "Enter `persona` as the logged-in face. One account may hold several, so this
   only ever says which one is active; the account itself lives in :account."
  [persona]
  (save-auth-persona (:id persona))
  (swap! app-state assoc
         :auth-user persona
         :show-auth-modal false
         :login-password ""
         :login-error nil)
  (enter-persona persona))

(defn switch-persona
  "Make another of the account's own personas the active one: the face the
   header names, the /:id in the address bar, and the one a new identity is
   written under."
  [persona]
  (swap! app-state assoc :current-tab :main)
  (login-user persona))

(defn- enter-admin []
  (swap! app-state assoc
         :auth-user admin-user
         :show-auth-modal false
         :login-password ""
         :login-error nil
         :current-tab :settings)
  (save-auth-persona (:id admin-user)))

(defn- enter-with-no-persona
  "An account that holds no personas is logged in all the same, and lands on its
   profile page — which is where it makes one, and the reason removing the last
   persona is allowed. :auth-user stays nil, and everything in the header that
   would act on an active persona is hidden while it is."
  []
  (save-auth-persona nil)
  (swap! app-state assoc
         :auth-user nil
         :current-user nil
         :selected-identity nil
         :identities []
         :recent-identities []
         :identity-history []
         :show-auth-modal false
         :login-email ""
         :login-password ""
         :login-error nil
         :current-tab :profile)
  (.pushState js/history nil "" "/"))

(defn- land-after-login
  "Where a successful login puts you. The token names an account, so the client
   has to ask which personas it holds and land on the first — the one the
   account sorts first, not whichever id was typed at the login screen. When
   `preferred` is given (dev mode, where you pick a face to log in as) that one
   is entered instead. An account holding none lands on its profile page rather
   than being turned away: zero personas is a legitimate state."
  ([] (land-after-login nil))
  ([preferred]
   (fetch-me
    (fn [account]
      (cond
        (:admin account) (enter-admin)
        (seq (:personas account)) (login-user (or preferred (first (:personas account))))
        :else (enter-with-no-persona)))
    (fn [_]
      (swap! app-state assoc :login-error "Invalid credentials")))))

(defn- login-succeeded [res preferred]
  (save-auth-token (:token res))
  (swap! app-state assoc
         :auth-token (:token res)
         :show-auth-modal false
         :login-email ""
         :login-password "")
  (land-after-login preferred))

(defn attempt-login
  "The only login there is. One identifier field, holding the account's email —
   logging in by persona id is gone, and a machine user has no password to send
   here at all."
  []
  (let [username (:login-email @app-state)
        password (:login-password @app-state)]
    (POST (str api-base "/api/auth/login")
      {:params {:username username :password password}
       :format :json
       :response-format :json
       :keywords? true
       :handler (fn [res]
                  (if (:success res)
                    (do (swap! app-state assoc :login-error nil)
                        (login-succeeded res nil))
                    (swap! app-state assoc :login-error "Invalid credentials")))
       :error-handler (fn [_]
                        (swap! app-state assoc :login-error "Invalid credentials"))})))

(defn try-login
  "Dev only (:dangerously-skip-logins?), where the auth modal offers the persona
   list instead of a credentials form: no token is minted, and the persona just
   picked is also what tells the server which account to act as (see acting-as)."
  [persona]
  (save-auth-persona (:id persona))
  (land-after-login persona))

(defn logout-user []
  (save-auth-token nil)
  (save-auth-persona nil)
  (swap! app-state assoc
         :auth-user nil
         :current-user nil
         :auth-token nil
         :account nil
         :accounts []
         :current-tab :main
         :identities []
         :selected-identity nil
         :identity-history []
         :provenance nil
         ;; nobody's answer to show any more, so not the tab that shows it either
         :text-editor-mode :edit)
  (.pushState js/history nil "" "/"))

;; ---------------------------------------------------------------------------
;; The account's own personas — the profile page
;; ---------------------------------------------------------------------------

(defn create-persona
  "Mint another persona under the logged-in account. The server takes the
   account off the token, so there is nothing here that could name someone
   else's.

   `private?` is passed at creation rather than toggled afterwards so a persona
   meant to be private never spends an instant as a public address."
  [id display-name private? on-success on-error]
  (POST (acting-as (str api-base "/api/personas"))
    {:params {:id id :name display-name :private (boolean private?)}
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [res]
                (fetch-personas)
                (fetch-me (fn [_] (on-success res)) nil))
     :error-handler (fn [err]
                      (on-error (or (get-in err [:response :error]) "Could not create persona")))}))

(defn delete-persona
  "Destroy one of the account's personas and everything under it. `confirm` is
   the urbit id the user typed; the server checks it again, because the dialog
   is not the authority on anything."
  [persona-id confirm on-success on-error]
  (DELETE (acting-as (str api-base "/api/personas/" persona-id))
    {:params {:confirm confirm}
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [res]
                (fetch-personas)
                ;; The face that just went cannot stay the active one, and in dev
                ;; it is also what names the account to the server (see acting-as)
                ;; — so it has to be replaced from the personas already in hand,
                ;; before anything asks /api/me again with a dead id.
                (when (= persona-id (:id (:auth-user @app-state)))
                  (if-let [persona (first (remove #(= persona-id (:id %))
                                                  (:personas (:account @app-state))))]
                    (login-user persona)
                    ;; that was the last one: stay logged in, stay on this page,
                    ;; and show the empty state
                    (enter-with-no-persona)))
                (fetch-me (fn [_] (on-success res))
                          (fn [_] (on-success res))))
     :error-handler (fn [err]
                      (on-error (or (get-in err [:response :error]) "Could not remove persona")))}))

(defn create-machine-user
  "Mint a machine user under the logged-in account. The token comes back in the
   response and nowhere else, ever — only its hash is kept — so the caller must
   put it in front of the user at once."
  [nm can-create? on-success on-error]
  (POST (acting-as (str api-base "/api/machine-users"))
    {:params {:name nm :can_create can-create?}
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [res] (fetch-me (fn [_] (on-success res)) nil))
     :error-handler (fn [err]
                      (on-error (or (get-in err [:response :error]) "Could not create machine user")))}))

(defn rotate-machine-token
  "Issue a new token and answer it, once. Whatever is using the old one stops
   working the moment this returns."
  [nm on-success on-error]
  (POST (acting-as (str api-base "/api/machine-users/" (js/encodeURIComponent nm) "/token"))
    {:format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [res] (on-success res))
     :error-handler (fn [err]
                      (on-error (or (get-in err [:response :error]) "Could not rotate token")))}))

(defn update-machine-user
  "Set which personas a machine user may write, and whether it may create them.
   :personas is the whole grant list rather than a patch — it is the checkbox
   grid, and the server replaces what it holds with what is sent."
  [nm {:keys [personas can-create?]} on-success on-error]
  (PUT (acting-as (str api-base "/api/machine-users/" (js/encodeURIComponent nm)))
    {:params (cond-> {}
               personas (assoc :personas personas)
               (some? can-create?) (assoc :can_create can-create?))
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [_] (fetch-me (fn [_] (on-success)) nil))
     :error-handler (fn [err]
                      (on-error (or (get-in err [:response :error]) "Could not save")))}))

(defn delete-machine-user [nm on-success on-error]
  (DELETE (acting-as (str api-base "/api/machine-users/" (js/encodeURIComponent nm)))
    {:format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [_] (fetch-me (fn [_] (on-success)) nil))
     :error-handler (fn [err]
                      (on-error (or (get-in err [:response :error]) "Could not remove machine user")))}))

(defn fetch-accounts
  "The admin listing: every account with its email and its personas."
  []
  (GET (acting-as (str api-base "/api/accounts"))
    {:handler (fn [res] (swap! app-state assoc :accounts res))
     :headers (auth-headers)
     :response-format :json
     :keywords? true
     :error-handler #(js/console.error "Error fetching accounts" %)}))

(defn create-account
  "Admin only: an account and its first persona in one call."
  [params on-success on-error]
  (POST (acting-as (str api-base "/api/accounts"))
    {:params params
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [res] (fetch-accounts) (fetch-personas) (on-success res))
     :error-handler (fn [err]
                      (on-error (or (get-in err [:response :error]) "Could not create account")))}))

(defn generate-id [callback]
  (GET (str api-base "/api/generate-id")
    {:handler (fn [res]
                (callback (:id res)))
     :response-format :json
     :keywords? true
     :error-handler #(js/console.error "Error generating ID" %)}))

(defn show-notification [message type]
  (swap! app-state assoc :notification {:message message :type type})
  (js/setTimeout #(swap! app-state assoc :notification nil) 5000))

(defn dismiss-notification []
  (swap! app-state assoc :notification nil))
