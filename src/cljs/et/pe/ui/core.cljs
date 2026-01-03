(ns et.pe.ui.core
  (:require [reagent.dom.client :as rdc]
            [et.pe.ui.state :refer [app-state fetch-personas check-password-required logout-user]]
            [et.pe.ui.modals :refer [login-modal auth-modal password-modal
                                     search-modal add-relation-modal
                                     add-identity-modal beta-modal]]
            [et.pe.ui.identity :refer [main-tab]]
            [et.pe.ui.settings :refer [settings-tab]]))

(defn header []
  (let [{:keys [current-user auth-user current-tab]} @app-state
        logged-in? (some? auth-user)
        is-admin? (= (:id auth-user) "admin")]
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
         [:span (str "Persona: " (:id current-user))]
         [:button {:on-click #(swap! app-state assoc :current-user nil :identities [] :selected-identity nil)
                   :style {:padding "0.5rem 1rem" :cursor "pointer"}}
          "Change"]])
      (when logged-in?
        [:<>
         [:span (str "Logged in: " (or (:name auth-user) (:id auth-user)))]
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
