(ns et.pe.ui.core
  (:require [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [ajax.core :refer [GET POST PUT]]))

(defonce app-state (r/atom {:personas []
                            :current-user nil
                            :identities []
                            :selected-identity nil
                            :identity-history []
                            :editing-text ""
                            :slider-value 0
                            :new-persona-name ""
                            :new-persona-email ""
                            :new-identity-id ""
                            :new-identity-text ""}))

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

(defn select-persona [persona]
  (swap! app-state assoc
         :current-user persona
         :identities []
         :selected-identity nil
         :identity-history [])
  (fetch-identities (:name persona)))

(defn select-identity [identity]
  (swap! app-state assoc
         :selected-identity identity
         :editing-text (:text identity))
  (fetch-identity-history (:identity identity)))

(defn header []
  (let [{:keys [current-user]} @app-state]
    [:div {:style {:display "flex"
                   :justify-content "space-between"
                   :align-items "center"
                   :padding "1rem"
                   :background "#333"
                   :color "white"}}
     [:h1 {:style {:margin 0}} "Personalist"]
     (when current-user
       [:div {:style {:display "flex" :align-items "center" :gap "1rem"}}
        [:span (str "Logged in as: " (:name current-user))]
        [:button {:on-click #(swap! app-state assoc :current-user nil :identities [] :selected-identity nil)
                  :style {:padding "0.5rem 1rem" :cursor "pointer"}}
         "Logout"]])]))

(defn persona-list []
  (let [{:keys [personas current-user new-persona-name new-persona-email]} @app-state]
    [:div {:style {:padding "1rem" :border-right "1px solid #ccc" :min-width "250px"}}
     [:h3 "Personas"]
     [:div {:style {:margin-bottom "1rem"}}
      [:input {:type "text"
               :placeholder "Name"
               :value new-persona-name
               :on-change #(swap! app-state assoc :new-persona-name (-> % .-target .-value))
               :style {:display "block" :margin-bottom "0.5rem" :padding "0.5rem" :width "100%"}}]
      [:input {:type "email"
               :placeholder "Email"
               :value new-persona-email
               :on-change #(swap! app-state assoc :new-persona-email (-> % .-target .-value))
               :style {:display "block" :margin-bottom "0.5rem" :padding "0.5rem" :width "100%"}}]
      [:button {:on-click add-persona
                :style {:padding "0.5rem 1rem" :cursor "pointer"}}
       "Add Persona"]]
     [:ul {:style {:list-style "none" :padding 0}}
      (for [p personas]
        ^{:key (:name p)}
        [:li {:on-click #(select-persona p)
              :style {:padding "0.5rem"
                      :cursor "pointer"
                      :background (if (= (:name p) (:name current-user)) "#e0e0e0" "transparent")
                      :border-radius "4px"
                      :margin-bottom "0.25rem"}}
         (:name p) " (" (:email p) ")"])]]))

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
        "Save Changes"]])))

(defn app []
  [:div {:style {:font-family "Arial, sans-serif"}}
   [header]
   [:div {:style {:display "flex" :min-height "calc(100vh - 60px)"}}
    [persona-list]
    [identity-list]
    [identity-editor]]])

(defonce root (rdc/create-root (.getElementById js/document "app")))

(defn ^:export init []
  (fetch-personas)
  (rdc/render root [app]))
