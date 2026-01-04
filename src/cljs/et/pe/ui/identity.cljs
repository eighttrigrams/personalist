(ns et.pe.ui.identity
  (:require [et.pe.ui.state :refer [app-state update-identity
                                    fetch-relations delete-relation select-identity
                                    update-url-with-time]]
            ["marked" :refer [marked]]))

(defn time-slider []
  (let [{:keys [identity-history slider-value selected-identity]} @app-state]
    (when (and selected-identity (seq identity-history))
      (let [history-count (count identity-history)
            current-entry (get identity-history slider-value)
            single-version? (= history-count 1)]
        [:div {:style {:margin-bottom "1rem" :padding "1rem" :background "#f5f5f5" :border-radius "4px"}}
         [:div {:style {:display "flex" :justify-content "space-between" :margin-bottom "0.5rem"}}
          (when-not single-version?
            [:span "Time Travel"])
          [:span (if single-version?
                   "Version 1"
                   (str "Version " (inc slider-value) " of " history-count))]]
         (when-not single-version?
           [:input {:type "range"
                    :min 0
                    :max (dec history-count)
                    :value slider-value
                    :on-change (fn [e]
                                 (let [new-val (js/parseInt (-> e .-target .-value))
                                       entry (get identity-history new-val)]
                                   (swap! app-state assoc
                                          :slider-value new-val
                                          :editing-name (:name entry)
                                          :editing-text (:text entry))))
                    :on-mouse-up (fn [_]
                                   (let [entry (get identity-history (:slider-value @app-state))]
                                     (when entry
                                       (fetch-relations (:identity selected-identity) (:valid-from entry))
                                       (update-url-with-time (:valid-from entry)))))
                    :style {:width "100%"}}])
         (when current-entry
           [:div {:style {:font-size "0.8rem" :color "#666" :margin-top "0.5rem"}}
            (cond
              single-version? "Created: "
              (zero? slider-value) "Created: "
              :else "Modified: ")
            (:valid-from current-entry)])]))))

(defn editor-tab-switcher []
  (let [{:keys [text-editor-mode]} @app-state]
    [:div {:style {:display "flex" :margin-bottom "0.5rem"}}
     [:button {:on-click #(swap! app-state assoc :text-editor-mode :edit)
               :style {:padding "0.5rem 1rem"
                       :cursor "pointer"
                       :background (if (= text-editor-mode :edit) "#4CAF50" "#e0e0e0")
                       :color (if (= text-editor-mode :edit) "white" "#333")
                       :border "none"
                       :border-radius "4px 0 0 4px"
                       :font-size "0.9rem"}}
      "Edit"]
     [:button {:on-click #(swap! app-state assoc :text-editor-mode :view)
               :style {:padding "0.5rem 1rem"
                       :cursor "pointer"
                       :background (if (= text-editor-mode :view) "#4CAF50" "#e0e0e0")
                       :color (if (= text-editor-mode :view) "white" "#333")
                       :border "none"
                       :border-radius "0 4px 4px 0"
                       :font-size "0.9rem"}}
      "View"]]))

(defn markdown-preview [text]
  [:div {:style {:width "100%"
                 :min-height "200px"
                 :padding "0.75rem"
                 :font-size "1rem"
                 :border "1px solid #ccc"
                 :border-radius "4px"
                 :background "#fafafa"
                 :overflow "auto"}
         :dangerouslySetInnerHTML {:__html (marked (or text ""))}}])

