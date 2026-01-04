(ns et.pe.ui.state
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [ajax.core :refer [GET POST PUT DELETE]]))

(defonce app-state (r/atom {:personas []
                            :current-user nil
                            :auth-user nil
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
                            :show-add-relation-modal false
                            :show-search-modal false
                            :show-add-identity-modal false
                            :relation-search-query ""
                            :relation-search-results []
                            :nav-search-query ""
                            :nav-search-results []
                            :search-valid-at nil
                            :show-beta-modal false
                            :password-required false
                            :login-password ""
                            :login-email ""
                            :login-error nil
                            :login-persona nil
                            :show-password-modal false
                            :auth-token nil
                            :notification nil
                            :text-editor-mode :edit
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

(defn- extract-persona-from-token [token]
  (when token
    (try
      (-> token
          (str/split #"\.")
          second
          js/atob
          js/JSON.parse
          (js->clj :keywordize-keys true)
          :persona)
      (catch :default _ nil))))

(defn valid-email? [email]
  (and (string? email)
       (re-matches #"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$" email)))

(defn auth-headers []
  (if-let [token (:auth-token @app-state)]
    {"Authorization" (str "Bearer " token)}
    {}))

(defn fetch-personas []
  (GET (str api-base "/api/personas")
    {:handler (fn [res]
                (swap! app-state assoc :personas res))
     :response-format :json
     :keywords? true
     :error-handler #(js/console.error "Error fetching personas" %)}))

(defn update-persona [persona-id updates on-success on-error]
  (PUT (str api-base "/api/personas/" persona-id)
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
  (GET (str api-base "/api/personas/" persona-name "/identities")
    {:handler (fn [res]
                (swap! app-state assoc :identities res))
     :response-format :json
     :keywords? true
     :error-handler #(js/console.error "Error fetching identities" %)}))

(defn fetch-recent-identities [persona-name]
  (GET (str api-base "/api/personas/" persona-name "/identities/recent")
    {:handler (fn [res]
                (swap! app-state assoc
                       :recent-identities (:items res)
                       :recent-identities-offset 0
                       :recent-identities-has-more (:has-more res)))
     :response-format :json
     :keywords? true
     :error-handler #(js/console.error "Error fetching recent identities" %)}))

(defn fetch-more-recent-identities [persona-name offset]
  (GET (str api-base "/api/personas/" persona-name "/identities/recent?offset=" offset)
    {:handler (fn [res]
                (swap! app-state assoc
                       :recent-identities (:items res)
                       :recent-identities-offset offset
                       :recent-identities-has-more (:has-more res)))
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
     (GET (str api-base "/api/personas/" (:id current-user) "/identities/" identity-id "/history")
       {:handler (fn [res]
                   (swap! app-state assoc :identity-history res)
                   (when (seq res)
                     (let [slider-idx (find-slider-index-for-time res url-time)
                           selected-entry (get res slider-idx)]
                       (swap! app-state assoc :slider-value slider-idx)
                       (when (and (> (count res) 1) (nil? url-time))
                         (update-url-with-time (:valid-from selected-entry))))))
        :response-format :json
        :keywords? true
        :error-handler #(js/console.error "Error fetching history" %)}))))

(defn fetch-identity-at [identity-id time-str]
  (let [{:keys [current-user]} @app-state]
    (GET (str api-base "/api/personas/" (:id current-user) "/identities/" identity-id "/at")
      {:params {:time time-str}
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
     (GET (if time-str (str url "?time=" (js/encodeURIComponent time-str)) url)
       {:handler (fn [res]
                   (swap! app-state assoc :relations res))
        :response-format :json
        :keywords? true
        :error-handler #(js/console.error "Error fetching relations" %)}))))

(defn format-time-for-url [time-str]
  (when time-str
    (let [base (-> time-str
                   (str/replace #"\.\d+Z?$" "")
                   (str/replace #"Z$" ""))]
      (str base "Z"))))

(defn update-url
  ([persona-id identity-id editing?]
   (update-url persona-id identity-id editing? nil))
  ([persona-id identity-id editing? time-str]
   (let [params (cond-> []
                  editing? (conj "edit=true")
                  time-str (conj (str "time=" (js/encodeURIComponent (format-time-for-url time-str)))))
         query (when (seq params) (str "?" (str/join "&" params)))
         path (if identity-id
                (str "/" persona-id "/" identity-id query)
                (if persona-id
                  (str "/" persona-id)
                  "/"))]
     (swap! app-state assoc :url-edit-mode editing?)
     (.pushState js/history nil "" path))))

(defn- can-edit? []
  (some? (:auth-user @app-state)))

(defn update-url-with-time [time-str]
  (let [{:keys [current-user selected-identity]} @app-state]
    (when (and current-user selected-identity)
      (update-url (:id current-user) (:identity selected-identity) (can-edit?) time-str))))

(defn parse-url []
  (let [pathname (.-pathname js/window.location)
        search (.-search js/window.location)
        parts (vec (filter seq (str/split pathname #"/")))
        editing? (str/includes? search "edit=true")
        time-match (re-find #"time=([^&]+)" search)
        time-param (when time-match (js/decodeURIComponent (second time-match)))]
    {:persona-id (first parts)
     :identity-id (second parts)
     :editing? editing?
     :time time-param}))

(defn- restore-auth-from-storage [personas]
  (if-let [token (load-auth-token)]
    (when-let [persona-id (extract-persona-from-token token)]
      (when-let [persona (first (filter #(= (:id %) persona-id) personas))]
        (swap! app-state assoc
               :auth-token token
               :auth-user persona)))
    (when-let [persona-id (load-auth-persona)]
      (when-let [persona (first (filter #(= (:id %) persona-id) personas))]
        (swap! app-state assoc :auth-user persona)))))

(defn load-from-url [on-complete]
  (let [{:keys [persona-id identity-id editing? time]} (parse-url)]
    (swap! app-state assoc :url-edit-mode editing?)
    (GET (str api-base "/api/personas")
      {:handler (fn [personas]
                  (swap! app-state assoc :personas personas)
                  (restore-auth-from-storage personas)
                  (when persona-id
                    (when-let [persona (first (filter #(= (:id %) persona-id) personas))]
                      (swap! app-state assoc
                             :current-user persona
                             :identities []
                             :selected-identity nil)
                      (GET (str api-base "/api/personas/" persona-id "/identities")
                        {:handler (fn [identities]
                                    (swap! app-state assoc :identities identities)
                                    (when identity-id
                                      (when-let [identity (first (filter #(= (:identity %) identity-id) identities))]
                                        (swap! app-state assoc
                                               :selected-identity identity
                                               :editing-name (:name identity)
                                               :editing-text (:text identity))
                                        (fetch-identity-history identity-id time)
                                        (fetch-relations identity-id time)
                                        (when time
                                          (fetch-identity-at identity-id time))))
                                    (when on-complete (on-complete editing?)))
                         :response-format :json
                         :keywords? true
                         :error-handler #(js/console.error "Error fetching identities" %)})))
                  (when (and (not persona-id) on-complete) (on-complete editing?)))
       :response-format :json
       :keywords? true
       :error-handler #(js/console.error "Error fetching personas" %)})))

(defn select-identity
  ([identity] (select-identity identity nil))
  ([identity time-str]
   (let [{:keys [current-user]} @app-state]
     (swap! app-state assoc
            :selected-identity identity
            :editing-name (:name identity)
            :editing-text (:text identity)
            :relations [])
     (update-url (:id current-user) (:identity identity) (can-edit?) time-str)
     (fetch-identity-history (:identity identity) time-str)
     (fetch-relations (:identity identity) time-str)
     (when time-str
       (fetch-identity-at (:identity identity) time-str)))))

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
                        (select-identity {:identity generated-id :name name-to-select :text text-to-select})))
           :error-handler (fn [err]
                        (js/console.error "Error adding identity" err)
                        (swap! app-state assoc :notification {:message "Failed to add identity. Please try again." :type :error})
                        (js/setTimeout #(swap! app-state assoc :notification nil) 5000))})))))

(defn update-identity [identity-id name text]
  (let [{:keys [current-user]} @app-state]
    (PUT (str api-base "/api/personas/" (:id current-user) "/identities/" identity-id)
      {:params {:name name :text text}
       :format :json
       :headers (auth-headers)
       :handler (fn [_]
                  (fetch-identities (:id current-user))
                  (fetch-identity-history identity-id))
       :error-handler (fn [err]
                        (js/console.error "Error updating identity" err)
                        (swap! app-state assoc :notification {:message "Failed to save. Please try again." :type :error})
                        (js/setTimeout #(swap! app-state assoc :notification nil) 5000))})))

(defn add-relation [target-id]
  (let [{:keys [current-user selected-identity]} @app-state]
    (POST (str api-base "/api/personas/" (:id current-user) "/identities/" (:identity selected-identity) "/relations")
      {:params {:target_id target-id}
       :format :json
       :headers (auth-headers)
       :handler (fn [_]
                  (swap! app-state assoc
                         :show-add-relation-modal false
                         :relation-search-query ""
                         :relation-search-results [])
                  (fetch-relations (:identity selected-identity)))
       :error-handler #(js/console.error "Error adding relation" %)})))

(defn delete-relation [relation-id]
  (let [{:keys [current-user selected-identity]} @app-state]
    (DELETE (str api-base "/api/personas/" (:id current-user) "/relations/" relation-id)
      {:headers (auth-headers)
       :handler (fn [_]
                  (fetch-relations (:identity selected-identity)))
       :error-handler #(js/console.error "Error deleting relation" %)})))

(defn search-identities
  ([query callback] (search-identities query nil callback))
  ([query valid-at callback]
   (let [{:keys [current-user]} @app-state
         params (cond-> {:q query}
                  valid-at (assoc :valid_at valid-at))]
     (GET (str api-base "/api/personas/" (:id current-user) "/identities/search")
       {:params params
        :handler callback
        :response-format :json
        :keywords? true
        :error-handler #(js/console.error "Error searching identities" %)}))))

(defn- find-persona-by-id [persona-id]
  (first (filter #(= (:id %) (if (keyword? persona-id) (name persona-id) persona-id)) (:personas @app-state))))

(defn- enter-persona [persona]
  (swap! app-state assoc
         :current-user persona
         :identities []
         :recent-identities []
         :selected-identity nil
         :identity-history [])
  (update-url (:id persona) nil false)
  (fetch-identities (:id persona))
  (fetch-recent-identities (:id persona)))

(defn select-persona [persona]
  (swap! app-state assoc :show-login-modal false)
  (enter-persona persona))

(defn login-user [persona]
  (let [full-persona (or (find-persona-by-id (:id persona)) persona)]
    (swap! app-state assoc
           :auth-user full-persona
           :show-auth-modal false
           :show-password-modal false
           :login-password ""
           :login-error nil
           :login-persona nil)
    (enter-persona full-persona)))

(defn attempt-login []
  (let [password (:login-password @app-state)
        persona (:login-persona @app-state)]
    (POST (str api-base "/api/auth/login")
      {:params {:id (:id persona) :password password}
       :format :json
       :response-format :json
       :keywords? true
       :handler (fn [res]
                  (if (:success res)
                    (do
                      (save-auth-token (:token res))
                      (swap! app-state assoc :auth-token (:token res))
                      (login-user persona))
                    (swap! app-state assoc :login-error "Invalid password")))
       :error-handler (fn [_]
                        (swap! app-state assoc :login-error "Invalid password"))})))

(defn attempt-email-login []
  (let [input (:login-email @app-state)
        password (:login-password @app-state)
        params (if (valid-email? input)
                 {:email input :password password}
                 {:id input :password password})]
    (POST (str api-base "/api/auth/login")
      {:params params
       :format :json
       :response-format :json
       :keywords? true
       :handler (fn [res]
                  (if (:success res)
                    (do
                      (save-auth-token (:token res))
                      (swap! app-state assoc
                             :auth-token (:token res)
                             :show-auth-modal false
                             :login-email ""
                             :login-password ""
                             :login-error nil)
                      (let [persona-id (extract-persona-from-token (:token res))]
                        (login-user {:id persona-id})))
                    (swap! app-state assoc :login-error "Invalid credentials")))
       :error-handler (fn [_]
                        (swap! app-state assoc :login-error "Invalid credentials"))})))

(defn try-login [persona]
  (if (:password-required @app-state)
    (swap! app-state assoc
           :show-password-modal true
           :show-auth-modal false
           :login-password ""
           :login-error nil
           :login-persona persona)
    (do
      (save-auth-persona (:id persona))
      (login-user persona))))

(defn logout-user []
  (save-auth-token nil)
  (save-auth-persona nil)
  (swap! app-state assoc
         :auth-user nil
         :current-user nil
         :auth-token nil
         :current-tab :main
         :identities []
         :selected-identity nil
         :identity-history [])
  (.pushState js/history nil "" "/"))

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
