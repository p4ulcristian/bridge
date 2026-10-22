(ns mailroom.sse
  "sse in the browser: an EventSource. It reopens by itself after a break
   and sends the last message's id, so the server can catch it up."
  (:require
   [mailroom.messages :as messages]
   [mailroom.shapes :as shapes]
   [wire.api :as wire]))

(defn open-sse-line!
  "Opens the server stream; each named message, shaped by its :answer-shape,
   goes to its handler. (on-open again?) each time it opens, again? true
   after a break. Returns the line."
  [address shapes-by-name handlers {:keys [on-open]}]
  (let [opened-before? (atom false)]
    (doto (js/EventSource. address #js {:withCredentials true})
      (set! -onopen #(when on-open
                       (on-open @opened-before?)
                       (reset! opened-before? true)))
      (set! -onmessage #(->> (wire/turn-json-text-into-data (.-data %))
                             (shapes/turn-answer-into-shaped-data shapes-by-name)
                             (messages/hand-named-messages! handlers))))))

(defn close-sse-line!
  "Closes the line; it doesn't reopen."
  [line]
  (.close line))
