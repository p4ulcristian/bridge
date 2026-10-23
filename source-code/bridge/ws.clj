(ns bridge.ws
  "ws: a websocket at the settings' ws address. Each message the browser
   sends is answered by the same handlers as http, each on a virtual thread
   of its own, and the answer sent back on the line; the server can also
   send on a line by itself. Every :ping-every-ms (a setting) the server
   sends {\"bridge/ping\":{}}, so a proxy never sees the line idle and cuts
   it; the browser drops it. Every message is read and written by the
   app's wire, which the line carries, :bridge/wire. A line's channel is
   its Jetty websocket session, through Ring's websocket protocols."
  (:require
   [bridge.messages :as messages]
   [bridge.metrics :as metrics]
   [ring.websocket.protocols :as ring-ws]
   [wire.api :as wire]))

(def ^:private not-a-websocket
  {:status 400 :headers {"content-type" "text/plain"} :body "This address is a websocket."})

(defn is-websocket-request?
  "Whether the request asks to become a websocket: Upgrade: websocket and
   Connection: upgrade."
  [{:keys [headers]}]
  (let [has? (fn [header word]
               (some-> (get headers header) .toLowerCase (.contains ^String word)))]
    (boolean (and (has? "upgrade" "websocket") (has? "connection" "upgrade")))))

(defn- make-line [wire session request]
  {:bridge/channel session :bridge/request (dissoc request :bridge/sse-lines) :bridge/wire wire})

(defn is-ws-channel-open? [session]
  (ring-ws/-open? session))

(defn close-ws-channel!
  "Closes the websocket (1000, normal); its on-ws-close runs once it has."
  [session]
  (ring-ws/-close session 1000 "closed"))

(defn send-message-on-ws-line!
  "Sends a named message as JSON on an open line. Whether it was open."
  [line message]
  (let [session (:bridge/channel line)]
    (and (ring-ws/-open? session)
         (do (ring-ws/-send session (wire/turn-data-into-json-text (:bridge/wire line) message))
             true))))

(defn- answer-on-line!
  "Answers one message from the browser on its line; a message that isn't
   JSON text is written to the log, :bridge/server-error, and not answered."
  [log handlers limiter note-answer! {wire :bridge/wire :as line} text]
  (try
    (let [message (wire/turn-json-text-into-data wire text)]
      (send-message-on-ws-line! line (messages/answer-named-messages wire log handlers limiter note-answer! (:bridge/request line) message)))
    (catch Throwable e
      (log :error :bridge/server-error {:error e}))))

(defn- keep-pinging!
  "Every ping-every-ms the ping, until the line is closed."
  [line ping-every-ms]
  (let [ping {:bridge/ping {}}]
    (Thread/startVirtualThread
     #(while (do (Thread/sleep (long ping-every-ms))
                 (send-message-on-ws-line! line ping))))))

(defn- make-listener [request handlers limiter ping-every-ms note-answer! {:keys [wire log meter on-ws-open on-ws-close]}]
  (reify ring-ws/Listener
    (on-open [_ session]
      (let [line (make-line wire session request)]
        (metrics/add-to-open-lines! meter :ws 1)
        (keep-pinging! line ping-every-ms)
        (when on-ws-open (on-ws-open line))))
    (on-message [_ session message]
      ;; Jetty reads the next frame while this one is answered; a binary
      ;; frame is read as UTF-8 text, as a text frame is
      (let [text (if (instance? java.nio.ByteBuffer message)
                   (str (.decode java.nio.charset.StandardCharsets/UTF_8 ^java.nio.ByteBuffer message))
                   (str message))]
        (Thread/startVirtualThread
         #(answer-on-line! log handlers limiter note-answer! (make-line wire session request) text))))
    (on-pong [_ _session _data])
    (on-error [_ _session _error])
    (on-close [_ session _code _reason]
      (metrics/add-to-open-lines! meter :ws -1)
      (when on-ws-close (on-ws-close (make-line wire session request))))))

(defn make-ring-handler-for-ws
  "A Ring handler that upgrades to a websocket answering named messages,
   pinging it every ping-every-ms, by the options' :wire, each message with
   its own log made from the options' :log, counted on the options' :meter
   (the line among the open ones, each name by note-answer!). A request
   that isn't a websocket gets 400."
  [handlers limiter ping-every-ms options]
  (let [note-answer! (metrics/make-note-answer (:meter options) :ws)]
    (fn [request]
      (if (is-websocket-request? request)
        {:ring.websocket/listener (make-listener request handlers limiter ping-every-ms note-answer! options)}
        not-a-websocket))))
