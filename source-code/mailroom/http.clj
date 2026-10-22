(ns mailroom.http
  "http: a POST of a named message to the settings' http address, answered
   at once with the same names."
  (:require
   [mailroom.messages :as messages]
   [wire.api :as wire]))

(def ^:private not-json
  {:status 400 :headers {"content-type" "text/plain"} :body "The body must be a JSON object of named messages."})

(defn- make-json-response [wire data]
  {:status  200
   :headers {"content-type" "application/json" "cache-control" "no-store"}
   :body    (wire/turn-data-into-json-text wire data)})

(defn- read-message [wire request]
  (try (let [message (wire/turn-json-text-into-data wire (slurp (:body request) :encoding "UTF-8"))]
         (when (map? message) message))
       (catch Exception _ nil)))

(defn make-ring-handler-for-http
  "A Ring handler that answers each named message in the body by its
   handler, read and written by the app's wire, each message with its own
   log, made from the app's; each name noted by note-answer!."
  [wire log handlers limiter note-answer!]
  (fn [request]
    (if-let [message (read-message wire request)]
      (make-json-response wire (messages/answer-named-messages wire log handlers limiter note-answer! request message))
      not-json)))
