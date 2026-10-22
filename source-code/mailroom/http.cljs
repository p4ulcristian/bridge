(ns mailroom.http
  "http from the browser: POST a named message, get the answer keyed by the
   same names, each turned into shaped data by its shape."
  (:require
   [mailroom.shapes :as shapes]
   [wire.api :as wire]))

(defn- make-fetch-options [message]
  #js {:method      "POST"
       :credentials "same-origin"
       :headers     #js {"accept" "application/json" "content-type" "application/json"}
       :body        (wire/turn-data-into-json-text message)})

(defn send-message!
  "POSTs {:orders/of-shop {…}} to the address. A promise of the answer,
   {:orders/of-shop …}: shaped data by the shapes, or {:error …}."
  [address shapes-by-name message]
  (-> (js/fetch address (make-fetch-options (shapes/turn-message-into-json-data shapes-by-name message)))
      (.then #(.text %))
      (.then #(shapes/turn-answer-into-shaped-data shapes-by-name (wire/turn-json-text-into-data %)))))
