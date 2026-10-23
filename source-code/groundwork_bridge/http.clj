(ns groundwork-bridge.http
  "http: a POST of a named message to the settings' http address, answered
   at once with the same names."
  (:require
   [groundwork-bridge.messages :as messages]
   [groundwork-shaped-json.api :as shaped-json]))

(def ^:private not-json
  {:status 400 :headers {"content-type" "text/plain"} :body "The body must be a JSON object of named messages."})

(defn- make-json-response [shaper data]
  {:status  200
   :headers {"content-type" "application/json" "cache-control" "no-store"}
   :body    (shaped-json/turn-data-into-json-text shaper data)})

(defn- read-message
  "The body's named messages, or nil when it isn't a JSON object. Reading
   the body is outside the try: a body over :max-body-bytes fails there,
   and Jetty answers it 413."
  [shaper request]
  (let [text (slurp (:body request) :encoding "UTF-8")]
    (try (let [message (shaped-json/turn-json-text-into-data shaper text)]
           (when (map? message) message))
         (catch Exception _ nil))))

(defn make-ring-handler-for-http
  "A Ring handler that answers each named message in the body by its
   handler, read and written by the app's shaper, each message with its own
   log, made from the app's; each name noted by note-answer!."
  [shaper log handlers limiter note-answer!]
  (fn [request]
    (if-let [message (read-message shaper request)]
      (make-json-response shaper (messages/answer-named-messages shaper log handlers limiter note-answer! request message))
      not-json)))
