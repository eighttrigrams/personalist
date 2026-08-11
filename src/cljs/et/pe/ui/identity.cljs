(ns et.pe.ui.identity
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [et.pe.ui.codemirror :as codemirror]
            [et.pe.ui.provenance :as provenance]
            [et.pe.ui.state :refer [app-state update-identity
                                    fetch-relations delete-relation select-identity
                                    update-url-with-time fetch-more-recent-identities
                                    own-persona? toggle-provenance]]
            ["marked" :refer [marked]]))

(defn fixed-version-indicator []
  ;; In fixed mode there is no picker; instead show a non-interactive, light-gray
  ;; note of which version/timestamp of this identity is in view at the slice.
  (let [{:keys [identity-history slider-value selected-identity fixed-mode?]} @app-state]
    (when (and fixed-mode? selected-identity (seq identity-history))
      (let [history-count (count identity-history)
            current-entry (get identity-history slider-value)]
        [:div {:style {:font-size "0.8rem" :color "#bbb" :margin-bottom "0.5rem"}}
         (str "Version " (inc slider-value) " of " history-count
              (when current-entry (str " · " (:valid-from current-entry))))]))))

(defn time-slider []
  (let [{:keys [identity-history slider-value selected-identity fixed-mode?]} @app-state]
    ;; in fixed mode we stay in one time-slice, so no picker — see fixed-version-indicator
    (when (and selected-identity (seq identity-history) (not fixed-mode?))
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
         :dangerouslySetInnerHTML (r/unsafe-html (marked (or text "")))}])

;; ---------------------------------------------------------------------------
;; Provenance — who wrote which lines
;;
;; Offered only to the account that holds the persona, and *not* rendered at all
;; for anyone else: no greyed-out button, no empty panel. A control a visitor
;; cannot use is a statement that there is something here to be let in on, and
;; the whole point of the guarded read is that there is nothing to be learned
;; about an account's machine users from outside it.
;;
;; What it draws is the text **as it stands now**, line by line, whatever the
;; version slider is showing: the ranges answer about the newest version, and
;; tinting an older text with them would attribute its lines to whoever wrote
;; the version that displaced them. So the panel carries its own copy of the
;; lines it is about, and says so in one line above them.
;; ---------------------------------------------------------------------------

(defn- provenance-toggle [showing?]
  [:button {:on-click toggle-provenance
            :title "Who wrote which lines of this text"
            :style {:padding "0.5rem 1rem"
                    :cursor "pointer"
                    :background (if showing? "#555" "#e0e0e0")
                    :color (if showing? "white" "#333")
                    :border "none"
                    :border-radius "4px"
                    :font-size "0.9rem"}}
   (if showing? "Hide provenance" "Show provenance")])

(defn- provenance-line
  "One line of the text: its number, a bar coloured by how careful an agent
   should be in it, and the line exactly as it is stored — its source rather
   than its rendering, because the numbering the answer uses is the source's."
  [n line caution]
  [:div {:style {:display "flex" :align-items "flex-start" :gap "0.5rem"
                 :padding "0.1rem 0"}
         :title (if (number? caution)
                  (str "caution " (.toFixed caution 2))
                  "no provenance for this line")}
   [:span {:style {:min-width "2.5rem" :text-align "right" :color "#bbb"
                   :font-family "monospace" :font-size "0.8rem" :user-select "none"}}
    n]
   [:span {:style {:width "4px" :align-self "stretch" :border-radius "2px"
                   :background (or (provenance/tint caution) "transparent")
                   :border (when-not (number? caution) "1px dashed #ddd")
                   :flex "0 0 auto"}}]
   [:span {:style {:font-family "monospace" :font-size "0.85rem"
                   :white-space "pre-wrap" :word-break "break-word"}}
    line]])

