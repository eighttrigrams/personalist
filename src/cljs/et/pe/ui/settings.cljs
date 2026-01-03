(ns et.pe.ui.settings
  (:require [reagent.core :as r]
            [ajax.core :refer [POST]]
            [et.pe.ui.state :refer [app-state api-base valid-email?
                                    fetch-personas update-persona]]))

(defn- persona-form []
  (let [name-ref (atom nil)
        display-name-ref (atom nil)
        email-ref (atom nil)
        password-ref (atom nil)
        error (r/atom nil)]
    (fn []
      (let [personas (:personas @app-state)
            existing-emails (set (map :email personas))]
        [:div {:style {:display "flex" :flex-direction "column" :gap "0.5rem" :max-width "300px"}}
         [:input {:type "text"
                  :placeholder "Username (internal ID)"
                  :ref #(reset! name-ref %)
                  :style {:padding "0.5rem"}}]
         [:input {:type "text"
                  :placeholder "Display Name"
                  :ref #(reset! display-name-ref %)
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
                                     display-name-val (when @display-name-ref (.-value @display-name-ref))
                                     email-val (when @email-ref (.-value @email-ref))
                                     password-val (when @password-ref (.-value @password-ref))]
                                 (reset! error nil)
                                 (cond
                                   (not (seq name-val))
                                   (reset! error "Username is required")

                                   (not (seq email-val))
                                   (reset! error "Email is required")

                                   (not (valid-email? email-val))
                                   (reset! error "Invalid email format")

                                   (contains? existing-emails email-val)
                                   (reset! error "Email already exists")

                                   :else
                                   (POST (str api-base "/api/personas")
                                     {:params {:id name-val
                                               :email email-val
                                               :password password-val
                                               :name (if (seq display-name-val) display-name-val name-val)}
                                      :format :json
                                      :handler (fn [_]
                                                 (when @name-ref (set! (.-value @name-ref) ""))
                                                 (when @display-name-ref (set! (.-value @display-name-ref) ""))
                                                 (when @email-ref (set! (.-value @email-ref) ""))
                                                 (when @password-ref (set! (.-value @password-ref) ""))
                                                 (fetch-personas))
                                      :error-handler #(js/console.error "Error adding persona" %)}))))
                   :style {:padding "0.5rem 1rem" :cursor "pointer" :margin-top "0.5rem"}}
          "Add Persona"]]))))

(defn- persona-row [p]
  (let [editing? (r/atom false)
        edit-display-name (r/atom (or (:name p) (:id p)))
        edit-email (r/atom (:email p))
        error (r/atom nil)]
    (fn [p]
      (let [personas (:personas @app-state)
            other-emails (set (map :email (filter #(not= (:id %) (:id p)) personas)))]
        [:li {:style {:padding "0.75rem" :background "#f5f5f5" :margin-bottom "0.5rem" :border-radius "4px"}}
         (if @editing?
           [:div {:style {:display "flex" :flex-direction "column" :gap "0.5rem"}}
            [:div {:style {:display "flex" :align-items "center" :gap "0.5rem"}}
             [:label {:style {:min-width "80px" :font-size "0.85rem"}} "Display:"]
             [:input {:type "text"
                      :value @edit-display-name
                      :on-change #(do (reset! edit-display-name (.. % -target -value))
                                      (reset! error nil))
                      :style {:padding "0.25rem" :flex 1}}]]
            [:div {:style {:display "flex" :align-items "center" :gap "0.5rem"}}
             [:label {:style {:min-width "80px" :font-size "0.85rem"}} "Email:"]
             [:input {:type "email"
                      :value @edit-email
                      :on-change #(do (reset! edit-email (.. % -target -value))
                                      (reset! error nil))
                      :style {:padding "0.25rem" :flex 1}}]]
            (when @error
              [:div {:style {:color "red" :font-size "0.85rem"}} @error])
            [:div {:style {:display "flex" :gap "0.5rem" :margin-top "0.25rem"}}
             [:button {:on-click (fn []
                                   (let [new-email @edit-email
                                         new-display-name @edit-display-name]
                                     (cond
                                       (empty? new-display-name)
                                       (reset! error "Display name required")

                                       (not (valid-email? new-email))
                                       (reset! error "Invalid email format")

                                       (contains? other-emails new-email)
                                       (reset! error "Email already exists")

                                       :else
                                       (update-persona
                                        (:id p)
                                        {:email new-email :name new-display-name}
                                        (fn []
                                          (reset! editing? false)
                                          (reset! error nil)
                                          (fetch-personas))
                                        (fn [err]
                                          (reset! error err))))))
                       :style {:padding "0.25rem 0.5rem" :cursor "pointer" :background "#4CAF50" :color "white" :border "none" :border-radius "4px"}}
              "Save"]
             [:button {:on-click #(do (reset! editing? false)
                                      (reset! edit-display-name (or (:name p) (:id p)))
                                      (reset! edit-email (:email p))
                                      (reset! error nil))
                       :style {:padding "0.25rem 0.5rem" :cursor "pointer"}}
              "Cancel"]]]
           [:div {:style {:display "flex" :align-items "center" :gap "0.5rem"}}
            [:strong {:style {:min-width "100px"}} (or (:name p) (:id p))]
            [:span {:style {:flex 1 :color "#666"}} (:email p)]
            (when (not= (:id p) "admin")
              [:button {:on-click #(reset! editing? true)
                        :style {:padding "0.25rem 0.5rem" :cursor "pointer"}}
               "Edit"])])]))))

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
           ^{:key (:id p)}
           [persona-row p])]
        [:p {:style {:color "#666" :font-style "italic"}} "No personas yet."])]]))
