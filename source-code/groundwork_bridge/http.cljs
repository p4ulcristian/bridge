(ns groundwork-bridge.http
  "http from the browser: POST a named message, get the answer keyed by the
   same names, each turned into shaped data by its shape."
  (:require
   [groundwork-bridge.settings :as settings]
   [groundwork-bridge.shapes :as shapes]
   [groundwork-shaped-json.api :as shaped-json]))

(defn- make-fetch-options
  "The addresses are paths on the page's own origin, and the server sends
   no CORS headers: a request goes there only, with the page's cookie. An
   answer is never kept (the server says no-store too); a redirect in front
   of the server (a proxy) is followed. The options' :headers go beside
   bridge's own two; :keep-alive? is fetch's keepalive, the request
   finishing after the page has closed."
  [shaper headers keep-alive? message]
  #js {:method      "POST"
       :mode        "same-origin"
       :credentials "same-origin"
       :cache       "no-store"
       :redirect    "follow"
       :keepalive   keep-alive?
       :headers     (clj->js (merge headers {"accept" "application/json" "content-type" "application/json"}))
       :body        (shaped-json/turn-data-into-json-text shaper message)})

(defn- read-answer
  "The answer in a response: shaped, when the server answered 200 with a
   JSON object; else :groundwork-bridge/no-answer under each name, with the status (a
   proxy's 502, a 413, a page that isn't JSON)."
  [shaper shapes-by-name message status text]
  (let [data (when (= 200 status)
               (try (shaped-json/turn-json-text-into-data shaper text)
                    (catch :default _ nil)))]
    (if (map? data)
      (shapes/turn-answer-into-shaped-data shaper shapes-by-name data)
      (shapes/make-no-answer message status))))

(defn send-message!
  "POSTs {:orders/of-shop {…}} to the address. A promise of the answer,
   {:orders/of-shop …}: shaped data by the shapes, or a refusal, through
   the options' :shaper; it never fails for want of an answer: no response
   (the network) or one that isn't the server's JSON is :groundwork-bridge/no-answer
   under each name. The shapes and the options are checked first, and a
   name without shapes isn't sent."
  [address shapes-by-name message options]
  (settings/check-shapes-by-name shapes-by-name)
  (let [{:keys [shaper headers keep-alive?]} (settings/check-send-options options)
        json-data                          (shapes/turn-message-into-json-data shaper shapes-by-name message)]
    (-> (js/fetch address (make-fetch-options shaper headers keep-alive? json-data))
        (.then (fn [response]
                 (-> (.text response)
                     (.then #(read-answer shaper shapes-by-name message (.-status response) %)
                            (fn [_] (shapes/make-no-answer message (.-status response))))))
               (fn [_] (shapes/make-no-answer message nil))))))
