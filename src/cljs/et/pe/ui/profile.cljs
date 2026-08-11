(ns et.pe.ui.profile
  "The logged-in user's own personas. An account is an email and a password; the
   personas under it are the public addresses it writes at, and this is where
   the account decides how many of them it wears. Nothing here is visible to
   anyone else: which personas share an account is exactly what the anonymity
   rule protects, so this page is fed by GET /api/me and never by the public
   persona list."
  (:require [reagent.core :as r]
            [et.pe.ui.state :refer [app-state generate-id create-persona
                                    delete-persona switch-persona show-notification]]))

(defn- create-form []
  (let [generated-id (r/atom nil)
        display-name (r/atom "")
        error (r/atom nil)
        regenerate! (fn [] (generate-id #(reset! generated-id %)))
        submit! (fn []
                  (reset! error nil)
                  (cond
                    (not (seq @generated-id)) (reset! error "ID not generated yet")
                    (not (seq @display-name)) (reset! error "Display name is required")
                    :else
                    (create-persona @generated-id @display-name
                                    (fn [_]
                                      (reset! display-name "")
                                      (regenerate!)
                                      (show-notification "Persona created" :success))
                                    #(reset! error %))))]
    (regenerate!)
    (fn []
      [:div {:style {:display "flex" :flex-direction "column" :gap "0.5rem" :max-width "360px"}}
       [:div {:style {:display "flex" :gap "0.5rem" :align-items "center"}}
        [:input {:type "text"
                 :value (or @generated-id "")
                 :read-only true
                 :placeholder "Generating ID..."
                 :style {:padding "0.5rem" :flex 1 :background "#f0f0f0" :color "#666"
                         :font-family "monospace"}}]
        [:button {:on-click regenerate!
                  :style {:padding "0.5rem" :cursor "pointer"}}
         "Regenerate"]]
       [:input {:type "text"
                :placeholder "Display Name"
                :value @display-name
                :on-change #(do (reset! display-name (.. % -target -value))
                                (reset! error nil))
                :on-key-down #(when (= (.-key %) "Enter") (submit!))
                :style {:padding "0.5rem"}}]
       (when @error
         [:p {:style {:color "red" :margin 0 :font-size "0.85rem"}} @error])
       [:button {:on-click submit!
                 :style {:padding "0.5rem 1rem" :cursor "pointer" :margin-top "0.5rem"
                         :background "#4CAF50" :color "white" :border "none"
                         :border-radius "4px" :align-self "flex-start"}}
        "Create Persona"]])))

(defn- remove-dialog
  "Removal is real destruction, so the urbit id has to be typed out by hand —
   the delete button stays disabled until it matches. The server is told the
   typed value and checks it again; this dialog is not the authority."
  [_persona _on-close]
  (let [typed (r/atom "")
        error (r/atom nil)]
    (fn [persona on-close]
      (let [matches? (= @typed (:id persona))]
        [:div {:style {:position "fixed" :top 0 :left 0 :right 0 :bottom 0
                       :background "rgba(0,0,0,0.5)"
                       :display "flex" :align-items "center" :justify-content "center"
                       :z-index 1000}
               :on-click on-close}
         [:div {:style {:background "white" :padding "2rem" :border-radius "8px"
                        :min-width "380px" :max-width "480px"}
                :on-click #(.stopPropagation %)}
          [:h2 {:style {:margin-top 0 :color "#c62828"}} "Remove this persona?"]
          [:p {:style {:color "#333" :margin-bottom "0.5rem"}}
           "This permanently destroys the persona "
           [:strong (or (:name persona) (:id persona))]
           " and " [:strong "every identity under it, with its whole history"] "."]
          [:p {:style {:color "#666" :margin-top 0 :margin-bottom "1rem"}}
           "There is no undo. The address "
           [:code {:style {:font-family "monospace"}} (str "/" (:id persona))]
           " stops resolving for everyone."]
          [:p {:style {:color "#333" :margin-bottom "0.5rem" :font-size "0.9rem"}}
           "Type " [:code {:style {:font-family "monospace" :font-weight "bold"}} (:id persona)]
           " to confirm:"]
          [:input {:type "text"
                   :value @typed
                   :auto-focus true
                   :placeholder (:id persona)
                   :on-change #(do (reset! typed (.. % -target -value)) (reset! error nil))
                   :style {:width "100%" :padding "0.75rem" :box-sizing "border-box"
                           :font-family "monospace" :border "1px solid #ccc"
                           :border-radius "4px" :margin-bottom "1rem"}}]
          (when @error
            [:p {:style {:color "red" :margin "0 0 1rem 0"}} @error])
          [:div {:style {:display "flex" :gap "0.5rem" :justify-content "flex-end"}}
           [:button {:on-click on-close
                     :style {:padding "0.5rem 1rem" :cursor "pointer"}}
            "Cancel"]
           [:button {:disabled (not matches?)
                     :on-click (fn []
                                 (delete-persona (:id persona) @typed
                                                 (fn [_]
                                                   (on-close)
                                                   (show-notification "Persona removed" :success))
                                                 #(reset! error %)))
                     :style {:padding "0.5rem 1rem"
                             :cursor (if matches? "pointer" "not-allowed")
                             :background (if matches? "#c62828" "#ccc")
                             :color "white" :border "none" :border-radius "4px"}}
            "Remove permanently"]]]]))))

(defn- persona-row [persona active? removable? on-remove]
  [:li {:style {:padding "0.75rem" :background (if active? "#e8f5e9" "#f5f5f5")
                :border (if active? "1px solid #a5d6a7" "1px solid transparent")
                :margin-bottom "0.5rem" :border-radius "4px"
                :display "flex" :align-items "center" :gap "0.75rem"}}
   [:span {:style {:color "#888" :font-size "0.85rem" :font-family "monospace" :min-width "140px"}}
    (:id persona)]
   [:strong {:style {:flex 1}} (or (:name persona) (:id persona))]
   (if active?
     [:span {:style {:color "#2e7d32" :font-size "0.85rem"}} "active"]
     [:button {:on-click #(switch-persona persona)
               :style {:padding "0.25rem 0.5rem" :cursor "pointer"}}
      "Enter"])
   ;; An account's last persona offers no remove at all: a login with no persona
   ;; leads nowhere, and the server refuses it anyway.
   (when removable?
     [:button {:on-click #(on-remove persona)
               :style {:padding "0.25rem 0.5rem" :cursor "pointer"
                       :background "#fff" :color "#c62828"
                       :border "1px solid #ef9a9a" :border-radius "4px"}}
      "Remove"])])

(defn profile-tab []
  (let [removing (r/atom nil)]
    (fn []
      (let [{:keys [account auth-user]} @app-state
            personas (:personas account)]
        [:div {:style {:padding "2rem" :max-width "640px"}}
         [:h2 {:style {:margin-top 0}} "Your Personas"]
         [:p {:style {:color "#666"}}
          "Signed in as " [:strong (:email account)] ". "
          "Nobody else can tell that these personas belong together."]
         (when @removing
           [remove-dialog @removing #(reset! removing nil)])
         [:div {:style {:margin-bottom "2rem"}}
          (if (seq personas)
            [:ul {:style {:list-style "none" :padding 0}}
             (for [p personas]
               ^{:key (:id p)}
               [persona-row p
                (= (:id p) (:id auth-user))
                (> (count personas) 1)
                #(reset! removing %)])]
            [:p {:style {:color "#666" :font-style "italic"}} "No personas yet."])]
         [:div
          [:h3 "Add a Persona"]
          [:p {:style {:color "#666" :font-size "0.9rem" :margin-top 0}}
           "The generated id is the public address it will be reached at."]
          [create-form]]]))))
