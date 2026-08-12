(ns et.pe.ui.modals
  (:require [reagent.core :as r]
            [et.pe.ui.codemirror :as codemirror]
            [et.pe.ui.state :refer [app-state select-persona try-login
                                    attempt-login
                                    search-identities select-identity
                                    already-related? offerable-as-relation?
                                    add-relation add-identity]]
            [et.pe.ui.persona :refer [private-badge]]))

;; ---------------------------------------------------------------------------
;; The sheet every modal sits on
;;
;; Six modals had this same block of inline style copied into them, and with it
;; the same defect: the sheet covers the page, so no *click* can reach what is
;; behind it, but a **wheel** over the sheet scrolled the page behind a dialog
;; that is supposed to be modal. Fixed once here rather than six times.
;;
;; The fix is the page lock below and not `overscroll-behavior` on the sheet.
;; That was tried first, being pure CSS, and measured: the page still scrolled
;; its full 247px under the modal. Containment only binds a scroll container
;; that can actually scroll, so a sheet whose content fits is skipped and the
;; wheel goes to the document — and "the sheet happens to be taller than the
;; window" is not a thing to make modality depend on. The declaration stays on
;; the sheet anyway: when it *is* the scroller, it should keep the overscroll at
;; its own ends too.
;;
;; **`overflow: hidden` on the root, and nothing else.** The document stops being
;; scrollable and keeps the position it was at. The first version of this took the
;; body out of flow and offset it by the scroll position, on the belief that hiding
;; the overflow forces a scroller back to the top; that belief was wrong and the
;; measurement behind it was bad — the test harness had scrolled the page to 0
;; before the lock ever ran (Playwright's `click()` scrolls its target into view,
;; and the button that opens a modal is up in the header). Cookbook is where it
;; showed: the same trick there put an html element with no in-flow content
;; underneath a surface, which stops the body's background reaching the canvas, and
;; the band above that app's overlay went white.
;; ---------------------------------------------------------------------------

;; How many sheets are up. Nothing forbids two — the flags that open modals are
;; independent — so the page is locked by the first and handed back only by the
;; last, rather than by whichever closes first.
(defonce ^:private open-sheets (atom 0))

(defn- lock-page! []
  (when (= 1 (swap! open-sheets inc))
    (let [style (.. js/document -documentElement -style)
          ;; What the scrollbar was taking, paid back as padding so the page does
          ;; not shift sideways underneath as the modal opens.
          gap (- (.-innerWidth js/window)
                 (.. js/document -documentElement -clientWidth))]
      (set! (.-paddingRight style) (str gap "px"))
      (set! (.-overflow style) "hidden"))))

(defn- unlock-page! []
  (when (zero? (swap! open-sheets dec))
    (let [style (.. js/document -documentElement -style)]
      (set! (.-overflow style) "")
      (set! (.-paddingRight style) ""))))

(def ^:private sheet-style
  "The dim sheet. Also its own scroll container, which is what lets a modal
   taller than a short window be scrolled inside the sheet instead of running off
   the top of it."
  {:position "fixed"
   :top 0
   :left 0
   :right 0
   :bottom 0
   :background "rgba(0,0,0,0.5)"
   :display "flex"
   :justify-content "center"
   :z-index 1000
   :padding "2rem"
   :box-sizing "border-box"
   :overflow-y "auto"
   :overscroll-behavior "contain"})

