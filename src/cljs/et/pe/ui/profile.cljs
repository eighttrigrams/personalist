(ns et.pe.ui.profile
  "The logged-in user's own personas. An account is an email and a password; the
   personas under it are the public addresses it writes at, and this is where
   the account decides how many of them it wears. Nothing here is visible to
   anyone else: which personas share an account is exactly what the anonymity
   rule protects, so this page is fed by GET /api/me and never by the public
   persona list."
  (:require [reagent.core :as r]
            [et.pe.ui.state :refer [app-state generate-id create-persona
                                    delete-persona switch-persona show-notification
                                    create-machine-user rotate-machine-token
                                    update-machine-user delete-machine-user
                                    update-persona fetch-me fetch-personas]]
            [et.pe.ui.persona :refer [private-badge]]))

(defn- create-form []
  (let [generated-id (r/atom nil)
        display-name (r/atom "")
        private? (r/atom false)
        error (r/atom nil)
        regenerate! (fn [] (generate-id #(reset! generated-id %)))
        submit! (fn []
                  (reset! error nil)
                  (cond
                    (not (seq @generated-id)) (reset! error "ID not generated yet")
                    (not (seq @display-name)) (reset! error "Display name is required")
                    :else
                    (create-persona @generated-id @display-name @private?
                                    (fn [_]
                                      (reset! display-name "")
                                      (reset! private? false)
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
       ;; Chosen here rather than toggled after the fact, so a persona meant to
       ;; be private is never a public address for as long as it takes to press
       ;; a second button.
       [:label {:style {:display "flex" :align-items "flex-start" :gap "0.4rem"
                        :font-size "0.9rem" :cursor "pointer" :margin-top "0.25rem"}}
        [:input {:type "checkbox"
                 :checked @private?
                 :on-change #(swap! private? not)
                 :style {:margin-top "0.2rem"}}]
        [:span "Private — only you can read it. The address answers everyone
                else as though there were no such persona."]]
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

(defn- persona-row
  "One persona of the account: its address, its display name, whether it is
   private, and the three things that can be done to it.

   **Private is a plain toggle and not a confirmed step**, unlike Remove. Hiding
   destroys nothing, and publishing something that was private is undone by
   pressing it again — what the hand-typed confirmation next to it guards is the
   step there is no way back from."
  [persona active? on-remove on-toggle-private]
  (let [private? (boolean (:private persona))]
    [:li {:style {:padding "0.75rem" :background (if active? "#e8f5e9" "#f5f5f5")
                  :border (if active? "1px solid #a5d6a7" "1px solid transparent")
                  :margin-bottom "0.5rem" :border-radius "4px"
                  :display "flex" :align-items "center" :gap "0.75rem"}}
     [:span {:style {:color "#888" :font-size "0.85rem" :font-family "monospace" :min-width "140px"}}
      (:id persona)]
     [:strong {:style {:flex 1}} (or (:name persona) (:id persona))]
     (when private? private-badge)
     [:button {:on-click #(on-toggle-private persona)
               :title (if private?
                        "Make this a public address again"
                        "Only you will be able to read it")
               :style {:padding "0.25rem 0.5rem" :cursor "pointer"
                       :background "#fff" :color "#5c4b8a"
                       :border "1px solid #d5cbee" :border-radius "4px"}}
      (if private? "Publish" "Make private")]
     (if active?
       [:span {:style {:color "#2e7d32" :font-size "0.85rem"}} "active"]
       [:button {:on-click #(switch-persona persona)
                 :style {:padding "0.25rem 0.5rem" :cursor "pointer"}}
        "Enter"])
     ;; Every persona offers Remove, the last one included: an account may hold
     ;; none. The hand-typed confirmation is the guard, and it is enough.
     [:button {:on-click #(on-remove persona)
               :style {:padding "0.25rem 0.5rem" :cursor "pointer"
                       :background "#fff" :color "#c62828"
                       :border "1px solid #ef9a9a" :border-radius "4px"}}
      "Remove"]]))


;; ---------------------------------------------------------------------------
;; Machine users
;;
;; A machine user is a credential under this account, not a person: it never
;; logs in, it holds no content of its own, and its whole identity is a bearer
;; token this page shows exactly once. The checkbox grid below *is* the
;; permission model made visible — it should read the way the owner said it:
;; this one writes A and C, that one writes B and C.
;; ---------------------------------------------------------------------------

(defn- token-box
  "The one and only sighting of a token. Selectable, whole, and not truncated —
   it is going straight into secrets.yaml."
  [token on-close]
  [:div {:style {:margin "0.75rem 0" :padding "1rem" :background "#fff8e1"
                 :border "1px solid #ffb300" :border-radius "4px"}}
   [:p {:style {:margin "0 0 0.5rem 0" :font-weight "bold" :color "#8d6e00"}}
    "Copy this token now — it will not be shown again."]
   [:p {:style {:margin "0 0 0.75rem 0" :font-size "0.85rem" :color "#8d6e00"}}
    "Only a hash of it is stored, so it cannot be looked up later. If you lose
     it, rotate to get a new one."]
   [:textarea {:value token
               :read-only true
               :auto-focus true
               :on-focus #(.select (.-target %))
               :rows 2
               :style {:width "100%" :box-sizing "border-box" :padding "0.5rem"
                       :font-family "monospace" :font-size "0.85rem"
                       :word-break "break-all" :resize "vertical"
                       :border "1px solid #ffb300" :border-radius "4px"
                       :background "white"}}]
   [:button {:on-click on-close
             :style {:margin-top "0.5rem" :padding "0.35rem 0.75rem" :cursor "pointer"}}
    "Done"]])

(defn- rotate-dialog [_nm _on-close]
  (let [error (r/atom nil)]
    (fn [nm on-close]
      [:div {:style {:position "fixed" :top 0 :left 0 :right 0 :bottom 0
                     :background "rgba(0,0,0,0.5)"
                     :display "flex" :align-items "center" :justify-content "center"
                     :z-index 1000}
             :on-click #(on-close nil)}
       [:div {:style {:background "white" :padding "2rem" :border-radius "8px"
                      :min-width "380px" :max-width "480px"}
              :on-click #(.stopPropagation %)}
        [:h2 {:style {:margin-top 0}} "Rotate this token?"]
        [:p {:style {:color "#333"}}
         "A new token is issued for " [:strong nm] " and shown once. "
         [:strong "Whatever is using the old token stops working the moment you press this"]
         " — including anything already running with it in a config file."]
        (when @error
          [:p {:style {:color "red" :margin "0 0 1rem 0"}} @error])
        [:div {:style {:display "flex" :gap "0.5rem" :justify-content "flex-end"}}
         [:button {:on-click #(on-close nil)
                   :style {:padding "0.5rem 1rem" :cursor "pointer"}}
          "Cancel"]
         [:button {:on-click (fn []
                               (rotate-machine-token nm
                                                     (fn [res] (on-close (:token res)))
                                                     (fn [e] (reset! error e))))
                   :style {:padding "0.5rem 1rem" :cursor "pointer"
                           :background "#ff8f00" :color "white"
                           :border "none" :border-radius "4px"}}
          "Rotate"]]]])))

(defn- machine-user-row
  "One machine user: its name, a checkbox per persona of the account, the
   can-create toggle, and the two buttons. The grid is the permission model."
  [_m _personas _on-token]
  (let [error (r/atom nil)
        confirming-remove? (r/atom false)
        rotating? (r/atom false)]
    (fn [m personas on-token]
      (let [granted (set (:personas m))
            save! (fn [updates]
                    (reset! error nil)
                    (update-machine-user (:name m) updates
                                         (fn [])
                                         (fn [e] (reset! error e))))]
        [:li {:style {:padding "0.75rem" :background "#f5f5f5"
                      :margin-bottom "0.5rem" :border-radius "4px"}}
         (when @rotating?
           [rotate-dialog (:name m)
            (fn [token]
              (reset! rotating? false)
              (when token (on-token token)))])
         [:div {:style {:display "flex" :align-items "center" :gap "0.75rem"}}
          [:strong {:style {:flex 1 :font-family "monospace"}} (:name m)]
          [:button {:on-click #(reset! rotating? true)
                    :style {:padding "0.25rem 0.5rem" :cursor "pointer"}}
           "Rotate token"]
          (if @confirming-remove?
            [:<>
             [:span {:style {:font-size "0.85rem" :color "#c62828"}} "Remove?"]
             [:button {:on-click (fn []
                                   (reset! confirming-remove? false)
                                   (delete-machine-user
                                    (:name m)
                                    (fn [] (show-notification "Machine user removed" :success))
                                    (fn [e] (reset! error e))))
                       :style {:padding "0.25rem 0.5rem" :cursor "pointer"
                               :background "#c62828" :color "white"
                               :border "none" :border-radius "4px"}}
              "Yes"]
             [:button {:on-click #(reset! confirming-remove? false)
                       :style {:padding "0.25rem 0.5rem" :cursor "pointer"}}
              "No"]]
            ;; An ordinary confirm, not the hand-typed one that guards persona
            ;; removal: a machine user holds no content, and re-creating one is
            ;; a name and a click.
            [:button {:on-click #(reset! confirming-remove? true)
                      :style {:padding "0.25rem 0.5rem" :cursor "pointer"
                              :background "#fff" :color "#c62828"
                              :border "1px solid #ef9a9a" :border-radius "4px"}}
             "Remove"])]
         [:div {:style {:margin-top "0.5rem" :display "flex" :flex-wrap "wrap" :gap "0.75rem"}}
          (for [p personas]
            ^{:key (:id p)}
            [:label {:style {:display "flex" :align-items "center" :gap "0.25rem"
                             :font-size "0.9rem" :cursor "pointer"}}
             [:input {:type "checkbox"
                      :checked (contains? granted (:id p))
                      :on-change (fn [_]
                                   (let [next (if (contains? granted (:id p))
                                                (disj granted (:id p))
                                                (conj granted (:id p)))]
                                     (save! {:personas (vec next)})))}]
             [:span (or (:name p) (:id p))]
             [:span {:style {:color "#888" :font-family "monospace" :font-size "0.8rem"}}
              (:id p)]])]
         [:label {:style {:display "flex" :align-items "center" :gap "0.25rem"
                          :margin-top "0.5rem" :font-size "0.9rem" :cursor "pointer"}}
          [:input {:type "checkbox"
                   :checked (boolean (:can-create m))
                   :on-change #(save! {:can-create? (not (:can-create m))})}]
          [:span "can create personas"]]
         (when @error
           [:p {:style {:color "red" :margin "0.5rem 0 0 0" :font-size "0.85rem"}} @error])]))))

(defn- machine-user-form [_on-token]
  (let [nm (r/atom "")
        can-create? (r/atom false)
        error (r/atom nil)]
    (fn [on-token]
      (let [submit! (fn []
                      (reset! error nil)
                      (if-not (seq @nm)
                        (reset! error "Name is required")
                        (create-machine-user
                         @nm @can-create?
                         (fn [res]
                           (reset! nm "")
                           (reset! can-create? false)
                           (on-token (:token res)))
                         (fn [e] (reset! error e)))))]
        [:div {:style {:display "flex" :flex-direction "column" :gap "0.5rem" :max-width "360px"}}
         [:input {:type "text"
                  :placeholder "Name, e.g. daniel-machine"
                  :value @nm
                  :on-change #(do (reset! nm (.. % -target -value)) (reset! error nil))
                  :on-key-down #(when (= (.-key %) "Enter") (submit!))
                  :style {:padding "0.5rem" :font-family "monospace"}}]
         [:label {:style {:display "flex" :align-items "center" :gap "0.25rem"
                          :font-size "0.9rem" :cursor "pointer"}}
          [:input {:type "checkbox"
                   :checked @can-create?
                   :on-change #(swap! can-create? not)}]
          [:span "can create personas"]]
         (when @error
           [:p {:style {:color "red" :margin 0 :font-size "0.85rem"}} @error])
         [:button {:on-click submit!
                   :style {:padding "0.5rem 1rem" :cursor "pointer"
                           :background "#4CAF50" :color "white" :border "none"
                           :border-radius "4px" :align-self "flex-start"}}
          "Add Machine User"]]))))

(defn- machine-users-section [_personas]
  (let [token (r/atom nil)]
    (fn [personas]
      (let [machine-users (:machine-users (:account @app-state))]
        [:div {:style {:margin-top "2.5rem" :padding-top "1.5rem" :border-top "1px solid #eee"}}
         [:h2 {:style {:margin-top 0}} "Machine Users"]
         [:p {:style {:color "#666"}}
          "A machine user writes through the API on your behalf. It never logs in
           and has no password — its whole credential is a token. Tick the
           personas each one may write."]
         (when @token
           [token-box @token #(reset! token nil)])
         (if (seq machine-users)
           [:ul {:style {:list-style "none" :padding 0}}
            (for [m machine-users]
              ^{:key (:name m)}
              [machine-user-row m personas #(reset! token %)])]
           [:p {:style {:color "#666" :font-style "italic"}} "No machine users yet."])
         [:div {:style {:margin-top "1.5rem"}}
          [:h3 "Add a Machine User"]
          [machine-user-form #(reset! token %)]]]))))

(defn profile-tab []
  (let [removing (r/atom nil)
        toggle-error (r/atom nil)
        ;; The row is drawn from :account, so the answer has to come back from
        ;; the server before the badge changes — /api/me is where the flag lives.
        ;; The public list is refreshed too: in dev it is the login screen and
        ;; carries the badge as well.
        toggle-private! (fn [p]
                          (reset! toggle-error nil)
                          (update-persona (:id p) {:private (not (boolean (:private p)))}
                                          (fn []
                                            (fetch-me nil nil)
                                            (fetch-personas)
                                            (show-notification
                                             (if (:private p)
                                               (str (or (:name p) (:id p)) " is public again")
                                               (str (or (:name p) (:id p)) " is private now"))
                                             :success))
                                          #(reset! toggle-error %)))]
    (fn []
      (let [{:keys [account auth-user]} @app-state
            personas (:personas account)]
        [:div {:style {:padding "2rem" :max-width "640px"}}
         [:h2 {:style {:margin-top 0}} "Your Personas"]
         [:p {:style {:color "#666"}}
          "Signed in as " [:strong (:email account)] ". "
          (if (seq personas)
            "Nobody else can tell that these personas belong together."
            "An account is an email and a password; personas are what you make with it.")]
         (when (some :private personas)
           [:p {:style {:color "#5c4b8a" :font-size "0.9rem" :margin-top "-0.5rem"}}
            "A private persona is not listed anywhere and its address answers
             everyone but you as though there were no such persona. Your machine
             users still write the ones you granted them."])
         (when @toggle-error
           [:p {:style {:color "red" :font-size "0.9rem"}} @toggle-error])
         (when @removing
           [remove-dialog @removing #(reset! removing nil)])
         [:div {:style {:margin-bottom "2rem"}}
          (if (seq personas)
            [:ul {:style {:list-style "none" :padding 0}}
             (for [p personas]
               ^{:key (:id p)}
               [persona-row p
                (= (:id p) (:id auth-user))
                #(reset! removing %)
                toggle-private!])]
            ;; Where the list would be. This is where a new account lands, so it
            ;; reads as a starting point rather than as something having gone
            ;; wrong — the Add form below is the next thing on the page.
            [:p {:style {:color "#666" :font-style "italic"}}
             "No personas yet. Make your first one below."])]
         [:div
          [:h3 "Add a Persona"]
          [:p {:style {:color "#666" :font-size "0.9rem" :margin-top 0}}
           "The generated id is the public address it will be reached at."]
          [create-form]]
         [machine-users-section personas]]))))
