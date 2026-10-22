(ns mailroom.http
  "http from the browser: POST a named message, get the answer keyed by the
   same names, each turned into shaped data by its shape."
  (:require
   [mailroom.settings :as settings]
   [mailroom.shapes :as shapes]
   [wire.api :as wire]))

(defn- make-fetch-options
  "The addresses are paths on the page's own origin, and the server sends
   no CORS headers: a request goes there only, with the page's cookie. An
   answer is never kept (the server says no-store too); a redirect in front
   of the server (a proxy) is followed."
  [message]
  #js {:method      "POST"
       :mode        "same-origin"
       :credentials "same-origin"
       :cache       "no-store"
       :redirect    "follow"
       :headers     #js {"accept" "application/json" "content-type" "application/json"}
       :body        (wire/turn-data-into-json-text message)})

(defn send-message!
  "POSTs {:orders/of-shop {…}} to the address. A promise of the answer,
   {:orders/of-shop …}: shaped data by the shapes, or {:error …}. The
   shapes are checked first, and a name without shapes isn't sent."
  [address shapes-by-name message]
  (settings/check-shapes-by-name shapes-by-name)
  (-> (js/fetch address (make-fetch-options (shapes/turn-message-into-json-data shapes-by-name message)))
      (.then #(.text %))
      (.then #(shapes/turn-answer-into-shaped-data shapes-by-name (wire/turn-json-text-into-data %)))))