(defn- focus-quietly
  "Focus a field on mount **without scrolling to it**, as `:ref`.

   `:auto-focus` cannot be used inside a sheet: the browser reveals the focused
   field by scrolling, and a field in a fixed sheet is already in view, so what
   moves instead is the page behind — it jumps to the top as the modal opens. It
   was doing that before the page lock went in, and with the lock the sheet then
   holds the jumped-to position for as long as it is up, which is how the lock
   came to be blamed for it.

   A named function rather than an inline one so its identity is stable across
   renders, or React would detach and re-attach the ref — and so re-focus — on
   every keystroke."
  [^js el]
  (when (and el (not= el (.-activeElement js/document)))
    (.focus el #js {:preventScroll true})))

(def ^:private box-style
  "The white box. Centred by `margin: auto` rather than by the sheet's
   `align-items: center`: a centred flex child that grows taller than its
   container overflows in both directions and its top cannot be scrolled back
   to, which is the one thing a modal holding an editor must not do."
  {:background "white"
   :padding "2rem"
   :border-radius "8px"
   :margin "auto"})

(def ^:private overlay
  "A modal: the sheet, and `children` in the box on it. While it is mounted the
   page behind is locked, so the only things that reach the background are the
   modal's own buttons.

   `:on-dismiss` is what a click on the *sheet* does. Leaving it out is a
   decision and not an oversight — a modal that holds something you are writing
   is left by its own buttons, so that a click landing beside it cannot take the
   draft with it. Clicks inside the box never reach the sheet either way.

   A `def` and not a `defn`: `r/create-class` has to be evaluated once, or every
   render would hand reagent a brand-new class, and a remounted sheet would
   re-lock a page it never unlocked and take the editor's caret with it."
  (r/create-class
   {:display-name "modal-sheet"
    :component-did-mount (fn [_] (lock-page!))
    :component-will-unmount (fn [_] (unlock-page!))
    :reagent-render
    (fn [{:keys [on-dismiss box]} & children]
      [:div (cond-> {:style sheet-style}
              on-dismiss (assoc :on-click on-dismiss))
       (into [:div {:style (merge box-style box)
                    :on-click #(.stopPropagation %)}]
             children)])}))

(defn login-modal []
  (let [{:keys [personas show-login-modal]} @app-state
        explorable-personas (remove #(= (:id %) "admin") personas)]
    (when show-login-modal
      [overlay {:on-dismiss #(swap! app-state assoc :show-login-modal false)
                :box {:min-width "300px" :max-width "400px"}}
       [:<>
        [:h2 {:style {:margin-top 0}} "Select Persona"]
        [:p {:style {:color "#666"}} "Choose a persona and explore their worldview:"]
        (if (seq explorable-personas)
          [:ul {:style {:list-style "none" :padding 0 :margin 0}}
           (for [p explorable-personas]
             ^{:key (:id p)}
             [:li {:on-click #(select-persona p)
                   :style {:padding "0.75rem"
                           :cursor "pointer"
                           :background "#f5f5f5"
                           :border-radius "4px"
                           :margin-bottom "0.5rem"
                           :transition "background 0.2s"}
                   :on-mouse-over #(set! (.-background (.-style (.-currentTarget %))) "#e0e0e0")
                   :on-mouse-out #(set! (.-background (.-style (.-currentTarget %))) "#f5f5f5")}
              [:div {:style {:display "flex" :justify-content "space-between" :align-items "center" :gap "0.5rem" :pointer-events "none"}}
               [:strong (or (:name p) (:id p))]
               (when (:private p) private-badge)
               [:span {:style {:color "#888" :font-size "0.85rem" :font-family "monospace"}} (:id p)]]])]
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
      [overlay {:on-dismiss #(swap! app-state assoc :show-auth-modal false :login-email "" :login-password "" :login-error nil)
                :box {:min-width "300px" :max-width "400px"}}
       [:<>
        [:h2 {:style {:margin-top 0}} "Login"]
        (if password-required
          [:<>
           [:p {:style {:color "#666"}} "Enter your email and password:"]
           [:input {:type "text"
                    :value login-email
                    :placeholder "Email"
                    :on-change #(swap! app-state assoc :login-email (.. % -target -value))
                    :on-key-down #(when (= (.-key %) "Enter") (attempt-login))
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
            [:button {:on-click #(swap! app-state assoc :show-auth-modal false :login-email "" :login-password "" :login-error nil)
                      :style {:padding "0.5rem 1rem"
                              :cursor "pointer"}}
             "Cancel"]]
           [:p {:style {:color "#ccc"
                        :font-size "0.75rem"
                        :font-style "italic"
                        :margin-top "1.5rem"
                        :margin-bottom "0"}}
            "Friend of the project? Ask your contact to set up an account for you."]]
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
                      :on-mouse-over #(set! (.-background (.-style (.-currentTarget %))) "#e0e0e0")
                      :on-mouse-out #(set! (.-background (.-style (.-currentTarget %))) "#f5f5f5")}
                 [:div {:style {:display "flex" :justify-content "space-between" :align-items "center" :gap "0.5rem" :pointer-events "none"}}
                  [:strong (or (:name p) (:id p))]
                  (when (:private p) private-badge)
                  [:span {:style {:color "#888" :font-size "0.85rem" :font-family "monospace"}} (:id p)]]])]
             [:p {:style {:color "#666" :font-style "italic"}}
              "No personas yet."])
           [:button {:on-click #(swap! app-state assoc :show-auth-modal false)
                     :style {:margin-top "1rem"
                             :padding "0.5rem 1rem"
                             :cursor "pointer"}}
            "Cancel"]])]])))

(defn- date-to-instant [date-str]
  (when (seq date-str)
    (str date-str "T23:59:59Z")))

(defn- do-search [query valid-at]
  (when (or (>= (count query) 1) (seq valid-at))
    (search-identities query
                       (date-to-instant valid-at)
                       #(swap! app-state assoc :nav-search-results (take 5 %)))))

(defn search-modal []
  (let [{:keys [show-search-modal nav-search-query nav-search-results search-valid-at recent-identities]} @app-state]
    (when show-search-modal
      [overlay {:on-dismiss #(swap! app-state assoc :show-search-modal false :nav-search-query "" :nav-search-results [] :search-valid-at nil)
                :box {:min-width "400px" :max-width "500px"
                      ;; its own scroller as well: a long result list is the
                      ;; box's overflow, not the sheet's
                      :max-height "80vh" :overflow-y "auto"
                      :overscroll-behavior "contain"}}
       [:<>
        [:h2 {:style {:margin-top 0}} "Search Identities"]
        [:div {:style {:display "flex" :gap "0.5rem" :margin-bottom "1rem"}}
         [:input {:type "text"
                  :placeholder "Search by name..."
                  :value nav-search-query
                  :ref focus-quietly
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
        (let [has-date-filter? (some? search-valid-at)
              results-to-show (if (or (seq nav-search-query) has-date-filter?)
                              nav-search-results
                              recent-identities)]
          (if (seq results-to-show)
            [:ul {:style {:list-style "none" :padding 0 :margin 0}}
             (for [result results-to-show]
               ^{:key (:identity result)}
               [:li {:on-click (fn []
                                 (let [logged-in? (some? (:auth-user @app-state))
                                       instant (date-to-instant search-valid-at)]
                                   ;; a non-logged-in user who scoped by a date enters the
                                   ;; single-time-slice "fixed" mode
                                   (when (and (not logged-in?) instant)
                                     (swap! app-state assoc :fixed-mode? true :fixed-time instant))
                                   (select-identity result instant))
                                 (swap! app-state assoc :show-search-modal false :nav-search-query "" :nav-search-results [] :search-valid-at nil))
                     :style {:padding "0.75rem"
                             :cursor "pointer"
                             :background "#f5f5f5"
                             :border-radius "4px"
                             :margin-bottom "0.5rem"
                             :transition "background 0.2s"}
                     :on-mouse-over #(set! (.-background (.-style (.-currentTarget %))) "#e0e0e0")
                     :on-mouse-out #(set! (.-background (.-style (.-currentTarget %))) "#f5f5f5")}
                [:span {:style {:pointer-events "none"}} (:name result)]])]
            (when (or (seq nav-search-query) has-date-filter?)
              [:p {:style {:color "#666" :font-style "italic"}} "No results found"])))
        [:button {:on-click #(swap! app-state assoc :show-search-modal false :nav-search-query "" :nav-search-results [] :search-valid-at nil)
                  :style {:margin-top "1rem"
                          :padding "0.5rem 1rem"
                          :cursor "pointer"}}
         "Cancel"]]])))

(defn add-relation-modal []
  (let [{:keys [show-add-relation-modal relation-search-query relation-search-results
                selected-identity] :as state} @app-state
        ;; What is left to offer. Filtered *before* the list is cut down to five,
        ;; so the five slots go to identities that can actually be picked rather
        ;; than being spent on ones already related.
        candidates (into [] (comp (filter #(offerable-as-relation? state (:identity %)))
                                  (take 5))
                         relation-search-results)
        ;; ...and when the query matched only relations that already exist, say so:
        ;; an empty list under a query that plainly matches something reads as a
        ;; broken search. Not said for a query that only matched the identity
        ;; itself, which is not a relation anybody was told about.
        all-related? (and (empty? candidates)
                          (boolean (some #(already-related? state (:identity %))
                                         relation-search-results)))]
    (when show-add-relation-modal
      [overlay {:on-dismiss #(swap! app-state assoc :show-add-relation-modal false :relation-search-query "" :relation-search-results [])
                :box {:min-width "400px" :max-width "500px"
                      :max-height "80vh" :overflow-y "auto"
                      :overscroll-behavior "contain"}}
       [:<>
        [:h2 {:style {:margin-top 0}} "Add Relation"]
        [:p {:style {:color "#666" :margin-bottom "1rem"}}
         (str "Link an identity to: " (:name selected-identity))]
        [:input {:type "text"
                 :placeholder "Search by name..."
                 :value relation-search-query
                 :ref focus-quietly
                 :on-change (fn [e]
                              (let [q (-> e .-target .-value)]
                                (swap! app-state assoc :relation-search-query q)
                                (when (>= (count q) 1)
                                  ;; kept whole; the cut to five happens after the
                                  ;; already-related ones have been taken out
                                  (search-identities q #(swap! app-state assoc :relation-search-results %)))))
                 :style {:width "100%"
                         :padding "0.75rem"
                         :font-size "1rem"
                         :border "1px solid #ccc"
                         :border-radius "4px"
                         :margin-bottom "1rem"}}]
        (when all-related?
          [:p {:style {:color "#666" :font-style "italic" :margin "0 0 0.5rem 0"}}
           "Already related to everything this matches."])
        (when (seq candidates)
          [:ul {:style {:list-style "none" :padding 0 :margin 0}}
           (for [result candidates]
             ^{:key (:identity result)}
             [:li {:on-click #(add-relation (:identity result) (:name result))
                   :style {:padding "0.75rem"
                           :cursor "pointer"
                           :background "#f5f5f5"
                           :border-radius "4px"
                           :margin-bottom "0.5rem"
                           :transition "background 0.2s"}
                   :on-mouse-over #(set! (.-background (.-style (.-currentTarget %))) "#e0e0e0")
                   :on-mouse-out #(set! (.-background (.-style (.-currentTarget %))) "#f5f5f5")}
              [:span {:style {:pointer-events "none"}} (:name result)]])])
        [:button {:on-click #(swap! app-state assoc :show-add-relation-modal false :relation-search-query "" :relation-search-results [])
                  :style {:margin-top "1rem"
                          :padding "0.5rem 1rem"
                          :cursor "pointer"}}
         "Cancel"]]])))

(defn add-identity-modal []
  (let [{:keys [show-add-identity-modal new-identity-name new-identity-text]} @app-state]
    (when show-add-identity-modal
      ;; No :on-dismiss. This is the one modal you write a whole identity into,
      ;; and a click that missed the box used to close it *without* clearing the
      ;; draft — so the text was gone from the screen and still in the state,
      ;; waiting to surprise the next Add. It is left by Cancel or by Create.
      ;; Capped to the window with the sheet's padding subtracted, and its own
      ;; scroller: the field below is a share of the viewport, so on a window too
      ;; short to hold the whole dialog it is Cancel and Create that would go off
      ;; the bottom edge, and they are the only way out of this one.
      [overlay {:box {:width "min(900px, 92vw)"
                      :max-height "calc(100vh - 4rem)"
                      :overflow-y "auto"
                      :overscroll-behavior "contain"}}
       [:<>
        [:h2 {:style {:margin-top 0}} "Add Identity"]
        [:div {:style {:margin-bottom "1rem"}}
         [:label {:style {:display "block" :margin-bottom "0.5rem" :font-weight "bold"}} "Name"]
         [:input {:type "text"
                  :placeholder "Display name for this identity..."
                  :value new-identity-name
                  :ref focus-quietly
                  :on-change #(swap! app-state assoc :new-identity-name (-> % .-target .-value))
                  :style {:width "100%"
                          :padding "0.75rem"
                          :font-size "1rem"
                          :border "1px solid #ccc"
                          :border-radius "4px"}}]]
        [:div {:style {:margin-bottom "1rem"}}
         [:label {:style {:display "block" :margin-bottom "0.5rem" :font-weight "bold"}} "Text"]
         ;; The same editor as the identity page's, so the text you type into a
         ;; new identity behaves like the text you later edit. Not monospace here:
         ;; this textarea never was.
         ;;
         ;; A tall field rather than a resizable one. The textarea this replaced
         ;; had a drag handle and CodeMirror has none, so the answer to "it is too
         ;; small" cannot be "drag it bigger" any more — it has to be big to begin
         ;; with, and it scrolls, which is what makes the fixed size bearable. In
         ;; viewport units so a small window gets a field that still fits in it,
         ;; and the sheet scrolls if even that is too tall.
         [codemirror/editor {:value new-identity-text
                             :on-change #(swap! app-state assoc :new-identity-text %)
                             :placeholder "Describe this identity..."
                             :height "min(55vh, 640px)"
                             :font-family "inherit"}]]
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
      [overlay {:on-dismiss #(swap! app-state assoc :show-beta-modal false)
                :box {:min-width "400px" :max-width "500px" :text-align "center"}}
       [:<>
        [:div {:style {:font-size "3rem" :margin-bottom "1rem"}} "\uD83D\uDE80"]
        [:h2 {:style {:margin-top 0 :margin-bottom "1rem"}} "Personalist Beta"]
        [:p {:style {:color "#666" :margin-bottom "1.5rem"}}
         "Welcome to the beta version of Personalist! We're "
         [:a {:href "https://github.com/eighttrigrams/personalist"
              :target "_blank"
              :style {:color "inherit"
                      :text-decoration "underline"
                      :cursor "pointer"}}
          "building"]
         " an integrated universe of personal encyclopedias."]
        [:a {:href "https://eighttrigrams.net/article/24/version/1"
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
