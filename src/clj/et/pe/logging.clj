(ns et.pe.logging)

(comment
  (require '[taoensso.telemere :as t])
  (t/log! :info "Hello world!") ; %> Basic log   signal (has message)
  (t/event! ::my-id :debug)     ; %> Basic event signal (just id))
  (t/set-ns-filter! {:disallow "xtdb.metrics" :allow "*"})
)