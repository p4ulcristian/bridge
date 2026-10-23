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
   bridge's own two; :keep-alive? is fetch's keepalive, the request
   finishing after the page has closed."
  [wire headers keep-alive? message]
  #js {:method      "POST"
       :mode        "same-origin"
       :credentials "same-origin"
       :cache       "no-store"
       :redirect    "follow"
       :keepalive   keep-alive?
       :headers     (clj->js (merge headers {"accept" "application/json" "content-type" "application/json"}))
       :body        (wire/turn-data-into-json-text wire message)})

(defn- read-answer
  "The answer in a response: shaped, when the server answered 200 with a
   JSON object; else :bridge/no-answer under each name, with the status (a
   proxy's 502, a 413, a page that isn't JSON)."
  [wire shapes-by-name message status text]
  (let [data (when (= 200 status)
               (try (wire/turn-json-text-into-data wire text)
                    (catch :default _ nil)))]
    (if (map? data)
      (shapes/turn-answer-into-shaped-data wire shapes-by-name data)
      (shapes/make-no-answer message status))))

(defn send-message!
  "POSTs {:orders/of-shop {…}} to the address. A promise of the answer,
   {:orders/of-shop …}: shaped data by the shapes, or a refusal, through
   the options' :wire; it never fails for want of an answer: no response
   (the network) or one that isn't the server's JSON is :bridge/no-answer
   under each name. The shapes and the options are checked first, and a
   name without shapes isn't sent."
  [address shapes-by-name message options]
  (settings/check-shapes-by-name shapes-by-name)
  (let [{:keys [wire headers keep-alive?]} (settings/check-send-options options)
        json-data                          (shapes/turn-message-into-json-data wire shapes-by-name message)]
    (-> (js/fetch address (make-fetch-options wire headers keep-alive? json-data))
        (.then (fn [response]
                 (-> (.text response)
                     (.then #(read-answer wire shapes-by-name message (.-status response) %)
                            (fn [_] (shapes/make-no-answer message (.-status response))))))
               (fn [_] (shapes/make-no-answer message nil))))))
