(ns et.pe.ui.identity
  (:require [et.pe.ui.state :refer [app-state update-identity fetch-identity-at
                                    fetch-relations delete-relation select-identity
                                    set-editing-mode]]
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
                    :on-change #(swap! app-state assoc :slider-value (js/parseInt (-> % .-target .-value)))
                    :on-mouse-up (fn [_]
                                   (let [entry (get identity-history (:slider-value @app-state))]
                                     (when entry
                                       (fetch-identity-at (:identity selected-identity) (:valid-from entry))
                                       (fetch-relations (:identity selected-identity) (:valid-from entry)))))
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
  (let [{:keys [relations identities auth-user]} @app-state
        can-edit? (some? auth-user)]
    [:div {:style {:margin-top "1.5rem" :padding-top "1rem" :border-top "1px solid #eee"}}
     [:h4 {:style {:margin 0 :margin-bottom "1rem"}} "Related Identities"]
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
              [:span (or (:name source-identity) (:source rel))]]
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
  (let [{:keys [selected-identity editing-name editing-text auth-user text-editor-mode]} @app-state
        can-edit? (some? auth-user)]
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
                   :on-focus #(set-editing-mode true)
                   :on-blur #(set-editing-mode false)
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
                        :on-focus #(set-editing-mode true)
                        :on-blur #(set-editing-mode false)
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
      [:div {:style {:display "flex"
                     :justify-content "center"
                     :align-items "center"
                     :min-height "calc(100vh - 60px)"
                     :color "#666"}}
       [:div {:style {:text-align "center"}}
        [:p {:style {:font-size "1.2rem" :margin-bottom "1rem"}} "No identity selected"]
        [:p {:style {:color "#999"}} "Use the search button to browse identities"]]]

      :else
      [:div {:style {:min-height "calc(100vh - 60px)"}}
       [identity-editor]])))
