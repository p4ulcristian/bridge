(ns bridge.sse
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
   line has it as :bridge/last-event-id, to catch up from. The line
   carries the app's wire too, :bridge/wire, which its messages are
   written by.

   Each line is written by its request's own virtual thread, which waits
   for the next message up to :ping-every-ms and pings when none came. A
   send puts the message in the line's queue and returns at once, so a slow
   browser holds up only its own line.

   A browser leaving is seen at once: the line asks Jetty to say when its
   socket can be read (nothing more comes from a browser on an sse line, so
   the socket's end is the only thing to read), and closes when it ends. A
   write to a socket that is gone fails too, so the next ping or message
   closes such a line at the latest. The answer says Connection: close, so
   Jetty never reads the next request on a socket the line was watching."
  (:require
   [clojure.string :as str]
   [bridge.metrics :as metrics]
   [ring.core.protocols :as ring-protocols]
   [wire.api :as wire])
  (:import
   (java.io OutputStream)
   (java.util.function Consumer)
   (org.eclipse.jetty.server Request)
   (org.eclipse.jetty.util BufferUtil Callback)
   (java.nio.charset StandardCharsets)
   (java.util.concurrent ConcurrentHashMap LinkedBlockingQueue TimeUnit)
   (java.util.concurrent.atomic AtomicBoolean)))

(def current-jetty-request
  "Jetty's request, while bridge's server handles it on this thread."
  (ThreadLocal.))

(deftype SseChannel [^LinkedBlockingQueue queue ^AtomicBoolean closed? close!])

(def ^:private stop-writing
  "Put in a line's queue: its thread ends the answer."
  ::stop-writing)

(defn make-sse-lines
  "A server's open sse lines, so a stop closes them; once it is stopping,
   a line that opens is closed at once."
  []
  {:open (ConcurrentHashMap.) :stopping? (AtomicBoolean.)})

(defn close-sse-channel!
  "Closes the line: counted out, on-sse-close run, its thread told to end
   the answer. Once: the browser leaving, the server and a stop may all
   come."
  [^SseChannel channel]
  ((.-close! channel)))

(defn close-every-sse-line!
  "A stop: every open line is closed, and any that opens after."
  [{:keys [^ConcurrentHashMap open ^AtomicBoolean stopping?]}]
  (.set stopping? true)
  (run! close-sse-channel! (vec (.keySet open))))

(defn is-sse-channel-open? [^SseChannel channel]
  (not (.get ^AtomicBoolean (.-closed? channel))))

(def ^:private stream-response
  {:status  200
   :headers {"content-type"      "text/event-stream"
             "cache-control"     "no-cache"
             ;; nginx and some proxies hold a response back unless told not to
             "x-accel-buffering" "no"
             ;; the line watches its socket; none is read for another request
             "connection"        "close"}})

(defn- make-line [wire channel request]
  {:bridge/channel       channel
   :bridge/request       (dissoc request :bridge/sse-lines)
   :bridge/last-event-id (get-in request [:headers "last-event-id"])
   :bridge/wire          wire})

(defn- write-text! [^OutputStream out ^String text]
  (.write out (.getBytes text StandardCharsets/UTF_8))
  (.flush out))

(defn- write-until-closed!
  "Writes each message the queue gets, a ping when none came for
   ping-every-ms, until the line is closed or a write fails (the browser
   left)."
  [^SseChannel channel out ping-every-ms]
  (try
    (loop []
      (when (is-sse-channel-open? channel)
        (let [text (.poll ^LinkedBlockingQueue (.-queue channel) (long ping-every-ms) TimeUnit/MILLISECONDS)]
          (when-not (= stop-writing text)
            (write-text! out (or text ": ping\n\n"))
            (recur)))))
    (catch java.io.IOException _
      ;; the browser left: the finally closes the line
      nil)
    (catch InterruptedException _
      ;; a stop cut the line's thread
      nil)
    (finally
      (close-sse-channel! channel))))

(defn- watch-for-browser-leaving!
  "Asks Jetty to say when the line's socket can be read, and closes the
   line when it has ended (or anything else came); nothing when the line
   isn't under bridge's server."
  [channel]
  (when-let [^Request jetty-request (.get ^ThreadLocal current-jetty-request)]
    (let [end-point (.. jetty-request getConnectionMetaData getConnection getEndPoint)
          close!    #(close-sse-channel! channel)]
      (letfn [(watch []
                (.tryFillInterested end-point
                                    (Callback/from ^Runnable
                                                   (fn []
                                                     (if (try (zero? (.fill end-point (BufferUtil/allocate 1)))
                                                              (catch java.io.IOException _ false))
                                                       (watch)
                                                       (close!)))
                                                   (reify Consumer
                                                     (accept [_ _error] (close!))))))]
        (watch)))))

(defn- run-line!
  "The line's answer, on its request's thread, until it is closed."
  [request {:keys [ping-every-ms sse-reconnect-after-ms]} {:keys [wire meter on-sse-open on-sse-close]} out]
  (let [{:keys [^ConcurrentHashMap open ^AtomicBoolean stopping?]} (:bridge/sse-lines request)
        closed?  (AtomicBoolean.)
        line     (promise)
        channel  (SseChannel. (LinkedBlockingQueue.)
                              closed?
                              (fn []
                                (when (.compareAndSet closed? false true)
                                  (some-> open (.remove (:bridge/channel @line)))
                                  (.offer ^LinkedBlockingQueue (.-queue ^SseChannel (:bridge/channel @line)) stop-writing)
                                  (metrics/add-to-open-lines! meter :sse -1)
                                  (when on-sse-close (on-sse-close @line)))))]
    (deliver line (make-line wire channel request))
    (metrics/add-to-open-lines! meter :sse 1)
    ;; a line opened by a handler outside bridge's server has no stop to close it
    (some-> open (.put channel true))
    (when (some-> stopping? .get)
      (close-sse-channel! channel))
    ;; a retry: field alone sets the browser's wait and is no message
    (try (write-text! out (str "retry: " sse-reconnect-after-ms "\n\n"))
         (catch java.io.IOException _
           (close-sse-channel! channel)))
    (watch-for-browser-leaving! channel)
    (when (and on-sse-open (is-sse-channel-open? channel))
      (on-sse-open @line))
    (write-until-closed! channel out ping-every-ms)))

(defn make-ring-handler-for-sse
  "A Ring handler that opens a line and keeps it open: it tells the browser
   to wait :sse-reconnect-after-ms before it reopens a broken line, pings it
   every :ping-every-ms, and calls (on-sse-open line) when it opens,
   (on-sse-close line) once when it closes: closed by the server, or left
   by the browser, seen at the latest by the next ping or send. Each line
   carries the options' :wire, and is among the open lines on the options'
   :meter while it is open."
  [settings options]
  (fn [request]
    (assoc stream-response
           :body (reify ring-protocols/StreamableResponseBody
                   (write-body-to-stream [_ _response out]
                     (run-line! request settings options out))))))

(defn turn-message-into-sse-text
  "A named message and its id (nil for none) as the lines of one sse
   event: \"id: 17\\ndata: {…}\\n\\n\", written by the wire."
  [wire message id]
  (str (when id (str "id: " id "\n"))
       (str/join (map #(str "data: " % "\n") (str/split-lines (wire/turn-data-into-json-text wire message))))
       "\n"))

(defn send-message-on-sse-line!
  "Puts a named message, with its id (nil for none), in an open line's
   queue; its thread writes it. An id comes back as Last-Event-ID when the
   browser reconnects. Whether the line was open: a line the browser has
   left is closed by this write."
  [line message id]
  (let [^SseChannel channel (:bridge/channel line)]
    (and (is-sse-channel-open? channel)
         (.offer ^LinkedBlockingQueue (.-queue channel) (turn-message-into-sse-text (:bridge/wire line) message id)))))
