(ns et.pe.ui.codemirror
  "Daniel's IJKL keyboard scheme on personalist's writing surfaces.

  The bindings themselves are not here. They are `@eighttrigrams/kw-codemirror`,
  the library in the keyboard-wizardry repo that also holds his VSCode and
  Obsidian keymaps, so there is one implementation of the scheme rather than one
  per app. This namespace is only the reagent side of it.

  Why a component and not the library's `fromTextarea`: personalist's textareas
  are controlled components - reagent owns their `:value` and learns about typing
  from `:on-change`. Mirroring a document into a textarea's value, which is what
  fromTextarea does for a server-rendered form, would never reach reagent, since
  setting `.value` fires no input event. So the editor is mounted on a div and
  reports changes through `:on-change`, the same shape the textarea had."
  (:require [reagent.core :as r]
            ["@codemirror/state" :refer [EditorState]]
            ["@codemirror/view" :refer [EditorView keymap placeholder]]
            ["@codemirror/commands" :as commands]
            ["@eighttrigrams/kw-codemirror" :as ijkl]))

(defn- theme
  "Built to match the textarea this replaces, so nothing about the page moves."
  [{:keys [height font-family font-size]}]
  (.theme EditorView
          #js {"&" #js {:height (or height "200px")
                        :fontSize (or font-size "1rem")
                        :border "1px solid #ccc"
                        :borderRadius "4px"
                        :backgroundColor "white"}
               "&.cm-focused" #js {:outline "none" :borderColor "#4CAF50"}
               ".cm-scroller" #js {:overflow "auto"
                                   :fontFamily (or font-family "monospace")
                                   :lineHeight "1.5"}
               ".cm-content" #js {:padding "0.75rem"
                                  :fontFamily (or font-family "monospace")
                                  :caretColor "#333"}
               ".cm-line" #js {:padding "0"}
               ".cm-gutters" #js {:display "none"}
               ".cm-activeLine" #js {:backgroundColor "transparent"}
               ".cm-cursor" #js {:borderLeftColor "#333"}}))

(defn- create-view [element {:keys [value on-change] :as props}]
  (let [listener (.of (.-updateListener EditorView)
                      (fn [^js update]
                        (when (and (.-docChanged update) on-change)
                          (on-change (.. update -state -doc toString)))))
        extensions (cond-> [(theme props)
                            (.-lineWrapping EditorView)
                            (commands/history)
                            (.of keymap commands/historyKeymap)
                            (.of keymap commands/defaultKeymap)
                            listener]
                     (:placeholder props) (conj (placeholder (:placeholder props))))
        state (.create EditorState
                       #js {:doc (or value "")
                            ;; into-array, not clj->js: these are CodeMirror
                            ;; extension objects and have no business being walked.
                            :extensions (into-array extensions)})
        view (new EditorView #js {:state state :parent element})]
    ;; The scheme. Capture phase inside the library, so these win over
    ;; CodeMirror's own keymaps above.
    (ijkl/install view commands)
    view))

(defn- set-doc! [^js view value]
  (.dispatch view
             #js {:changes #js {:from 0
                                :to (.. view -state -doc -length)
                                :insert (or value "")}}))

(defn editor
  "A CodeMirror with the IJKL bindings, in place of a textarea.

  Props, all optional but for the two that carry the text:
    :value        the document
    :on-change    (fn [string]) on every change
    :height       CSS height, default 200px
    :font-family  default monospace
    :font-size    default 1rem
    :placeholder  shown while the document is empty
    :auto-focus?  focus it once mounted"
  [_props]
  (let [!view (atom nil)
        !element (atom nil)]
    (r/create-class
      {:display-name "ijkl-editor"

       :component-did-mount
       (fn [this]
         (let [{:keys [auto-focus?] :as props} (second (r/argv this))
               view (create-view @!element props)]
           (reset! !view view)
           (when auto-focus? (.focus view))))

       ;; Reagent re-renders this on every keystroke, because :value comes out of
       ;; app-state and :on-change puts it there. Replacing the document each time
       ;; would fight the editor - the caret would jump to the end of every word.
       ;; So only when the two have actually diverged, which is what happens when
       ;; something *else* changed the text: another identity selected, a version
       ;; picked off the time slider.
       :component-did-update
       (fn [this _]
         (let [{:keys [value]} (second (r/argv this))
               ^js view @!view]
           (when (and view (not= (or value "") (.. view -state -doc toString)))
             (set-doc! view value))))

       :component-will-unmount
       (fn [_]
         (when-let [^js view @!view]
           (.destroy view)
           (reset! !view nil)))

       :reagent-render
       (fn [{:keys [height]}]
         [:div {:ref #(reset! !element %)
                :style {:width "100%"
                        :height (or height "200px")}}])})))
