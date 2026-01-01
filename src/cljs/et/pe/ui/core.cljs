(ns et.pe.ui.core
  (:require [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [ajax.core :refer [GET POST PUT DELETE]]))

(defonce app-state (r/atom {:personas []
                            :current-user nil
                            :current-tab :main
                            :show-login-modal false
                            :identities []
                            :selected-identity nil
                            :identity-history []
                            :editing-text ""
                            :slider-value 0
                            :new-persona-name ""
                            :new-persona-email ""
                            :new-identity-id ""
                            :new-identity-text ""
                            :relations []
                            :show-add-relation-modal false
                            :show-search-modal false
                            :relation-search-query ""
                            :relation-search-results []
                            :nav-search-query ""
                            :nav-search-results []}))

(def api-base "http://localhost:3017")

(defn fetch-personas []
  (GET (str api-base "/api/personas")
    {:handler (fn [res]
                (swap! app-state assoc :personas res))
     :response-format :json
     :keywords? true
     :error-handler #(js/console.error "Error fetching personas" %)}))

(defn add-persona []
  (let [{:keys [new-persona-name new-persona-email]} @app-state]
    (when (and (seq new-persona-name) (seq new-persona-email))
      (POST (str api-base "/api/personas")
        {:params {:name new-persona-name :email new-persona-email}
         :format :json
         :handler (fn [_]
                    (swap! app-state assoc :new-persona-name "" :new-persona-email "")
                    (fetch-personas))
         :error-handler #(js/console.error "Error adding persona" %)}))))

(defn fetch-identities [persona-name]
  (GET (str api-base "/api/personas/" persona-name "/identities")
    {:handler (fn [res]
                (swap! app-state assoc :identities res))
     :response-format :json
     :keywords? true
     :error-handler #(js/console.error "Error fetching identities" %)}))

(defn add-identity []
  (let [{:keys [current-user new-identity-id new-identity-text]} @app-state]
    (when (and current-user (seq new-identity-id) (seq new-identity-text))
      (POST (str api-base "/api/personas/" (:name current-user) "/identities")
        {:params {:id new-identity-id :text new-identity-text}
         :format :json
         :handler (fn [_]
                    (swap! app-state assoc :new-identity-id "" :new-identity-text "")
                    (fetch-identities (:name current-user)))
         :error-handler #(js/console.error "Error adding identity" %)}))))

(declare fetch-identity-history)

(defn update-identity [identity-id text]
  (let [{:keys [current-user]} @app-state]
    (PUT (str api-base "/api/personas/" (:name current-user) "/identities/" identity-id)
      {:params {:text text}
       :format :json
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
                  (swap! app-state assoc :editing-text (:text res)))
       :response-format :json
       :keywords? true
       :error-handler #(js/console.error "Error fetching identity at time" %)})))

(declare fetch-relations)

(defn fetch-relations [identity-id]
  (let [{:keys [current-user]} @app-state]
    (GET (str api-base "/api/personas/" (:name current-user) "/identities/" identity-id "/relations")
      {:handler (fn [res]
                  (swap! app-state assoc :relations res))
       :response-format :json
       :keywords? true
       :error-handler #(js/console.error "Error fetching relations" %)})))

(defn add-relation [source-id]
  (let [{:keys [current-user selected-identity]} @app-state]
    (POST (str api-base "/api/personas/" (:name current-user) "/identities/" (:identity selected-identity) "/relations")
      {:params {:source_id source-id}
       :format :json
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
      {:handler (fn [_]
                  (fetch-relations (:identity selected-identity)))
       :error-handler #(js/console.error "Error deleting relation" %)})))

(defn search-identities [query callback]
  (let [{:keys [current-user]} @app-state]
    (GET (str api-base "/api/personas/" (:name current-user) "/identities/search")
      {:params {:q query}
       :handler callback
       :response-format :json
       :keywords? true
       :error-handler #(js/console.error "Error searching identities" %)})))

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
         :editing-text (:text identity)
         :relations [])
  (fetch-identity-history (:identity identity))
  (fetch-relations (:identity identity)))

(defn header []
  (let [{:keys [current-user current-tab]} @app-state]
    [:div {:style {:display "flex"
                   :justify-content "space-between"
                   :align-items "center"
                   :padding "1rem"
                   :background "#333"
                   :color "white"}}
     [:div {:style {:display "flex" :align-items "center" :gap "2rem"}}
      [:h1 {:style {:margin 0}} "Personalist"]
      [:div {:style {:display "flex" :gap "0.5rem"}}
       [:button {:on-click #(swap! app-state assoc :current-tab :main)
                 :style {:padding "0.5rem 1rem"
                         :cursor "pointer"
                         :background (if (= current-tab :main) "#555" "#333")
                         :color "white"
                         :border "1px solid #555"
                         :border-radius "4px"}}
        "Main"]
       [:button {:on-click #(swap! app-state assoc :current-tab :settings)
                 :style {:padding "0.5rem 1rem"
                         :cursor "pointer"
                         :background (if (= current-tab :settings) "#555" "#333")
                         :color "white"
                         :border "1px solid #555"
                         :border-radius "4px"}}
        "Settings"]
       (when current-user
         [:button {:on-click #(swap! app-state assoc :show-search-modal true)
                   :style {:padding "0.5rem 1rem"
                           :cursor "pointer"
                           :background "#333"
                           :color "white"
                           :border "1px solid #555"
                           :border-radius "4px"
                           :font-size "1.1rem"}}
          "\uD83D\uDD0D"])]]
     (if current-user
       [:div {:style {:display "flex" :align-items "center" :gap "1rem"}}
        [:span (str "Logged in as: " (:name current-user))]
        [:button {:on-click #(swap! app-state assoc :current-user nil :identities [] :selected-identity nil)
                  :style {:padding "0.5rem 1rem" :cursor "pointer"}}
         "Logout"]]
       [:button {:on-click #(swap! app-state assoc :show-login-modal true)
                 :style {:padding "0.5rem 1rem"
                         :cursor "pointer"
                         :background "#4CAF50"
                         :color "white"
                         :border "none"
                         :border-radius "4px"}}
        "Sign In"])]))

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
        [:h2 {:style {:margin-top 0}} "Sign In"]
        [:p {:style {:color "#666"}} "Select a persona to continue:"]
        (if (seq personas)
          [:ul {:style {:list-style "none" :padding 0 :margin 0}}
           (for [p personas]
             ^{:key (:name p)}
             [:li {:on-click #(select-persona p)
                   :style {:padding "0.75rem"
                           :cursor "pointer"
                           :background "#f5f5f5"
                           :border-radius "4px"
                           :margin-bottom "0.5rem"
                           :transition "background 0.2s"}
                   :on-mouse-over #(set! (.-background (.-style (.-target %))) "#e0e0e0")
                   :on-mouse-out #(set! (.-background (.-style (.-target %))) "#f5f5f5")}
              [:strong (:name p)] [:br] [:span {:style {:color "#666" :font-size "0.9rem"}} (:email p)]])]
          [:p {:style {:color "#666" :font-style "italic"}}
           "No personas yet. Add one in Settings."])
        [:button {:on-click #(swap! app-state assoc :show-login-modal false)
                  :style {:margin-top "1rem"
                          :padding "0.5rem 1rem"
                          :cursor "pointer"}}
         "Cancel"]]])))

(defn search-modal []
  (let [{:keys [show-search-modal nav-search-query nav-search-results identities]} @app-state]
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
             :on-click #(swap! app-state assoc :show-search-modal false :nav-search-query "" :nav-search-results [])}
       [:div {:style {:background "white"
                      :padding "2rem"
                      :border-radius "8px"
                      :min-width "400px"
                      :max-width "500px"
                      :max-height "80vh"
                      :overflow-y "auto"}
              :on-click #(.stopPropagation %)}
        [:h2 {:style {:margin-top 0}} "Search Identities"]
        [:input {:type "text"
                 :placeholder "Search by ID or text..."
                 :value nav-search-query
                 :auto-focus true
                 :on-change (fn [e]
                              (let [q (-> e .-target .-value)]
                                (swap! app-state assoc :nav-search-query q)
                                (when (>= (count q) 1)
                                  (search-identities q #(swap! app-state assoc :nav-search-results %)))))
                 :style {:width "100%"
                         :padding "0.75rem"
                         :font-size "1rem"
                         :border "1px solid #ccc"
                         :border-radius "4px"
                         :margin-bottom "1rem"}}]
        (if (seq nav-search-results)
          [:ul {:style {:list-style "none" :padding 0 :margin 0}}
           (for [result nav-search-results]
             ^{:key (:identity result)}
             [:li {:on-click (fn []
                               (let [identity-data (first (filter #(= (:identity %) (:identity result)) identities))]
                                 (when identity-data
                                   (select-identity identity-data))
                                 (swap! app-state assoc :show-search-modal false :nav-search-query "" :nav-search-results [])))
                   :style {:padding "0.75rem"
                           :cursor "pointer"
                           :background "#f5f5f5"
                           :border-radius "4px"
                           :margin-bottom "0.5rem"
                           :transition "background 0.2s"}
                   :on-mouse-over #(set! (.-background (.-style (.-target %))) "#e0e0e0")
                   :on-mouse-out #(set! (.-background (.-style (.-target %))) "#f5f5f5")}
              [:strong (:identity result)] [:br]
              [:span {:style {:color "#666" :font-size "0.9rem"}} (subs (or (:text result) "") 0 (min 50 (count (or (:text result) ""))))]])]
          (when (seq nav-search-query)
            [:p {:style {:color "#666" :font-style "italic"}} "No results found"]))
        [:button {:on-click #(swap! app-state assoc :show-search-modal false :nav-search-query "" :nav-search-results [])
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
         (str "Link an identity to: " (:identity selected-identity))]
        [:input {:type "text"
                 :placeholder "Search identities..."
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
                [:strong (:identity result)] [:br]
                [:span {:style {:color "#666" :font-size "0.9rem"}} (subs (or (:text result) "") 0 (min 50 (count (or (:text result) ""))))]]))])
        [:button {:on-click #(swap! app-state assoc :show-add-relation-modal false :relation-search-query "" :relation-search-results [])
                  :style {:margin-top "1rem"
                          :padding "0.5rem 1rem"
                          :cursor "pointer"}}
         "Cancel"]]])))

(defn identity-list []
  (let [{:keys [current-user identities selected-identity new-identity-id new-identity-text]} @app-state]
    (when current-user
      [:div {:style {:padding "1rem" :border-right "1px solid #ccc" :min-width "250px"}}
       [:h3 "Identities"]
       [:div {:style {:margin-bottom "1rem"}}
        [:input {:type "text"
                 :placeholder "Identity ID"
                 :value new-identity-id
                 :on-change #(swap! app-state assoc :new-identity-id (-> % .-target .-value))
                 :style {:display "block" :margin-bottom "0.5rem" :padding "0.5rem" :width "100%"}}]
        [:input {:type "text"
                 :placeholder "Text"
                 :value new-identity-text
                 :on-change #(swap! app-state assoc :new-identity-text (-> % .-target .-value))
                 :style {:display "block" :margin-bottom "0.5rem" :padding "0.5rem" :width "100%"}}]
        [:button {:on-click add-identity
                  :style {:padding "0.5rem 1rem" :cursor "pointer"}}
         "Add Identity"]]
       [:ul {:style {:list-style "none" :padding 0}}
        (for [i identities]
          ^{:key (:identity i)}
          [:li {:on-click #(select-identity i)
                :style {:padding "0.5rem"
                        :cursor "pointer"
                        :background (if (= (:identity i) (:identity selected-identity)) "#e0e0e0" "transparent")
                        :border-radius "4px"
                        :margin-bottom "0.25rem"}}
           [:strong (:identity i)] ": " (:text i)])]])))

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
                                     (fetch-identity-at (:identity selected-identity) (:valid-from entry)))))
                  :style {:width "100%"}}]
         (when current-entry
           [:div {:style {:font-size "0.8rem" :color "#666" :margin-top "0.5rem"}}
            "Valid from: " (:valid-from current-entry)])]))))

(defn relations-list []
  (let [{:keys [relations identities]} @app-state]
    [:div {:style {:margin-top "1.5rem" :padding-top "1rem" :border-top "1px solid #eee"}}
     [:div {:style {:display "flex" :align-items "center" :gap "1rem" :margin-bottom "1rem"}}
      [:h4 {:style {:margin 0}} "Linked Identities"]
      [:button {:on-click #(swap! app-state assoc :show-add-relation-modal true)
                :style {:padding "0.25rem 0.75rem"
                        :cursor "pointer"
                        :background "#2196F3"
                        :color "white"
                        :border "none"
                        :border-radius "4px"
                        :font-size "1.2rem"}}
       "\u221E"]]
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
              [:strong (:source rel)]
              (when source-identity
                [:span {:style {:color "#666" :margin-left "0.5rem"}}
                 (str "- " (subs (or (:text source-identity) "") 0 (min 30 (count (or (:text source-identity) "")))))])]
             [:button {:on-click #(delete-relation (:id rel))
                       :style {:padding "0.25rem 0.5rem"
                               :cursor "pointer"
                               :background "#ff5252"
                               :color "white"
                               :border "none"
                               :border-radius "4px"
                               :font-size "0.8rem"}}
              "X"]]))]
       [:p {:style {:color "#666" :font-style "italic" :margin 0}} "No linked identities yet"])]))

(defn identity-editor []
  (let [{:keys [selected-identity editing-text]} @app-state]
    (when selected-identity
      [:div {:style {:padding "1rem" :flex 1}}
       [:h3 "Edit Identity: " (:identity selected-identity)]
       [time-slider]
       [:textarea {:value editing-text
                   :on-change #(swap! app-state assoc :editing-text (-> % .-target .-value))
                   :style {:width "100%"
                           :height "200px"
                           :padding "0.5rem"
                           :font-size "1rem"
                           :border "1px solid #ccc"
                           :border-radius "4px"}}]
       [:button {:on-click #(update-identity (:identity selected-identity) editing-text)
                 :style {:margin-top "1rem"
                         :padding "0.5rem 1rem"
                         :cursor "pointer"
                         :background "#4CAF50"
                         :color "white"
                         :border "none"
                         :border-radius "4px"}}
        "Save Changes"]
       [relations-list]])))

(defn main-tab []
  (let [{:keys [current-user]} @app-state]
    (if current-user
      [:div {:style {:display "flex" :min-height "calc(100vh - 60px)"}}
       [identity-list]
       [identity-editor]]
      [:div {:style {:display "flex"
                     :justify-content "center"
                     :align-items "center"
                     :min-height "calc(100vh - 60px)"
                     :color "#666"}}
       [:div {:style {:text-align "center"}}
        [:p {:style {:font-size "1.2rem"}} "Please sign in to continue"]
        [:button {:on-click #(swap! app-state assoc :show-login-modal true)
                  :style {:padding "0.75rem 1.5rem"
                          :cursor "pointer"
                          :background "#4CAF50"
                          :color "white"
                          :border "none"
                          :border-radius "4px"
                          :font-size "1rem"}}
         "Sign In"]]])))

(defn settings-tab []
  (let [{:keys [personas new-persona-name new-persona-email]} @app-state]
    [:div {:style {:padding "2rem" :max-width "600px"}}
     [:h2 "Settings"]
     [:div {:style {:margin-bottom "2rem"}}
      [:h3 "Add New Persona"]
      [:div {:style {:display "flex" :flex-direction "column" :gap "0.5rem" :max-width "300px"}}
       [:input {:type "text"
                :placeholder "Name"
                :value new-persona-name
                :on-change #(swap! app-state assoc :new-persona-name (-> % .-target .-value))
                :style {:padding "0.5rem"}}]
       [:input {:type "email"
                :placeholder "Email"
                :value new-persona-email
                :on-change #(swap! app-state assoc :new-persona-email (-> % .-target .-value))
                :style {:padding "0.5rem"}}]
       [:button {:on-click add-persona
                 :style {:padding "0.5rem 1rem" :cursor "pointer" :margin-top "0.5rem"}}
        "Add Persona"]]]
     [:div
      [:h3 "Existing Personas"]
      (if (seq personas)
        [:ul {:style {:list-style "none" :padding 0}}
         (for [p personas]
           ^{:key (:name p)}
           [:li {:style {:padding "0.5rem" :background "#f5f5f5" :margin-bottom "0.25rem" :border-radius "4px"}}
            [:strong (:name p)] " - " (:email p)])]
        [:p {:style {:color "#666" :font-style "italic"}} "No personas yet."])]]))

(defn app []
  (let [{:keys [current-tab]} @app-state]
    [:div {:style {:font-family "Arial, sans-serif"}}
     [header]
     [login-modal]
     [search-modal]
     [add-relation-modal]
     (case current-tab
       :settings [settings-tab]
       [main-tab])]))

(defonce root (rdc/create-root (.getElementById js/document "app")))

(defn ^:export init []
  (fetch-personas)
  (rdc/render root [app]))