(defn provenance-panel
  "The spectrum over the text, under the legend the server sent with it.

   The legend is drawn rather than paraphrased. `1.00` and `0.00` are unreadable
   to anyone who has not read this codebase, and the words that make them
   readable are the API's own — the same sentence an agent fetching this
   identity is handed, so the two readers are told the same thing in the same
   vocabulary."
  []
  (let [{:keys [provenance identity-history slider-value]} @app-state
        {:keys [legend ranges versions]} provenance
        ;; the newest version's text, which is what the ranges are about
        text (:text (last identity-history))
        lines (provenance/split-lines text)
        cautions (provenance/line-cautions ranges (count lines))
        ;; ...which is not what the editor above is showing while the slider is
        ;; back in the history. Said out loud rather than left to be noticed: two
        ;; texts on one screen, and only one of them is the one being attributed.
        time-travelling? (and (seq identity-history)
                              (< slider-value (dec (count identity-history))))]
    (when provenance
      [:div {:style {:margin-top "1rem" :padding "1rem"
                     :background "#fafafa" :border "1px solid #eee" :border-radius "4px"}}
       [:div {:style {:font-size "0.8rem" :color "#666" :margin-bottom "0.75rem"}}
        legend]
       [:div {:style {:font-size "0.75rem" :color "#999" :margin-bottom "0.75rem"}}
        (str "The text as it stands now, in "
             (count versions)
             (if (= 1 (count versions)) " version, written by " " versions, written by ")
             (->> versions (map :author) distinct sort (str/join ", "))
             "."
             (when time-travelling?
               (str " You are looking at version " (inc slider-value) " of "
                    (count identity-history) " above; this is the latest.")))]
       [:div
        (doall
         (map-indexed (fn [i line]
                        ^{:key i} [provenance-line (inc i) line (nth cautions i nil)])
                      lines))]])))

(defn relations-list []
  (let [{:keys [relations auth-user url-edit-mode identity-history slider-value
                pending-relation-adds pending-relation-removes]} @app-state
        edit? (and (some? auth-user) url-edit-mode)
        current-entry (get identity-history slider-value)
        current-time (:valid-from current-entry)
        ;; in edit mode the list previews the not-yet-saved state
        effective (if edit?
                    (concat (remove #(contains? pending-relation-removes (:id %)) relations)
                            pending-relation-adds)
                    relations)]
    [:div {:style {:margin-top "1.5rem" :padding-top "1rem" :border-top "1px solid #eee"}}
     [:h4 {:style {:margin 0 :margin-bottom "1rem"}} "Related Identities"]
     (if (seq effective)
       [:ul {:style {:list-style "none" :padding 0 :margin 0}}
        (for [rel effective]
          ^{:key (:id rel)}
          [:li {:style {:padding "0.5rem"
                        :background (if (:pending rel) "#fff8e1" "#f5f5f5")
                        :border (if (:pending rel) "1px dashed #ffb300" "1px solid transparent")
                        :border-radius "4px"
                        :margin-bottom "0.5rem"
                        :display "flex"
                        :justify-content "space-between"
                        :align-items "center"}}
           [:span {:on-click (fn []
                               (select-identity {:identity (:target rel) :name (:target-name rel)} current-time))
                   :style {:cursor "pointer"}}
            [:span (or (:target-name rel) (name (:target rel)))]
            (when (:pending rel)
              [:span {:style {:margin-left "0.5rem" :font-size "0.75rem" :color "#b28704" :font-style "italic"}}
               "unsaved"])]
           (when edit?
             [:button {:on-click #(delete-relation (:id rel))
                       :style {:padding "0.25rem 0.5rem"
                               :cursor "pointer"
                               :background "#ff5252"
                               :color "white"
                               :border "none"
                               :border-radius "4px"
                               :font-size "0.8rem"}}
              "X"])])]
       [:p {:style {:color "#666" :font-style "italic" :margin 0}} "No Relations for this Identity at this point in time."])]))

(defn identity-editor []
  (let [{:keys [selected-identity editing-name editing-text auth-user text-editor-mode
                url-edit-mode showing-provenance?]} @app-state
        can-edit? (and (some? auth-user) url-edit-mode)
        ;; Not `can-edit?`: a machine user may write a persona and this is not for
        ;; it, and the account may be looking at its own persona without having
        ;; entered edit mode. Ownership is the question, and the server asks it
        ;; again — this only decides whether to offer the control at all.
        own? (own-persona?)]
    (when selected-identity
      [:div {:style {:padding "2rem"
                     :max-width "800px"
                     :margin "0 auto"}}
       (when (or can-edit? own?)
         [:div {:style {:display "flex"
                        :justify-content "flex-end"
                        :gap "0.5rem"
                        :margin-bottom "1rem"}}
          (when own?
            [provenance-toggle showing-provenance?])
          (when can-edit?
            [:button {:on-click #(update-identity (:identity selected-identity) editing-name editing-text)
                      :style {:padding "0.5rem 1rem"
                              :cursor "pointer"
                              :background "#4CAF50"
                              :color "white"
                              :border "none"
                              :border-radius "4px"}}
             "Save"])])
       [time-slider]
       [fixed-version-indicator]
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
            ;; A CodeMirror with Daniel's IJKL bindings rather than a textarea.
            ;; The height and the monospace are the textarea's, so the switch to
            ;; preview and back does not move anything on the page. What is lost
            ;; is the drag handle: CodeMirror does not resize.
            [codemirror/editor {:value editing-text
                                :on-change #(swap! app-state assoc :editing-text %)
                                :height "200px"}]
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
                 :dangerouslySetInnerHTML (r/unsafe-html (marked (or editing-text "")))}]])
       ;; Below the text in both modes, and describing the stored text in both:
       ;; the answer is about the version that was saved, so an editor's unsaved
       ;; draft is not what it is tinting. Saving refetches it (state/update-identity).
       (when (and own? showing-provenance?)
         [provenance-panel])
       [relations-list]])))

