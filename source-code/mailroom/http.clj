(ns mailroom.http
  "http: a POST of a named message to the settings' http address, answered
   at once with the same names."
  (:require
   [mailroom.messages :as messages]
   [wire.api :as wire]))

(def ^:private not-json
  {:status 400 :headers {"content-type" "text/plain"} :body "The body must be a JSON object of named messages."})

(defn- make-json-response [data]
  {:status  200
   :headers {"content-type" "application/json" "cache-control" "no-store"}
   :body    (wire/turn-data-into-json-text data)})

(defn- read-message [request]
  (try (let [message (wire/turn-json-text-into-data (slurp (:body request) :encoding "UTF-8"))]
         (when (map? message) message))
       (catch Exception _ nil)))

(defn make-ring-handler-for-http
  "A Ring handler that answers each named message in the body by its handler."
  [handlers limiter]
  (fn [request]
    (if-let [message (read-message request)]
      (make-json-response (messages/answer-named-messages handlers limiter request message))
      not-json)))
