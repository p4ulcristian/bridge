(ns mailroom.sse
  "sse in the browser: an EventSource. After a break the browser reopens it
   by itself, after the server's :sse-reconnect-after-ms (the line's retry:
   field), and sends the last message's id, so the server can catch it up."
  (:require
   [mailroom.messages :as messages]
   [mailroom.settings :as settings]
   [mailroom.shapes :as shapes]
   [wire.api :as wire]))

(defn open-sse-line!
  "Opens the server stream; each named message, shaped by its :answer-shape,
   goes to its handler. Options, every key required: :on-open, (on-open
   again?) each time it opens, again? true after a break, or nil. The
   shapes and the options are checked first. Returns the line."
  [address shapes-by-name handlers options]
  (settings/check-shapes-by-name shapes-by-name)
  (let [{:keys [on-open]} (settings/check-sse-options options)
        opened-before?    (atom false)]
    ;; the address is a path on the page's own origin, which gets the
    ;; page's cookie anyway; withCredentials only matters across origins,
    ;; which mailroom doesn't serve
    (doto (js/EventSource. address #js {:withCredentials false})
      (set! -onopen #(do (when on-open (on-open @opened-before?))
                         (reset! opened-before? true)))
      (set! -onmessage #(->> (wire/turn-json-text-into-data (.-data %))
                             (shapes/turn-answer-into-shaped-data shapes-by-name)
                             (messages/hand-named-messages! handlers))))))

(defn close-sse-line!
  "Closes the line; it doesn't reopen."
  [line]
  (.close line))
