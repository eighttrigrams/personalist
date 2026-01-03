(ns et.pe.ui.core
  (:require [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [clojure.string :as str]
            [ajax.core :refer [GET POST PUT DELETE]]))

(defonce app-state (r/atom {:personas []
                            :current-user nil
                            :auth-user nil
                            :current-tab :main
                            :show-login-modal false
                            :show-auth-modal false
                            :identities []
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
                            :auth-token nil}))

(def api-base "")

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

(defn update-persona-email [persona-name new-email on-success on-error]
  (PUT (str api-base "/api/personas/" persona-name)
    {:params {:email new-email}
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

(declare select-identity)

(defn add-identity []
  (let [{:keys [current-user new-identity-name new-identity-text]} @app-state]
    (when (and current-user (seq new-identity-name) (seq new-identity-text))
      (let [name-to-select new-identity-name
            text-to-select new-identity-text]
        (POST (str api-base "/api/personas/" (:name current-user) "/identities")
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
                        (fetch-identities (:name current-user))
                        (select-identity {:identity generated-id :name name-to-select :text text-to-select})))
           :error-handler #(js/console.error "Error adding identity" %)})))))

(declare fetch-identity-history)

(defn update-identity [identity-id name text]
  (let [{:keys [current-user]} @app-state]
    (PUT (str api-base "/api/personas/" (:name current-user) "/identities/" identity-id)
      {:params {:name name :text text}
       :format :json
       :headers (auth-headers)
       :handler (fn [_]
                  (fetch-identities (:name current-user))
                  (fetch-identity-history identity-id))
       :error-handler #(js/console.error "Error updating identity" %)})))

(defn fetch-identity-history [identity-id]
  (let [{:keys [current-user]} @app-state]
    (GET (str api-base "/api/personas/" (:name current-user) "/identities/" identity-id "/history")
      {:handler (fn [res]
                  (swap! app-state assoc :identity-history res)
                  (when (seq res)
                    (swap! app-state assoc :slider-value (dec (count res)))))
       :response-format :json
       :keywords? true
       :error-handler #(js/console.error "Error fetching history" %)})))

(defn fetch-identity-at [identity-id time-str]
  (let [{:keys [current-user]} @app-state]
    (GET (str api-base "/api/personas/" (:name current-user) "/identities/" identity-id "/at")
      {:params {:time time-str}
       :handler (fn [res]
                  (swap! app-state assoc :editing-name (:name res) :editing-text (:text res)))
       :response-format :json
       :keywords? true
       :error-handler #(js/console.error "Error fetching identity at time" %)})))

(declare fetch-relations)

(defn fetch-relations
  ([identity-id] (fetch-relations identity-id nil))
  ([identity-id time-str]
   (let [{:keys [current-user]} @app-state
         url (str api-base "/api/personas/" (:name current-user) "/identities/" identity-id "/relations")]
     (GET (if time-str (str url "?time=" (js/encodeURIComponent time-str)) url)
       {:handler (fn [res]
                   (swap! app-state assoc :relations res))
        :response-format :json
        :keywords? true
        :error-handler #(js/console.error "Error fetching relations" %)}))))

(defn add-relation [source-id]
  (let [{:keys [current-user selected-identity]} @app-state]
    (POST (str api-base "/api/personas/" (:name current-user) "/identities/" (:identity selected-identity) "/relations")
      {:params {:source_id source-id}
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
    (DELETE (str api-base "/api/personas/" (:name current-user) "/relations/" relation-id)
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
     (GET (str api-base "/api/personas/" (:name current-user) "/identities/search")
       {:params params
        :handler callback
        :response-format :json
        :keywords? true
        :error-handler #(js/console.error "Error searching identities" %)}))))

(defn select-persona [persona]
  (swap! app-state assoc
         :current-user persona
         :show-login-modal false
         :identities []
         :selected-identity nil
         :identity-history [])
  (fetch-identities (:name persona)))

(defn select-identity [identity]
  (swap! app-state assoc
         :selected-identity identity
         :editing-name (:name identity)
         :editing-text (:text identity)
         :relations [])
  (fetch-identity-history (:identity identity))
  (fetch-relations (:identity identity)))

(defn login-user [persona]
  (swap! app-state assoc
         :auth-user persona
         :current-user persona
         :show-auth-modal false
         :show-password-modal false
         :login-password ""
         :login-error nil
         :login-persona nil
         :identities []
         :selected-identity nil
         :identity-history [])
  (fetch-identities (:name persona)))

(defn attempt-login []
  (let [password (:login-password @app-state)
        persona (:login-persona @app-state)]
    (POST (str api-base "/api/auth/login")
      {:params {:name (:name persona) :password password}
       :format :json
       :response-format :json
       :keywords? true
       :handler (fn [res]
                  (if (:success res)
                    (do
                      (swap! app-state assoc :auth-token (:token res))
                      (login-user persona))
                    (swap! app-state assoc :login-error "Invalid password")))
       :error-handler (fn [_]
                        (swap! app-state assoc :login-error "Invalid password"))})))

(defn attempt-email-login []
  (let [email (:login-email @app-state)
        password (:login-password @app-state)]
    (POST (str api-base "/api/auth/login")
      {:params {:email email :password password}
       :format :json
       :response-format :json
       :keywords? true
       :handler (fn [res]
                  (if (:success res)
                    (do
                      (swap! app-state assoc
                             :auth-token (:token res)
                             :show-auth-modal false
                             :login-email ""
                             :login-password ""
                             :login-error nil)
                      (let [persona-name (-> res :token
                                             (str/split #"\.")
                                             second
                                             js/atob
                                             js/JSON.parse
                                             (js->clj :keywordize-keys true)
                                             :persona)]
                        (login-user {:name persona-name})))
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
    (login-user persona)))

(defn logout-user []
  (swap! app-state assoc
         :auth-user nil
         :current-user nil
         :auth-token nil
         :current-tab :main
         :identities []
         :selected-identity nil
         :identity-history []))

(defn header []
  (let [{:keys [current-user auth-user current-tab]} @app-state
        logged-in? (some? auth-user)
        is-admin? (= (:name auth-user) "admin")]
    [:div {:style {:display "flex"
                   :justify-content "space-between"
                   :align-items "center"
                   :padding "1rem"
                   :background "#333"
                   :color "white"}}
     [:div {:style {:display "flex" :align-items "center" :gap "2rem"}}
      [:div {:style {:display "flex" :align-items "center" :gap "0.5rem"}}
       [:h1 {:style {:margin 0 :cursor "pointer"}
             :on-click #(swap! app-state assoc :current-tab :main)}
        "Personalist"]
       [:span {:on-click #(swap! app-state assoc :show-beta-modal true)
               :style {:background "linear-gradient(135deg, #ff6b6b, #feca57, #48dbfb)"
                       :color "#fff"
                       :font-weight "bold"
                       :font-size "0.7rem"
                       :padding "0.2rem 0.5rem"
                       :border-radius "4px"
                       :cursor "pointer"
                       :text-transform "uppercase"
                       :box-shadow "0 2px 8px rgba(255,107,107,0.4)"
                       :animation "pulse 2s infinite"}}
        "Beta"]]
      [:div {:style {:display "flex" :gap "0.5rem"}}
       (when is-admin?
         [:button {:on-click #(swap! app-state assoc :current-tab :settings)
                   :style {:padding "0.5rem 1rem"
                           :cursor "pointer"
                           :background (if (= current-tab :settings) "#555" "#333")
                           :color "white"
                           :border "1px solid #555"
                           :border-radius "4px"}}
          "Users"])
       (when (and logged-in? (not is-admin?))
         [:<>
          [:button {:on-click #(swap! app-state assoc :show-add-identity-modal true)
                    :style {:padding "0.5rem 1rem"
                            :cursor "pointer"
                            :background "#333"
                            :color "white"
                            :border "1px solid #555"
                            :border-radius "4px"
                            :font-size "1.2rem"
                            :font-weight "bold"}}
           "+"]
          [:button {:on-click #(swap! app-state assoc :show-search-modal true)
                    :style {:padding "0.5rem 1rem"
                            :cursor "pointer"
                            :background "#333"
                            :color "white"
                            :border "1px solid #555"
                            :border-radius "4px"
                            :font-size "1.1rem"}}
           "\uD83D\uDD0D"]])
       (when (and (not logged-in?) current-user)
         [:button {:on-click #(swap! app-state assoc :show-search-modal true)
                   :style {:padding "0.5rem 1rem"
                           :cursor "pointer"
                           :background "#333"
                           :color "white"
                           :border "1px solid #555"
                           :border-radius "4px"
                           :font-size "1.1rem"}}
          "\uD83D\uDD0D"])]]
     [:div {:style {:display "flex" :align-items "center" :gap "1rem"}}
      (when (and (not logged-in?) current-user)
        [:<>
         [:span (str "Persona: " (:name current-user))]
         [:button {:on-click #(swap! app-state assoc :current-user nil :identities [] :selected-identity nil)
                   :style {:padding "0.5rem 1rem" :cursor "pointer"}}
          "Change"]])
      (when logged-in?
        [:<>
         [:span (str "Logged in: " (:name auth-user))]
         [:button {:on-click logout-user
                   :style {:padding "0.5rem 1rem" :cursor "pointer"}}
          "Logout"]])
      (when (and (not logged-in?) (not current-user))
        [:button {:on-click #(swap! app-state assoc :show-login-modal true)
                  :style {:padding "0.5rem 1rem"
                          :cursor "pointer"
                          :background "#4CAF50"
                          :color "white"
                          :border "none"
                          :border-radius "4px"}}
         "Explore"])
      (when (not logged-in?)
        [:button {:on-click #(swap! app-state assoc :show-auth-modal true)
                  :style {:padding "0.5rem 1rem"
                          :cursor "pointer"
                          :background "#2196F3"
                          :color "white"
                          :border "none"
                          :border-radius "4px"}}
         "Login"])]]))

(defn login-modal []
  (let [{:keys [personas show-login-modal]} @app-state]
    (when show-login-modal
      [:div {:style {:position "fixed"
                     :top 0
                     :left 0
                     :right 0
                     :bottom 0
                     :background "rgba(0,0,0,0.5)"
                     :display "flex"
                     :align-items "center"
                     :justify-content "center"
                     :z-index 1000}
             :on-click #(swap! app-state assoc :show-login-modal false)}
       [:div {:style {:background "white"
                      :padding "2rem"
                      :border-radius "8px"
                      :min-width "300px"
                      :max-width "400px"}
              :on-click #(.stopPropagation %)}
        [:h2 {:style {:margin-top 0}} "Select Persona"]
        [:p {:style {:color "#666"}} "Choose a persona to view:"]
        (if (seq personas)
          [:ul {:style {:list-style "none" :padding 0 :margin 0}}
           (for [p personas]
             ^{:key (:name p)}
             (when (not= (:name p) "admin")
               [:li {:on-click #(select-persona p)
                     :style {:padding "0.75rem"
                             :cursor "pointer"
                             :background "#f5f5f5"
                             :border-radius "4px"
                             :margin-bottom "0.5rem"
                             :transition "background 0.2s"}
                     :on-mouse-over #(set! (.-background (.-style (.-target %))) "#e0e0e0")
                     :on-mouse-out #(set! (.-background (.-style (.-target %))) "#f5f5f5")}
                [:strong (:name p)]]))]
          [:p {:style {:color "#666" :font-style "italic"}}
           "No personas yet. Add one in Users tab."])
        [:button {:on-click #(swap! app-state assoc :show-login-modal false)
                  :style {:margin-top "1rem"
                          :padding "0.5rem 1rem"
                          :cursor "pointer"}}
         "Cancel"]]])))

(defn auth-modal []
  (let [{:keys [personas show-auth-modal password-required login-email login-password login-error]} @app-state]
    (when show-auth-modal
      [:div {:style {:position "fixed"
                     :top 0
                     :left 0
                     :right 0
                     :bottom 0
                     :background "rgba(0,0,0,0.5)"
                     :display "flex"
                     :align-items "center"
                     :justify-content "center"
                     :z-index 1000}
             :on-click #(swap! app-state assoc :show-auth-modal false :login-email "" :login-password "" :login-error nil)}
       [:div {:style {:background "white"
                      :padding "2rem"
                      :border-radius "8px"
                      :min-width "300px"
                      :max-width "400px"}
              :on-click #(.stopPropagation %)}
        [:h2 {:style {:margin-top 0}} "Login"]
        (if password-required
          [:<>
           [:p {:style {:color "#666"}} "Enter your credentials:"]
           [:input {:type "email"
                    :value login-email
                    :placeholder "Email"
                    :on-change #(swap! app-state assoc :login-email (.. % -target -value))
                    :on-key-down #(when (= (.-key %) "Enter") (attempt-email-login))
                    :style {:width "100%"
                            :padding "0.75rem"
                            :margin-bottom "0.5rem"
                            :border "1px solid #ccc"
                            :border-radius "4px"
                            :box-sizing "border-box"}}]
           [:input {:type "password"
                    :value login-password
                    :placeholder "Password"
                    :on-change #(swap! app-state assoc :login-password (.. % -target -value))
                    :on-key-down #(when (= (.-key %) "Enter") (attempt-email-login))
                    :style {:width "100%"
                            :padding "0.75rem"
                            :margin-bottom "1rem"
                            :border "1px solid #ccc"
                            :border-radius "4px"
                            :box-sizing "border-box"}}]
           (when login-error
             [:p {:style {:color "red" :margin "0 0 1rem 0"}} login-error])
           [:div {:style {:display "flex" :gap "0.5rem"}}
            [:button {:on-click attempt-email-login
                      :style {:padding "0.5rem 1rem"
                              :cursor "pointer"
                              :background "#4CAF50"
                              :color "white"
                              :border "none"
                              :border-radius "4px"}}
             "Login"]
            [:button {:on-click #(swap! app-state assoc :show-auth-modal false :login-email "" :login-password "" :login-error nil)
                      :style {:padding "0.5rem 1rem"
                              :cursor "pointer"}}
             "Cancel"]]]
          [:<>
           [:p {:style {:color "#666"}} "Select your persona to login:"]
           (if (seq personas)
             [:ul {:style {:list-style "none" :padding 0 :margin 0}}
              (for [p personas]
                ^{:key (:name p)}
                [:li {:on-click #(try-login p)
                      :style {:padding "0.75rem"
                              :cursor "pointer"
                              :background "#f5f5f5"
                              :border-radius "4px"
                              :margin-bottom "0.5rem"
                              :transition "background 0.2s"}
                      :on-mouse-over #(set! (.-background (.-style (.-target %))) "#e0e0e0")
                      :on-mouse-out #(set! (.-background (.-style (.-target %))) "#f5f5f5")}
                 [:strong (:name p)]])]
             [:p {:style {:color "#666" :font-style "italic"}}
              "No personas yet."])
           [:button {:on-click #(swap! app-state assoc :show-auth-modal false)
                     :style {:margin-top "1rem"
                             :padding "0.5rem 1rem"
                             :cursor "pointer"}}
            "Cancel"]])]])))

(defn password-modal []
  (let [{:keys [show-password-modal login-password login-error login-persona]} @app-state]
    (when show-password-modal
      [:div {:style {:position "fixed"
                     :top 0
                     :left 0
                     :right 0
                     :bottom 0
                     :background "rgba(0,0,0,0.5)"
                     :display "flex"
                     :align-items "center"
                     :justify-content "center"
                     :z-index 1000}
             :on-click #(swap! app-state assoc :show-password-modal false)}
       [:div {:style {:background "white"
                      :padding "2rem"
                      :border-radius "8px"
                      :min-width "300px"
                      :max-width "400px"}
              :on-click #(.stopPropagation %)}
        [:h2 {:style {:margin-top 0}} (str "Login as " (:name login-persona))]
        [:p {:style {:color "#666"}} "Enter your password:"]
        [:input {:type "password"
                 :value login-password
                 :placeholder "Password"
                 :on-change #(swap! app-state assoc :login-password (.. % -target -value))
                 :on-key-down #(when (= (.-key %) "Enter") (attempt-login))
                 :style {:width "100%"
                         :padding "0.75rem"
                         :margin-bottom "1rem"
                         :border "1px solid #ccc"
                         :border-radius "4px"
                         :box-sizing "border-box"}}]
        (when login-error
          [:p {:style {:color "red" :margin "0 0 1rem 0"}} login-error])
        [:div {:style {:display "flex" :gap "0.5rem"}}
         [:button {:on-click attempt-login
                   :style {:padding "0.5rem 1rem"
                           :cursor "pointer"
                           :background "#4CAF50"
                           :color "white"
                           :border "none"
                           :border-radius "4px"}}
          "Login"]
         [:button {:on-click #(swap! app-state assoc :show-password-modal false)
                   :style {:padding "0.5rem 1rem"
                           :cursor "pointer"}}
          "Cancel"]]]])))

(defn- date-to-instant [date-str]
  (when (seq date-str)
    (str date-str "T23:59:59Z")))

(defn- do-search [query valid-at]
  (when (>= (count query) 1)
    (search-identities query
                       (date-to-instant valid-at)
                       #(swap! app-state assoc :nav-search-results %))))

(defn search-modal []
  (let [{:keys [show-search-modal nav-search-query nav-search-results search-valid-at identities]} @app-state]
    (when show-search-modal
      [:div {:style {:position "fixed"
                     :top 0
                     :left 0
                     :right 0
                     :bottom 0
                     :background "rgba(0,0,0,0.5)"
                     :display "flex"
                     :align-items "center"
                     :justify-content "center"
                     :z-index 1000}
             :on-click #(swap! app-state assoc :show-search-modal false :nav-search-query "" :nav-search-results [] :search-valid-at nil)}
       [:div {:style {:background "white"
                      :padding "2rem"
                      :border-radius "8px"
                      :min-width "400px"
                      :max-width "500px"
                      :max-height "80vh"
                      :overflow-y "auto"}
              :on-click #(.stopPropagation %)}
        [:h2 {:style {:margin-top 0}} "Search Identities"]
        [:div {:style {:display "flex" :gap "0.5rem" :margin-bottom "1rem"}}
         [:input {:type "text"
                  :placeholder "Search by name..."
                  :value nav-search-query
                  :auto-focus true
                  :on-change (fn [e]
                               (let [q (-> e .-target .-value)]
                                 (swap! app-state assoc :nav-search-query q)
                                 (do-search q search-valid-at)))
                  :style {:flex 1
                          :padding "0.75rem"
                          :font-size "1rem"
                          :border "1px solid #ccc"
                          :border-radius "4px"}}]
         [:input {:type "date"
                  :value (or search-valid-at "")
                  :on-change (fn [e]
                               (let [d (-> e .-target .-value)]
                                 (swap! app-state assoc :search-valid-at (when (seq d) d))
                                 (do-search nav-search-query d)))
                  :style {:padding "0.75rem"
                          :font-size "1rem"
                          :border "1px solid #ccc"
                          :border-radius "4px"
                          :width "150px"}}]]
        (when search-valid-at
          [:div {:style {:margin-bottom "1rem" :padding "0.5rem" :background "#e3f2fd" :border-radius "4px" :font-size "0.9rem"}}
           [:span "Searching identities as of: " search-valid-at]])
        (if (seq nav-search-results)
          [:ul {:style {:list-style "none" :padding 0 :margin 0}}
           (for [result nav-search-results]
             ^{:key (:identity result)}
             [:li {:on-click (fn []
                               (let [identity-data (first (filter #(= (:identity %) (:identity result)) identities))]
                                 (when identity-data
                                   (select-identity identity-data))
                                 (swap! app-state assoc :show-search-modal false :nav-search-query "" :nav-search-results [] :search-valid-at nil)))
                   :style {:padding "0.75rem"
                           :cursor "pointer"
                           :background "#f5f5f5"
                           :border-radius "4px"
                           :margin-bottom "0.5rem"
                           :transition "background 0.2s"}
                   :on-mouse-over #(set! (.-background (.-style (.-target %))) "#e0e0e0")
                   :on-mouse-out #(set! (.-background (.-style (.-target %))) "#f5f5f5")}
              [:span (:name result)]])]
          (when (seq nav-search-query)
            [:p {:style {:color "#666" :font-style "italic"}} "No results found"]))
        [:button {:on-click #(swap! app-state assoc :show-search-modal false :nav-search-query "" :nav-search-results [] :search-valid-at nil)
                  :style {:margin-top "1rem"
                          :padding "0.5rem 1rem"
                          :cursor "pointer"}}
         "Cancel"]]])))

(defn add-relation-modal []
  (let [{:keys [show-add-relation-modal relation-search-query relation-search-results selected-identity]} @app-state]
    (when show-add-relation-modal
      [:div {:style {:position "fixed"
                     :top 0
                     :left 0
                     :right 0
                     :bottom 0
                     :background "rgba(0,0,0,0.5)"
                     :display "flex"
                     :align-items "center"
                     :justify-content "center"
                     :z-index 1000}
             :on-click #(swap! app-state assoc :show-add-relation-modal false :relation-search-query "" :relation-search-results [])}
       [:div {:style {:background "white"
                      :padding "2rem"
                      :border-radius "8px"
                      :min-width "400px"
                      :max-width "500px"
                      :max-height "80vh"
                      :overflow-y "auto"}
              :on-click #(.stopPropagation %)}
        [:h2 {:style {:margin-top 0}} "Add Relation"]
        [:p {:style {:color "#666" :margin-bottom "1rem"}}
         (str "Link an identity to: " (:name selected-identity))]
        [:input {:type "text"
                 :placeholder "Search by name..."
                 :value relation-search-query
                 :auto-focus true
                 :on-change (fn [e]
                              (let [q (-> e .-target .-value)]
                                (swap! app-state assoc :relation-search-query q)
                                (when (>= (count q) 1)
                                  (search-identities q #(swap! app-state assoc :relation-search-results %)))))
                 :style {:width "100%"
                         :padding "0.75rem"
                         :font-size "1rem"
                         :border "1px solid #ccc"
                         :border-radius "4px"
                         :margin-bottom "1rem"}}]
        (when (seq relation-search-results)
          [:ul {:style {:list-style "none" :padding 0 :margin 0}}
           (for [result relation-search-results]
             ^{:key (:identity result)}
             (when (not= (:identity result) (:identity selected-identity))
               [:li {:on-click #(add-relation (:identity result))
                     :style {:padding "0.75rem"
                             :cursor "pointer"
                             :background "#f5f5f5"
                             :border-radius "4px"
                             :margin-bottom "0.5rem"
                             :transition "background 0.2s"}
                     :on-mouse-over #(set! (.-background (.-style (.-target %))) "#e0e0e0")
                     :on-mouse-out #(set! (.-background (.-style (.-target %))) "#f5f5f5")}
                [:span (:name result)]]))])
        [:button {:on-click #(swap! app-state assoc :show-add-relation-modal false :relation-search-query "" :relation-search-results [])
                  :style {:margin-top "1rem"
                          :padding "0.5rem 1rem"
                          :cursor "pointer"}}
         "Cancel"]]])))

(defn add-identity-modal []
  (let [{:keys [show-add-identity-modal new-identity-name new-identity-text]} @app-state]
    (when show-add-identity-modal
      [:div {:style {:position "fixed"
                     :top 0
                     :left 0
                     :right 0
                     :bottom 0
                     :background "rgba(0,0,0,0.5)"
                     :display "flex"
                     :align-items "center"
                     :justify-content "center"
                     :z-index 1000}
             :on-click #(swap! app-state assoc :show-add-identity-modal false)}
       [:div {:style {:background "white"
                      :padding "2rem"
                      :border-radius "8px"
                      :min-width "400px"
                      :max-width "500px"}
              :on-click #(.stopPropagation %)}
        [:h2 {:style {:margin-top 0}} "Add Identity"]
        [:div {:style {:margin-bottom "1rem"}}
         [:label {:style {:display "block" :margin-bottom "0.5rem" :font-weight "bold"}} "Name"]
         [:input {:type "text"
                  :placeholder "Display name for this identity..."
                  :value new-identity-name
                  :auto-focus true
                  :on-change #(swap! app-state assoc :new-identity-name (-> % .-target .-value))
                  :style {:width "100%"
                          :padding "0.75rem"
                          :font-size "1rem"
                          :border "1px solid #ccc"
                          :border-radius "4px"}}]]
        [:div {:style {:margin-bottom "1rem"}}
         [:label {:style {:display "block" :margin-bottom "0.5rem" :font-weight "bold"}} "Text"]
         [:textarea {:placeholder "Describe this identity..."
                     :value new-identity-text
                     :on-change #(swap! app-state assoc :new-identity-text (-> % .-target .-value))
                     :style {:width "100%"
                             :height "100px"
                             :padding "0.75rem"
                             :font-size "1rem"
                             :border "1px solid #ccc"
                             :border-radius "4px"
                             :resize "vertical"}}]]
        [:div {:style {:display "flex" :gap "1rem" :justify-content "flex-end"}}
         [:button {:on-click #(swap! app-state assoc :show-add-identity-modal false :new-identity-name "" :new-identity-text "")
                   :style {:padding "0.5rem 1rem"
                           :cursor "pointer"}}
          "Cancel"]
         [:button {:on-click add-identity
                   :disabled (or (empty? new-identity-name) (empty? new-identity-text))
                   :style {:padding "0.5rem 1rem"
                           :cursor "pointer"
                           :background (if (or (empty? new-identity-name) (empty? new-identity-text)) "#ccc" "#4CAF50")
                           :color "white"
                           :border "none"
                           :border-radius "4px"}}
          "Create"]]]])))

(defn beta-modal []
  (let [{:keys [show-beta-modal]} @app-state]
    (when show-beta-modal
      [:div {:style {:position "fixed"
                     :top 0
                     :left 0
                     :right 0
                     :bottom 0
                     :background "rgba(0,0,0,0.5)"
                     :display "flex"
                     :align-items "center"
                     :justify-content "center"
                     :z-index 1000}
             :on-click #(swap! app-state assoc :show-beta-modal false)}
       [:div {:style {:background "white"
                      :padding "2rem"
                      :border-radius "8px"
                      :min-width "400px"
                      :max-width "500px"
                      :text-align "center"}
              :on-click #(.stopPropagation %)}
        [:div {:style {:font-size "3rem" :margin-bottom "1rem"}} "\uD83D\uDE80"]
        [:h2 {:style {:margin-top 0 :margin-bottom "1rem"}} "Personalist Beta"]
        [:p {:style {:color "#666" :margin-bottom "1.5rem"}}
         "Welcome to the beta version of Personalist! We're building an integrated universe of personal encyclopedias."]
        [:a {:href "https://eighttrigrams.substack.com/p/personalist"
             :target "_blank"
             :style {:display "inline-block"
                     :padding "0.75rem 1.5rem"
                     :background "linear-gradient(135deg, #ff6b6b, #feca57)"
                     :color "white"
                     :text-decoration "none"
                     :border-radius "4px"
                     :font-weight "bold"
                     :margin-bottom "0.5rem"}}
         "Read the Whitepaper"]
        [:br]
        [:a {:href "https://github.com/eighttrigrams/personalist/blob/main/DEMO.md"
             :target "_blank"
             :style {:display "inline-block"
                     :padding "0.5rem 1rem"
                     :color "#666"
                     :text-decoration "none"
                     :font-size "0.9rem"}}
         "View Demo Guide"]
        [:br]
        [:p {:style {:color "#999" :font-size "0.75rem" :margin-top "1rem" :margin-bottom "0" :max-width "280px" :margin-left "auto" :margin-right "auto" :line-height "1.4"}}
         "This runs on a small cloud instance that sleeps when idle. If slow at first, it's just waking up!"]
        [:button {:on-click #(swap! app-state assoc :show-beta-modal false)
                  :style {:margin-top "1rem"
                          :padding "0.5rem 1rem"
                          :cursor "pointer"
                          :background "#eee"
                          :border "none"
                          :border-radius "4px"}}
         "Close"]]])))

(defn time-slider []
  (let [{:keys [identity-history slider-value selected-identity]} @app-state]
    (when (and selected-identity (seq identity-history))
      (let [history-count (count identity-history)
            current-entry (get identity-history slider-value)]
        [:div {:style {:margin-bottom "1rem" :padding "1rem" :background "#f5f5f5" :border-radius "4px"}}
         [:div {:style {:display "flex" :justify-content "space-between" :margin-bottom "0.5rem"}}
          [:span "Time Travel"]
          [:span (str "Version " (inc slider-value) " of " history-count)]]
         [:input {:type "range"
                  :min 0
                  :max (dec history-count)
                  :value slider-value
                  :on-change #(swap! app-state assoc :slider-value (js/parseInt (-> % .-target .-value)))
                  :on-mouse-up (fn [_]
                                 (let [entry (get identity-history (:slider-value @app-state))]
                                   (when entry
                                     (fetch-identity-at (:identity selected-identity) (:valid-from entry))
                                     (fetch-relations (:identity selected-identity) (:valid-from entry)))))
                  :style {:width "100%"}}]
         (when current-entry
           [:div {:style {:font-size "0.8rem" :color "#666" :margin-top "0.5rem"}}
            "Valid from: " (:valid-from current-entry)])]))))

(defn relations-list []
  (let [{:keys [relations identities auth-user]} @app-state
        can-edit? (some? auth-user)]
    [:div {:style {:margin-top "1.5rem" :padding-top "1rem" :border-top "1px solid #eee"}}
     [:h4 {:style {:margin 0 :margin-bottom "1rem"}} "Related Identities"]
     (if (seq relations)
       [:ul {:style {:list-style "none" :padding 0 :margin 0}}
        (for [rel relations]
          ^{:key (:id rel)}
          (let [source-identity (first (filter #(= (:identity %) (:source rel)) identities))]
            [:li {:style {:padding "0.5rem"
                          :background "#f5f5f5"
                          :border-radius "4px"
                          :margin-bottom "0.5rem"
                          :display "flex"
                          :justify-content "space-between"
                          :align-items "center"}}
             [:span {:on-click (fn []
                                 (when source-identity
                                   (select-identity source-identity)))
                     :style {:cursor "pointer"}}
              [:span (or (:name source-identity) (:source rel))]]
             (when can-edit?
               [:button {:on-click #(delete-relation (:id rel))
                         :style {:padding "0.25rem 0.5rem"
                                 :cursor "pointer"
                                 :background "#ff5252"
                                 :color "white"
                                 :border "none"
                                 :border-radius "4px"
                                 :font-size "0.8rem"}}
                "X"])]))]
       [:p {:style {:color "#666" :font-style "italic" :margin 0}} "No linked identities yet"])]))

(defn identity-editor []
  (let [{:keys [selected-identity editing-name editing-text auth-user]} @app-state
        can-edit? (some? auth-user)]
    (when selected-identity
      [:div {:style {:padding "2rem"
                     :max-width "800px"
                     :margin "0 auto"}}
       [:div {:style {:display "flex"
                      :justify-content "space-between"
                      :align-items "center"
                      :margin-bottom "1rem"}}
        [:span {:style {:color "#666" :font-size "0.9rem"}} (str "Identity: " (:identity selected-identity))]
        (when can-edit?
          [:button {:on-click #(update-identity (:identity selected-identity) editing-name editing-text)
                    :style {:padding "0.5rem 1rem"
                            :cursor "pointer"
                            :background "#4CAF50"
                            :color "white"
                            :border "none"
                            :border-radius "4px"}}
           "Save"])]
       [time-slider]
       (if can-edit?
         [:<>
          [:input {:type "text"
                   :value editing-name
                   :on-change #(swap! app-state assoc :editing-name (-> % .-target .-value))
                   :placeholder "Name"
                   :style {:width "100%"
                           :padding "0.75rem"
                           :font-size "1.2rem"
                           :font-weight "bold"
                           :border "1px solid #ccc"
                           :border-radius "4px"
                           :margin-bottom "0.5rem"}}]
          [:textarea {:value editing-text
                      :on-change #(swap! app-state assoc :editing-text (-> % .-target .-value))
                      :style {:width "100%"
                              :height "200px"
                              :padding "0.75rem"
                              :font-size "1rem"
                              :border "1px solid #ccc"
                              :border-radius "4px"
                              :resize "vertical"}}]
          [:div {:style {:display "flex" :gap "0.5rem" :margin-top "1rem"}}
           [:button {:on-click #(swap! app-state assoc :show-add-relation-modal true)
                     :style {:padding "0.5rem 1rem"
                             :cursor "pointer"
                             :background "#2196F3"
                             :color "white"
                             :border "none"
                             :border-radius "4px"}}
            "\u221E"]]]
         [:<>
          [:div {:style {:width "100%"
                         :padding "0.75rem"
                         :font-size "1.2rem"
                         :font-weight "bold"
                         :border "1px solid #ccc"
                         :border-radius "4px"
                         :background "#fafafa"
                         :margin-bottom "0.5rem"}}
           editing-name]
          [:div {:style {:width "100%"
                         :min-height "200px"
                         :padding "0.75rem"
                         :font-size "1rem"
                         :border "1px solid #ccc"
                         :border-radius "4px"
                         :background "#fafafa"
                         :white-space "pre-wrap"}}
           editing-text]])
       [relations-list]])))

(defn main-tab []
  (let [{:keys [current-user selected-identity]} @app-state]
    (cond
      (not current-user)
      [:div {:style {:display "flex"
                     :justify-content "center"
                     :align-items "center"
                     :min-height "calc(100vh - 60px)"
                     :color "#666"}}
       [:div {:style {:text-align "center"}}
        [:p {:style {:font-size "1.2rem"}} "Select a persona to view their world model"]
        [:button {:on-click #(swap! app-state assoc :show-login-modal true)
                  :style {:padding "0.75rem 1.5rem"
                          :cursor "pointer"
                          :background "#4CAF50"
                          :color "white"
                          :border "none"
                          :border-radius "4px"
                          :font-size "1rem"}}
         "Select Persona"]]]

      (not selected-identity)
      [:div {:style {:display "flex"
                     :justify-content "center"
                     :align-items "center"
                     :min-height "calc(100vh - 60px)"
                     :color "#666"}}
       [:div {:style {:text-align "center"}}
        [:p {:style {:font-size "1.2rem" :margin-bottom "1rem"}} "No identity selected"]
        [:p {:style {:color "#999"}} "Use the search button to browse identities"]]]

      :else
      [:div {:style {:min-height "calc(100vh - 60px)"}}
       [identity-editor]])))

(defn- persona-form []
  (let [name-ref (atom nil)
        email-ref (atom nil)
        password-ref (atom nil)
        error (r/atom nil)]
    (fn []
      (let [personas (:personas @app-state)
            existing-emails (set (map :email personas))]
        [:div {:style {:display "flex" :flex-direction "column" :gap "0.5rem" :max-width "300px"}}
         [:input {:type "text"
                  :placeholder "Name"
                  :ref #(reset! name-ref %)
                  :style {:padding "0.5rem"}}]
         [:input {:type "email"
                  :placeholder "Email"
                  :ref #(reset! email-ref %)
                  :style {:padding "0.5rem"}}]
         [:input {:type "password"
                  :placeholder "Password"
                  :ref #(reset! password-ref %)
                  :style {:padding "0.5rem"}}]
         (when @error
           [:p {:style {:color "red" :margin "0" :font-size "0.85rem"}} @error])
         [:button {:on-click (fn []
                               (let [name-val (when @name-ref (.-value @name-ref))
                                     email-val (when @email-ref (.-value @email-ref))
                                     password-val (when @password-ref (.-value @password-ref))]
                                 (reset! error nil)
                                 (cond
                                   (not (seq name-val))
                                   (reset! error "Name is required")

                                   (not (seq email-val))
                                   (reset! error "Email is required")

                                   (not (valid-email? email-val))
                                   (reset! error "Invalid email format")

                                   (contains? existing-emails email-val)
                                   (reset! error "Email already exists")

                                   :else
                                   (POST (str api-base "/api/personas")
                                     {:params {:name name-val :email email-val :password password-val}
                                      :format :json
                                      :handler (fn [_]
                                                 (when @name-ref (set! (.-value @name-ref) ""))
                                                 (when @email-ref (set! (.-value @email-ref) ""))
                                                 (when @password-ref (set! (.-value @password-ref) ""))
                                                 (fetch-personas))
                                      :error-handler #(js/console.error "Error adding persona" %)}))))
                   :style {:padding "0.5rem 1rem" :cursor "pointer" :margin-top "0.5rem"}}
          "Add Persona"]]))))

(defn- persona-row [p]
  (let [editing? (r/atom false)
        edit-email (r/atom (:email p))
        error (r/atom nil)]
    (fn [p]
      (let [personas (:personas @app-state)
            other-emails (set (map :email (filter #(not= (:name %) (:name p)) personas)))]
        [:li {:style {:padding "0.5rem" :background "#f5f5f5" :margin-bottom "0.25rem" :border-radius "4px"}}
         [:div {:style {:display "flex" :align-items "center" :gap "0.5rem"}}
          [:strong {:style {:min-width "100px"}} (:name p)]
          (if @editing?
            [:<>
             [:input {:type "email"
                      :value @edit-email
                      :on-change #(do (reset! edit-email (.. % -target -value))
                                      (reset! error nil))
                      :style {:padding "0.25rem" :flex 1}}]
             [:button {:on-click (fn []
                                   (let [new-email @edit-email]
                                     (cond
                                       (not (valid-email? new-email))
                                       (reset! error "Invalid email format")

                                       (contains? other-emails new-email)
                                       (reset! error "Email already exists")

                                       :else
                                       (update-persona-email
                                        (:name p)
                                        new-email
                                        (fn []
                                          (reset! editing? false)
                                          (reset! error nil)
                                          (fetch-personas))
                                        (fn [err]
                                          (reset! error err))))))
                       :style {:padding "0.25rem 0.5rem" :cursor "pointer" :background "#4CAF50" :color "white" :border "none" :border-radius "4px"}}
              "Save"]
             [:button {:on-click #(do (reset! editing? false)
                                      (reset! edit-email (:email p))
                                      (reset! error nil))
                       :style {:padding "0.25rem 0.5rem" :cursor "pointer"}}
              "Cancel"]]
            [:<>
             [:span {:style {:flex 1}} (:email p)]
             (when (not= (:name p) "admin")
               [:button {:on-click #(reset! editing? true)
                         :style {:padding "0.25rem 0.5rem" :cursor "pointer"}}
                "Edit"])])]
         (when @error
           [:div {:style {:color "red" :font-size "0.85rem" :margin-top "0.25rem"}} @error])]))))

(defn settings-tab []
  (let [personas (:personas @app-state)]
    [:div {:style {:padding "2rem" :max-width "600px"}}
     [:h2 "Users"]
     [:div {:style {:margin-bottom "2rem"}}
      [:h3 "Add New Persona"]
      [persona-form]]
     [:div
      [:h3 "Existing Personas"]
      (if (seq personas)
        [:ul {:style {:list-style "none" :padding 0}}
         (for [p personas]
           ^{:key (:name p)}
           [persona-row p])]
        [:p {:style {:color "#666" :font-style "italic"}} "No personas yet."])]]))

(defn app []
  (let [{:keys [current-tab]} @app-state]
    [:div {:style {:font-family "Arial, sans-serif"}}
     [header]
     [login-modal]
     [auth-modal]
     [password-modal]
     [search-modal]
     [add-relation-modal]
     [add-identity-modal]
     [beta-modal]
     (case current-tab
       :settings [settings-tab]
       [main-tab])]))

(defonce root (rdc/create-root (.getElementById js/document "app")))

(defn ^:export init []
  (fetch-personas)
  (check-password-required)
  (rdc/render root [app]))
