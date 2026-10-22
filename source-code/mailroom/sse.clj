(ns mailroom.sse
  "sse: a server stream (Server-Sent Events) at the settings' sse address.
   The browser opens one GET per tab; the answer stays open, and each named
   message is a few lines written into it. A comment line every
   :ping-every-ms (a setting) keeps proxies from cutting it, and the first
   lines say how long the browser waits before it reopens the line after a
   break, :sse-reconnect-after-ms (a setting):

     retry: 3000

     id: 17
     data: {\"bells/changed\":{\"table\":\"orders\",\"ids\":[\"42\"]}}

   A browser that reconnects sends the last id it got as Last-Event-ID; the
   line has it as :mailroom/last-event-id, to catch up from. The line
   carries the app's wire too, :mailroom/wire, which its messages are
   written by."
  (:require
   [clojure.string :as str]
   [mailroom.channel :as channel]
   [mailroom.metrics :as metrics]
   [wire.api :as wire]
   [org.httpkit.server :as http-kit])
  (:import
   (java.util.concurrent.atomic AtomicBoolean)))

(def ^:private stream-response
  {:status  200
   :headers {"content-type"      "text/event-stream"
             "cache-control"     "no-cache"
             ;; nginx and some proxies hold a response back unless told not to
             "x-accel-buffering" "no"}})

(defn- make-line [wire channel request]
  {:mailroom/channel       channel
   :mailroom/request       request
   :mailroom/last-event-id (get-in request [:headers "last-event-id"])
   :mailroom/wire          wire})

(defn- close-line-once!
  "Counts the line out of the open ones and runs (on-sse-close line), once
   for each line: http-kit's :on-close and a ping that finds the browser
   gone may both come."
  [^AtomicBoolean closed? meter on-sse-close line]
  (when (.compareAndSet closed? false true)
    (metrics/add-to-open-lines! meter :sse -1)
    (when on-sse-close (on-sse-close line))))

(defn- close-if-browser-left!
  "Whether the line is open. A line whose socket is closed (the browser
   left, and http-kit 2.8.1 didn't say so) is closed now: http-kit's close
   runs its :on-close, and the line is counted out and on-sse-close runs."
  [channel]
  (or (channel/is-channel-open? channel)
      (do (http-kit/close channel)
          false)))

(defn- keep-pinging!
  "Every ping-every-ms, a comment line; a line the browser has left is
   closed instead, so it lives at most one ping-every-ms after it is left.
   Stops once the line is closed."
  [channel ping-every-ms]
  (Thread/startVirtualThread
   #(while (do (Thread/sleep (long ping-every-ms))
               (and (close-if-browser-left! channel)
                    (http-kit/send! channel ": ping\n\n" false))))))

(defn- open-line! [request {:keys [ping-every-ms sse-reconnect-after-ms]} {:keys [wire meter on-sse-open]} channel]
  (metrics/add-to-open-lines! meter :sse 1)
  ;; a retry: field alone sets the browser's wait and is no message
  (http-kit/send! channel (assoc stream-response :body (str "retry: " sse-reconnect-after-ms "\n\n")) false)
  (keep-pinging! channel ping-every-ms)
  (when on-sse-open (on-sse-open (make-line wire channel request))))

(defn make-ring-handler-for-sse
  "A Ring handler that opens a line and keeps it open: it tells the browser
   to wait :sse-reconnect-after-ms before it reopens a broken line, pings it
   every :ping-every-ms, and calls (on-sse-open line) when it opens,
   (on-sse-close line) once when it closes: closed by the server, or left
   by the browser, seen at the latest by the next ping or send. Each line
   carries the options' :wire, and is among the open lines on the options'
   :meter while it is open."
  [settings {:keys [wire meter on-sse-close] :as options}]
  (fn [request]
    (let [closed? (AtomicBoolean.)]
      (http-kit/as-channel request
                           {:on-open  #(open-line! request settings options %)
                            :on-close (fn [channel _status]
                                        (close-line-once! closed? meter on-sse-close (make-line wire channel request)))}))))

(defn turn-message-into-sse-text
  "A named message and its id (nil for none) as the lines of one sse
   event: \"id: 17\\ndata: {…}\\n\\n\", written by the wire."
  [wire message id]
  (str (when id (str "id: " id "\n"))
       (str/join (map #(str "data: " % "\n") (str/split-lines (wire/turn-data-into-json-text wire message))))
       "\n"))

(defn send-message-on-sse-line!
  "Writes a named message into an open line with its id, nil for none; an
   id comes back as Last-Event-ID when the browser reconnects. Whether it
   was still open; a line the browser has left is closed now."
  [line message id]
  (let [channel (:mailroom/channel line)]
    (and (close-if-browser-left! channel)
         (http-kit/send! channel (turn-message-into-sse-text (:mailroom/wire line) message id) false))))
