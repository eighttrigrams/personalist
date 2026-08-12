(ns et.pe.ui.core
  (:require [reagent.dom.client :as rdc]
            [clojure.string :as string]
            [et.pe.ui.state :refer [app-state fetch-personas check-password-required logout-user dismiss-notification load-from-url parse-url fetch-recent-identities exit-fixed-mode open-search-modal restore-auth]]
            [et.pe.ui.modals :refer [login-modal auth-modal
                                     search-modal add-relation-modal
                                     add-identity-modal beta-modal]]
            [et.pe.ui.identity :refer [main-tab]]
            [et.pe.ui.profile :refer [profile-tab]]
            [et.pe.ui.settings :refer [settings-tab]]))

(defn- persona-label
  "What the header calls a persona, and it now says it the same way to everybody.

  It used to read `Persona: <name>` for a visitor and `Logged in: <persona>` for a
  signed-in reader — one thing named two ways, and the signed-in half was wrong as
  well as different. Logging in is a property of the **account**: an account may
  hold several personas and none of them *is* the login, so a persona's name after
  `Logged in:` describes the wrong thing. What a login is called is an email, and
  that is what the header says in the one case where there is no persona to name.

  The id is the fallback because that is what a persona is addressed by — the
  visitor's half had no fallback, so a nameless persona rendered `Persona: ` there
  and cannot now."
  [persona]
  (str "Persona: " (or (:name persona) (:id persona))))