(defn main-tab []
  (let [{:keys [current-user selected-identity not-found-persona not-found-identity]} @app-state]
    (cond
      not-found-persona
      [:div {:style {:display "flex"
                     :justify-content "center"
                     :align-items "center"
                     :min-height "calc(100vh - 60px)"
                     :color "#666"}}
       [:div {:style {:text-align "center"}}
        [:h2 {:style {:color "#333" :margin-bottom "1rem"}} "Persona not found"]
        [:p {:style {:font-size "1rem" :margin-bottom "1.5rem"}}
         (str "The persona \"" not-found-persona "\" does not exist.")]
        [:button {:on-click #(do (swap! app-state assoc :not-found-persona nil)
                                 (.pushState js/history nil "" "/"))
                  :style {:padding "0.75rem 1.5rem"
                          :cursor "pointer"
                          :background "#4CAF50"
                          :color "white"
                          :border "none"
                          :border-radius "4px"
                          :font-size "1rem"}}
         "Go Home"]]]

      not-found-identity
      [:div {:style {:display "flex"
                     :justify-content "center"
                     :align-items "center"
                     :min-height "calc(100vh - 60px)"
                     :color "#666"}}
       [:div {:style {:text-align "center"}}
        [:h2 {:style {:color "#333" :margin-bottom "1rem"}} "Identity not found"]
        [:p {:style {:font-size "1rem" :margin-bottom "1.5rem"}}
         (str "The identity \"" not-found-identity "\" does not exist.")]
        [:button {:on-click #(do (swap! app-state assoc :not-found-identity nil :selected-identity nil)
                                 (.pushState js/history nil "" (str "/" (:id current-user))))
                  :style {:padding "0.75rem 1.5rem"
                          :cursor "pointer"
                          :background "#4CAF50"
                          :color "white"
                          :border "none"
                          :border-radius "4px"
                          :font-size "1rem"}}
         "Back to Persona"]]]

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
      (let [recent-identities (:recent-identities @app-state)
            offset (:recent-identities-offset @app-state)
            has-more (:recent-identities-has-more @app-state)]
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
                      text))]])]
             (when has-more
               [:button {:style {:background "transparent"
                                 :border "none"
                                 :color "#4a90d9"
                                 :font-size "0.85rem"
                                 :cursor "pointer"
                                 :padding "0.5rem"
                                 :margin-top "0.25rem"}
                         :on-click #(fetch-more-recent-identities (:id current-user) (+ offset 5))}
                "more"])]
            [:<>
             [:p {:style {:font-size "1.2rem" :margin-bottom "1rem"}} "No identity selected"]
             [:p {:style {:color "#999"}} "Use the search button to browse identities"]])]])

      :else
      [:div {:style {:min-height "calc(100vh - 60px)"}}
       [identity-editor]])))