(defn relations-list []
  (let [{:keys [relations identities auth-user identity-history slider-value]} @app-state
        can-edit? (some? auth-user)
        current-entry (get identity-history slider-value)
        current-time (:valid-from current-entry)]
    [:div {:style {:margin-top "1.5rem" :padding-top "1rem" :border-top "1px solid #eee"}}
     [:h4 {:style {:margin 0 :margin-bottom "1rem"}} "Related Identities"]
     (if (seq relations)
       [:ul {:style {:list-style "none" :padding 0 :margin 0}}
        (for [rel relations]
          ^{:key (:id rel)}
          (let [target-identity (first (filter #(= (:identity %) (:target rel)) identities))]
            [:li {:style {:padding "0.5rem"
                          :background "#f5f5f5"
                          :border-radius "4px"
                          :margin-bottom "0.5rem"
                          :display "flex"
                          :justify-content "space-between"
                          :align-items "center"}}
             [:span {:on-click (fn []
                                 (when target-identity
                                   (select-identity target-identity current-time)))
                     :style {:cursor "pointer"}}
              [:span (or (:name target-identity) (:target rel))]]
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
  (let [{:keys [selected-identity editing-name editing-text auth-user text-editor-mode url-edit-mode]} @app-state
        can-edit? (and (some? auth-user) url-edit-mode)]
    (when selected-identity
      [:div {:style {:padding "2rem"
                     :max-width "800px"
                     :margin "0 auto"}}
       (when can-edit?
         [:div {:style {:display "flex"
                        :justify-content "flex-end"
                        :margin-bottom "1rem"}}
          [:button {:on-click #(update-identity (:identity selected-identity) editing-name editing-text)
                    :style {:padding "0.5rem 1rem"
                            :cursor "pointer"
                            :background "#4CAF50"
                            :color "white"
                            :border "none"
                            :border-radius "4px"}}
           "Save"]])
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
          [editor-tab-switcher]
          (if (= text-editor-mode :edit)
            [:textarea {:value editing-text
                        :on-change #(swap! app-state assoc :editing-text (-> % .-target .-value))
                        :style {:width "100%"
                                :height "200px"
                                :padding "0.75rem"
                                :font-size "1rem"
                                :font-family "monospace"
                                :border "1px solid #ccc"
                                :border-radius "4px"
                                :resize "vertical"}}]
            [markdown-preview editing-text])
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
          [:div {:style {:font-size "1.2rem"
                         :font-weight "bold"
                         :margin-bottom "0.5rem"}}
           editing-name]
          [:div {:style {:font-size "1rem"}
                 :dangerouslySetInnerHTML {:__html (marked (or editing-text ""))}}]])
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
      (let [recent-identities (:recent-identities @app-state)]
        [:div {:style {:display "flex"
                       :justify-content "center"
                       :align-items "flex-start"
                       :padding-top "15vh"
                       :min-height "calc(100vh - 60px)"
                       :color "#666"}}
         [:div {:style {:text-align "center" :width "100%" :max-width "400px"}}
          [:p {:style {:font-size "0.9rem" :color "#999" :margin-bottom "0.25rem"}} (:id current-user)]
          [:h1 {:style {:font-size "2rem" :margin-top "0" :margin-bottom "2rem" :color "#333"}} (:name current-user)]
          (if (seq recent-identities)
            [:<>
             [:div {:style {:text-align "left"}}
              (for [identity recent-identities]
                ^{:key (:identity identity)}
                [:div {:style {:padding "0.75rem 1rem"
                               :margin-bottom "0.5rem"
                               :background "#f5f5f5"
                               :border-radius "4px"
                               :cursor "pointer"
                               :transition "background 0.2s"}
                       :on-click #(select-identity identity)
                       :on-mouse-over #(set! (.-background (.-style (.-currentTarget %))) "#e0e0e0")
                       :on-mouse-out #(set! (.-background (.-style (.-currentTarget %))) "#f5f5f5")}
                 [:div {:style {:font-weight "500" :pointer-events "none"}} (:name identity)]
                 [:div {:style {:font-size "0.85rem" :color "#999" :margin-top "0.25rem" :pointer-events "none"}}
                  (let [text (:text identity)]
                    (if (> (count text) 60)
                      (str (subs text 0 60) "...")
                      text))]])]]
            [:<>
             [:p {:style {:font-size "1.2rem" :margin-bottom "1rem"}} "No identity selected"]
             [:p {:style {:color "#999"}} "Use the search button to browse identities"]])]])

      :else
      [:div {:style {:min-height "calc(100vh - 60px)"}}
       [identity-editor]])))
