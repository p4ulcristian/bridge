(ns bridge.http
  "http from the browser: POST a named message, get the answer keyed by the
   same names, each turned into shaped data by its shape."
  (:require
   [bridge.settings :as settings]
   [bridge.shapes :as shapes]
   [wire.api :as wire]))

(defn- make-fetch-options
  "The addresses are paths on the page's own origin, and the server sends
   no CORS headers: a request goes there only, with the page's cookie. An
   answer is never kept (the server says no-store too); a redirect in front
   of the server (a proxy) is followed. The options' :headers go beside
   bridge's own two."
  [wire headers message]
  #js {:method      "POST"
       :mode        "same-origin"
       :credentials "same-origin"
       :cache       "no-store"
       :redirect    "follow"
       :headers     (clj->js (merge headers {"accept" "application/json" "content-type" "application/json"}))
       :body        (wire/turn-data-into-json-text wire message)})

(defn send-message!
  "POSTs {:orders/of-shop {…}} to the address. A promise of the answer,
   {:orders/of-shop …}: shaped data by the shapes, or {:error …}, through
   the options' :wire. The shapes and the options are checked first, and a
   name without shapes isn't sent."
  [address shapes-by-name message options]
  (settings/check-shapes-by-name shapes-by-name)
  (let [{:keys [wire headers]} (settings/check-send-options options)]
    (-> (js/fetch address (make-fetch-options wire headers (shapes/turn-message-into-json-data wire shapes-by-name message)))
        (.then #(.text %))
        (.then #(shapes/turn-answer-into-shaped-data wire shapes-by-name (wire/turn-json-text-into-data wire %))))))
