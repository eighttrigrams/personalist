(ns et.pe.ui.settings
  "The admin's Users tab. Since accounts sit above personas this lists
   *accounts* — an email, a password, and the personas underneath — where it
   used to list personas with an email each. The listing comes from
   GET /api/accounts, which is admin-only: it is the one read in the app that
   ties emails to personas in bulk, and the public persona list has no emails
   left to check a new one against."
  (:require [reagent.core :as r]
            [et.pe.ui.state :refer [app-state valid-email? fetch-accounts
                                    create-account update-persona]]
            [et.pe.ui.persona :refer [private-badge]]))

(defn- account-form
  "An account is an email and a password, and this form shows exactly those. It
   used to carry a display name and a generated urbit id too, because creating
   an account created its first persona in the same call; it does not any more —
   an account may hold none, and its owner makes personas on their own profile
   page."
  []
  (let [email-ref (atom nil)
        password-ref (atom nil)
        error (r/atom nil)]
    (fn []
      (let [accounts (:accounts @app-state)
            existing-emails (set (map :email accounts))]
        [:div {:style {:display "flex" :flex-direction "column" :gap "0.5rem" :max-width "300px"}}
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
                               (let [email-val (when @email-ref (.-value @email-ref))
                                     password-val (when @password-ref (.-value @password-ref))]
                                 (reset! error nil)
                                 (cond
                                   (not (seq email-val))
                                   (reset! error "Email is required")

                                   (not (valid-email? email-val))
                                   (reset! error "Invalid email format")

                                   (contains? existing-emails email-val)
                                   (reset! error "Email already exists")

                                   :else
                                   (create-account
                                    {:email email-val :password password-val}
                                    (fn [_]
                                      (when @email-ref (set! (.-value @email-ref) ""))
                                      (when @password-ref (set! (.-value @password-ref) "")))
                                    #(reset! error %)))))
                   :style {:padding "0.5rem 1rem" :cursor "pointer" :margin-top "0.5rem"}}
          "Add Account"]]))))

(defn- persona-row
  "One persona of an account. Only the display name is editable here: the email
   and the password belong to the account above it, and a persona's id is its
   address and never changes.

   Whether it is private is shown but not editable: admin is entitled to *read* a
   private persona, by the same authority that lets it edit another account's
   display name, and hiding them from this roster would only stop it finding the
   one it is allowed to open. Publishing somebody else's persona is a different
   act, and it belongs to the account that chose to hide it."
  [_p]
  (let [editing? (r/atom false)
        edit-display-name (r/atom nil)
        error (r/atom nil)]
    (fn [p]
      [:li {:style {:padding "0.4rem 0" :display "flex" :align-items "center" :gap "0.5rem"}}
       (if @editing?
         [:<>
          [:input {:type "text"
                   :value (or @edit-display-name (:name p) (:id p))
                   :on-change #(do (reset! edit-display-name (.. % -target -value))
                                   (reset! error nil))
                   :style {:padding "0.25rem" :flex 1}}]
          (when @error
            [:span {:style {:color "red" :font-size "0.85rem"}} @error])
          [:button {:on-click (fn []
                                (let [new-name (or @edit-display-name (:name p) (:id p))]
                                  (if (empty? new-name)
                                    (reset! error "Display name required")
                                    (update-persona (:id p) {:name new-name}
                                                    (fn []
                                                      (reset! editing? false)
                                                      (reset! error nil)
                                                      (fetch-accounts))
                                                    #(reset! error %)))))
                    :style {:padding "0.25rem 0.5rem" :cursor "pointer" :background "#4CAF50"
                            :color "white" :border "none" :border-radius "4px"}}
           "Save"]
          [:button {:on-click #(do (reset! editing? false)
                                   (reset! edit-display-name nil)
                                   (reset! error nil))
                    :style {:padding "0.25rem 0.5rem" :cursor "pointer"}}
           "Cancel"]]
         [:<>
          [:span {:style {:color "#888" :font-size "0.85rem" :font-family "monospace" :min-width "140px"}}
           (:id p)]
          [:span {:style {:flex 1}} (or (:name p) (:id p))]
          (when (:private p) private-badge)
          [:button {:on-click #(do (reset! edit-display-name (or (:name p) (:id p)))
                                   (reset! editing? true))
                    :style {:padding "0.25rem 0.5rem" :cursor "pointer"}}
           "Edit"]])])))

(defn- account-row [a]
  [:li {:style {:padding "0.75rem" :background "#f5f5f5" :margin-bottom "0.5rem" :border-radius "4px"}}
   [:strong (:email a)]
   (if (seq (:personas a))
     [:ul {:style {:list-style "none" :padding "0.25rem 0 0 0" :margin 0}}
      (for [p (:personas a)]
        ^{:key (:id p)}
        [persona-row p])]
     ;; An account may hold none, which is a normal state rather than a gap —
     ;; its owner makes personas on their own profile page.
     [:p {:style {:color "#666" :font-style "italic" :margin "0.25rem 0 0 0"}}
      "No personas yet."])])

(defn settings-tab []
  (r/create-class
   ;; The listing is admin-only, so it cannot come from the persona list every
   ;; visitor already has — it is fetched when the tab opens.
   {:component-did-mount fetch-accounts
    :reagent-render
    (fn []
      (let [accounts (:accounts @app-state)]
        [:div {:style {:padding "2rem" :max-width "600px"}}
         [:h2 "Users"]
         [:div {:style {:margin-bottom "2rem"}}
          [:h3 "Add New Account"]
          [account-form]]
         [:div
          [:h3 "Existing Accounts"]
          (if (seq accounts)
            [:ul {:style {:list-style "none" :padding 0}}
             (for [a accounts]
               ^{:key (:id a)}
               [account-row a])]
            [:p {:style {:color "#666" :font-style "italic"}} "No accounts yet."])]]))}))