(defn header []
  (let [{:keys [current-user auth-user current-tab account]} @app-state
        ;; Logged in is a property of the *account*, not of having a persona:
        ;; an account may hold none, and it is still logged in — it just has no
        ;; active persona, so nothing here that acts on one is offered.
        logged-in? (some? account)
        is-admin? (true? (:admin account))
        active-persona? (some? auth-user)]
    [:div {:style {:display "flex"
                   :justify-content "space-between"
                   :align-items "center"
                   :padding "1rem"
                   :background "#333"
                   :color "white"}}
     [:div {:style {:display "flex" :align-items "center" :gap "2rem"}}
      [:div {:style {:display "flex" :align-items "center" :gap "0.5rem"}}
       [:h1 {:style {:margin 0 :cursor "pointer"}
             :on-click #(let [user (or (:auth-user @app-state) (:current-user @app-state))]
                          (swap! app-state assoc
                                 :current-tab :main
                                 :selected-identity nil
                                 :not-found-persona nil
                                 :not-found-identity nil
                                 :fixed-mode? false
                                 :fixed-time nil
                                 :current-user user)
                          (when user (fetch-recent-identities (:id user)))
                          (.pushState js/history nil "" (if user (str "/" (:id user)) "/")))}
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
       ;; Personas is not here but on the right, beside the persona it switches.
       ;; What is left on this side acts *within* the persona you are looking at —
       ;; add an identity, search it — and Personas is how you leave it for another.
       (when (and logged-in? (not is-admin?) active-persona?)
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
          [:button {:on-click #(open-search-modal)
                    :style {:padding "0.5rem 1rem"
                            :cursor "pointer"
                            :background "#333"
                            :color "white"
                            :border "1px solid #555"
                            :border-radius "4px"
                            :font-size "1.1rem"}}
           "\uD83D\uDD0D"]])
       (when (and (not logged-in?) current-user)
         [:button {:on-click #(open-search-modal)
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
        [:div {:style {:display "flex" :align-items "center" :gap "0.5rem"}}
         [:span {:style {:cursor "pointer"}
                 :on-click #(do (swap! app-state assoc
                                       :current-tab :main
                                       :selected-identity nil
                                       :not-found-persona nil
                                       :not-found-identity nil
                                       :fixed-mode? false
                                       :fixed-time nil)
                                (fetch-recent-identities (:id current-user))
                                (.pushState js/history nil "" (str "/" (:id current-user))))}
          (persona-label current-user)]
         [:span {:on-click #(do (swap! app-state assoc :current-user nil :identities [] :selected-identity nil
                                       :fixed-mode? false :fixed-time nil)
                                (.pushState js/history nil "" "/"))
                 :style {:width "18px"
                         :height "18px"
                         :border-radius "50%"
                         :background "#999"
                         :color "white"
                         :font-size "12px"
                         :display "flex"
                         :align-items "center"
                         :justify-content "center"
                         :cursor "pointer"
                         :line-height "1"}}
          "\u00D7"]])
      (when logged-in?
        [:<>
         ;; One login may hold several personas now; this is where you see them
         ;; all, switch between them, and add or remove one. It stands immediately
         ;; left of the persona name, because that name is what it is about.
         (when-not is-admin?
           [:button {:on-click #(swap! app-state assoc :current-tab :profile)
                     :style {:padding "0.5rem 1rem"
                             :cursor "pointer"
                             :background (if (= current-tab :profile) "#555" "#333")
                             :color "white"
                             :border "1px solid #555"
                             :border-radius "4px"}}
            "Personas"])
         [:span {:style {:cursor "pointer"}
                 :on-click #(if active-persona?
                              (do (swap! app-state assoc
                                         :current-tab :main
                                         :selected-identity nil
                                         :not-found-persona nil
                                         :not-found-identity nil
                                         :current-user auth-user)
                                  (fetch-recent-identities (:id auth-user))
                                  (.pushState js/history nil "" (str "/" (:id auth-user))))
                              ;; no active persona means no splash page to go to
                              (swap! app-state assoc :current-tab :profile))}
          ;; The email only where there is no persona to name: an account may hold
          ;; none, and then this is the only thing in the header that says whose
          ;; session it is. With one active it names the persona, exactly as a
          ;; visitor's header does.
          (if active-persona?
            (persona-label auth-user)
            (str "Logged in: " (or (:email account) "")))]
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

(defn fixed-mode-strip []
  (let [{:keys [fixed-mode? fixed-time auth-user]} @app-state]
    (when (and fixed-mode? fixed-time (nil? auth-user))
      [:div {:style {:display "flex"
                     :justify-content "center"
                     :align-items "center"
                     :position "relative"
                     :padding "0.35rem 1rem"
                     :background "#e3f2fd"
                     :border-bottom "1px solid #90caf9"
                     :color "#0d47a1"
                     :font-size "0.85rem"}}
       [:span "Fixed to " [:strong (first (string/split fixed-time #"T"))]]
       [:span {:on-click exit-fixed-mode
               :title "Back to normal browsing"
               :style {:cursor "pointer"
                       :position "absolute"
                       :right "1rem"
                       :width "18px"
                       :height "18px"
                       :border-radius "50%"
                       :background "#90caf9"
                       :color "#0d47a1"
                       :font-size "12px"
                       :display "flex"
                       :align-items "center"
                       :justify-content "center"
                       :line-height "1"}}
        "×"]])))

(defn notification-bar []
  (when-let [{:keys [message type]} (:notification @app-state)]
    [:div {:style {:position "fixed"
                   :bottom 0
                   :left 0
                   :right 0
                   :padding "1rem"
                   :background (case type :error "#f44336" :success "#4CAF50" "#333")
                   :color "white"
                   :display "flex"
                   :justify-content "space-between"
                   :align-items "center"
                   :z-index 2000
                   :box-shadow "0 -2px 10px rgba(0,0,0,0.2)"}}
     [:span message]
     [:button {:on-click dismiss-notification
               :style {:background "transparent"
                       :border "none"
                       :color "white"
                       :font-size "1.2rem"
                       :cursor "pointer"
                       :padding "0 0.5rem"}}
      "\u00D7"]]))

(defn app []
  (let [{:keys [current-tab]} @app-state]
    [:div {:style {:font-family "Arial, sans-serif"}}
     [header]
     [fixed-mode-strip]
     [login-modal]
     [auth-modal]
     [search-modal]
     [add-relation-modal]
     [add-identity-modal]
     [beta-modal]
     [notification-bar]
     (case current-tab
       :settings [settings-tab]
       :profile [profile-tab]
       [main-tab])]))

(defonce root (rdc/create-root (.getElementById js/document "app")))

(defn ^:export init []
  (check-password-required)
  ;; browser back/forward: re-sync the whole view from the (now changed) URL
  (.addEventListener js/window "popstate" (fn [_] (load-from-url nil)))
  ;; Auth is restored first and the rest of the boot waits for it: it takes a
  ;; round trip to /api/me now (the token names an account, not a persona), and
  ;; what loads next depends on the answer — fixed mode is for visitors only.
  (restore-auth (fn []
                  (let [{:keys [persona-id]} (parse-url)]
                    (if persona-id
                      (load-from-url nil)
                      (fetch-personas)))))
  (rdc/render root [app]))
