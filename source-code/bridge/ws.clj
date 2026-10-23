(ns bridge.ws
  "ws: a websocket at the settings' ws address. Each message the browser
   sends is answered by the same handlers as http, and the answer sent back
   on the line; the server can also send on a line by itself. Every
   :ping-every-ms (a setting) the server sends {\"bridge/ping\":{}}, so a
   proxy never sees the line idle and cuts it; the browser drops it. Every
   message is read and written by the app's wire, which the line carries,
   :bridge/wire."
  (:require
   [bridge.messages :as messages]
   [bridge.metrics :as metrics]
   [wire.api :as wire]
   [org.httpkit.server :as http-kit]))

(def ^:private not-a-websocket
  {:status 400 :headers {"content-type" "text/plain"} :body "This address is a websocket."})

(defn- make-line [wire channel request]
  {:bridge/channel channel :bridge/request request :bridge/wire wire})

(defn send-message-on-ws-line!
  "Sends a named message as JSON on an open line. Whether it was still open."
  [line message]
  (http-kit/send! (:bridge/channel line) (wire/turn-data-into-json-text (:bridge/wire line) message)))

(defn- answer-on-line! [log handlers limiter note-answer! {wire :bridge/wire :as line} text]
  (let [message (wire/turn-json-text-into-data wire text)]
    (send-message-on-ws-line! line (messages/answer-named-messages wire log handlers limiter note-answer! (:bridge/request line) message))))

(defn- keep-pinging! [wire channel ping-every-ms]
  (let [ping (wire/turn-data-into-json-text wire {:bridge/ping {}})]
    (Thread/startVirtualThread
     #(while (do (Thread/sleep (long ping-every-ms))
                 (http-kit/send! channel ping))))))

(defn- open-websocket [request handlers limiter ping-every-ms note-answer! {:keys [wire log meter on-ws-open on-ws-close]}]
  (http-kit/as-channel request
                       {:on-open    (fn [channel]
                                      (metrics/add-to-open-lines! meter :ws 1)
                                      (keep-pinging! wire channel ping-every-ms)
                                      (when on-ws-open (on-ws-open (make-line wire channel request))))
                        :on-receive #(answer-on-line! log handlers limiter note-answer! (make-line wire %1 request) %2)
                        :on-close   (fn [channel _status]
                                      (metrics/add-to-open-lines! meter :ws -1)
                                      (when on-ws-close (on-ws-close (make-line wire channel request))))}))

(defn make-ring-handler-for-ws
  "A Ring handler that upgrades to a websocket answering named messages,
   pinging it every ping-every-ms, by the options' :wire, each message with
   its own log made from the options' :log, counted on the options' :meter
   (the line among the open ones, each name by note-answer!). A request
   that isn't a websocket gets 400."
  [handlers limiter ping-every-ms options]
  (let [note-answer! (metrics/make-note-answer (:meter options) :ws)]
    (fn [request]
      (if (:websocket? request)
        (open-websocket request handlers limiter ping-every-ms note-answer! options)
        not-a-websocket))))
