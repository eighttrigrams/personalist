(ns et.pe.ui.modals
  (:require [et.pe.ui.state :refer [app-state select-persona try-login
                                    attempt-login attempt-email-login
                                    search-identities select-identity
                                    add-relation add-identity]]))

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
             ^{:key (:id p)}
             (when (not= (:id p) "admin")
               [:li {:on-click #(select-persona p)
                     :style {:padding "0.75rem"
                             :cursor "pointer"
                             :background "#f5f5f5"
                             :border-radius "4px"
                             :margin-bottom "0.5rem"
                             :transition "background 0.2s"}
                     :on-mouse-over #(set! (.-background (.-style (.-target %))) "#e0e0e0")
                     :on-mouse-out #(set! (.-background (.-style (.-target %))) "#f5f5f5")}
                [:div {:style {:display "flex" :justify-content "space-between" :align-items "center"}}
                 [:strong (or (:name p) (:id p))]
                 [:span {:style {:color "#888" :font-size "0.85rem" :font-family "monospace"}} (:id p)]]]))]
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
           [:input {:type "text"
                    :value login-email
                    :placeholder "Email or Persona ID"
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
                ^{:key (:id p)}
                [:li {:on-click #(try-login p)
                      :style {:padding "0.75rem"
                              :cursor "pointer"
                              :background "#f5f5f5"
                              :border-radius "4px"
                              :margin-bottom "0.5rem"
                              :transition "background 0.2s"}
                      :on-mouse-over #(set! (.-background (.-style (.-target %))) "#e0e0e0")
                      :on-mouse-out #(set! (.-background (.-style (.-target %))) "#f5f5f5")}
                 [:div {:style {:display "flex" :justify-content "space-between" :align-items "center"}}
                  [:strong (or (:name p) (:id p))]
                  [:span {:style {:color "#888" :font-size "0.85rem" :font-family "monospace"}} (:id p)]]])]
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
        [:h2 {:style {:margin-top 0}} (str "Login as " (or (:name login-persona) (:id login-persona)))]
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
                       #(swap! app-state assoc :nav-search-results (take 5 %)))))

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
        (let [results-to-show (if (seq nav-search-query)
                              nav-search-results
                              (take 5 identities))]
          (if (seq results-to-show)
            [:ul {:style {:list-style "none" :padding 0 :margin 0}}
             (for [result results-to-show]
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
              [:p {:style {:color "#666" :font-style "italic"}} "No results found"])))
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
                                  (search-identities q #(swap! app-state assoc :relation-search-results (take 5 %))))))
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

(defn- localhost? []
  (= (.-hostname js/window.location) "localhost"))

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
        (if (localhost?)
          [:<>
           [:br]
           [:a {:href "https://github.com/eighttrigrams/personalist/blob/main/DEMO.md"
                :target "_blank"
                :style {:display "inline-block"
                        :padding "0.5rem 1rem"
                        :color "#666"
                        :text-decoration "none"
                        :font-size "0.9rem"}}
            "View Demo Guide"]]
          [:<>
           [:br]
           [:p {:style {:color "#999" :font-size "0.75rem" :margin-top "1rem" :margin-bottom "0" :max-width "280px" :margin-left "auto" :margin-right "auto" :line-height "1.4"}}
            "Ask an admin for your account!"]])
        [:div {:style {:margin-top "1rem"}}
         [:button {:on-click #(swap! app-state assoc :show-beta-modal false)
                   :style {:padding "0.5rem 1rem"
                           :cursor "pointer"
                           :background "#eee"
                           :border "none"
                           :border-radius "4px"}}
          "Close"]]]])))
