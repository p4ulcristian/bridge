(ns bridge.api
  "Named messages come in, a handler's steps answer them, the answers go
   out: how a browser and a server talk, the server's side. The browser's
   side is this namespace's .cljs half.

     http  a POST of a message, answered at once
     sse   the server stream: messages to the browser
     ws    a websocket: messages both ways

   A message is a map of names to data, {:orders/of-shop {:shop/id 7}}. The
   server answers it from one handler table, by name, whichever pipe it came
   on; the answer has the same names. A handler's :steps are its work, a
   list of steps over one map, the context (run-steps-until-answered):

     (def handlers {:orders/of-shop {:steps        [find-orders-of-shop add-orders-answer]
                                     :params-shape [:map [:shop/id :uuid]]
                                     :answer-shape [:vector [:map [:order/id :uuid]]]
                                     :rate-limit   nil
                                     :run-steps-in nil}})

     (def settings {:ip \"0.0.0.0\" :port 8080 :proxy-protocol :disable
                    :client-address-header nil :server-header nil
                    :max-body-bytes 8388608 :max-header-line-bytes 8192 :max-ws-message-bytes 4194304
                    :addresses {:http \"/mail/http\" :sse \"/mail/sse\" :ws \"/mail/ws\"}
                    :ping-every-ms 25000 :sse-reconnect-after-ms 3000 :ws-reopen-after-ms 1000
                    :stop-wait-ms 10000 :stop-wait-after-cut-ms 1000
                    :rate-limit-buckets-before-cleanup 10000
                    :metric-buckets {:answer-seconds [0.005 0.01 0.025 0.05 0.1 0.25 0.5 1 2.5 5 10]}})

     ;; the app's wire, log and meter, made once by the app (bridge makes none)
     (def wire (wire.api/make-wire-from-settings wire-settings))
     (def log (logbook.api/log-function (logbook.api/make-logbook logbook-settings)))
     (def meter (meter.api/make-meter meter-settings {:log log}))

     (bridge/start-server!
      (bridge/make-ring-handler-from-routes
       (bridge/make-routes-for-messages
        handlers settings
        {:wire                wire
         :log                 log
         :meter               meter
         :find-rate-limit-key #(bridge/find-client-address % (:client-address-header settings))
         :on-sse-open nil :on-sse-close nil :on-ws-open nil :on-ws-close nil})
       {:files nil})
      settings
      {:log log})

   Nothing has a default: the settings (settings-shape), the handlers and
   the options are checked first, every key written out, nil where nothing
   applies; a missing one refuses, named, with every other problem at once.

   Each message's steps get, beside the request, its name and its data,
   what every step may use: :bridge/log, the app's log with a new random
   request id as its context ({:request-id \"…\"}), and :bridge/wire,
   the app's wire. A step that learns more (who is asking) puts a log with
   more context back at :bridge/log, for the steps after it. A handler's
   failure is written to that log, :error :bridge/handler-failed;
   http-kit's own errors and warnings to start-server!'s :log,
   :bridge/server-error and :bridge/server-warning. bridge writes
   nowhere else.

   make-routes-for-messages declares bridge's metrics on the app's meter,
   its :meter, and counts into it (bridge.metrics):
   bridge_messages_total{name,pipe,outcome}, each named message answered
   on http or ws; bridge_answer_seconds{name}, the time to answer it,
   bucketed by the settings' :metric-buckets; bridge_open_lines{pipe},
   the open sse and ws lines. A name no handler has is counted as
   \"other\".

   A line (an open sse or ws connection) is
   {:bridge/channel … :bridge/request … :bridge/wire …}: it carries
   the wire its routes were made with, which messages sent on it are
   written by."
  (:require
   [logbook.api :as logbook]
   [bridge.answers :as answers]
   [bridge.channel :as channel]
   [bridge.http :as http]
   [bridge.limits :as limits]
   [bridge.messages :as messages]
   [bridge.metrics :as metrics]
   [bridge.routes :as routes]
   [bridge.server :as server]
   [bridge.settings :as settings]
   [bridge.sse :as sse]
   [bridge.steps :as steps]
   [bridge.ws :as ws]))

(def settings-shape
  "The settings, every key required, as a malli shape, the map closed:
   {:ip :port :proxy-protocol :client-address-header :server-header
    :max-body-bytes :max-header-line-bytes :max-ws-message-bytes
    :addresses {:http :sse :ws} :ping-every-ms :sse-reconnect-after-ms
    :ws-reopen-after-ms :stop-wait-ms :stop-wait-after-cut-ms
    :rate-limit-buckets-before-cleanup :metric-buckets {:answer-seconds}}.
   :proxy-protocol: :disable, :enable or :optional, for a load balancer
   that speaks the PROXY protocol. :client-address-header: behind a proxy,
   the header it puts the asker's address in (\"cf-connecting-ip\",
   \"x-forwarded-for\"); nil when the server faces browsers itself.
   :server-header: the Server header, nil for none. :max-body-bytes,
   :max-header-line-bytes, :max-ws-message-bytes: the largest request body,
   request or header line, and ws message taken. :ping-every-ms: how often
   an idle sse line gets a comment and a ws line {:bridge/ping {}}, so
   proxies don't cut them. :sse-reconnect-after-ms: how long the browser
   waits before it reopens a broken sse line, sent on the line (retry:);
   the app hands it to the browser with the addresses too, for a line the
   server answered with an HTTP error. :ws-reopen-after-ms: the same for
   ws; the app hands it to the browser with the addresses. :stop-wait-ms: how long stop-server! lets running
   requests finish; :stop-wait-after-cut-ms: how long it then waits for the
   cut ones' threads to end. :rate-limit-buckets-before-cleanup: above this
   many rate-limit buckets (one per message name and asker) in memory, the
   full ones are dropped. :metric-buckets: the histograms' bucket bounds,
   ascending: :answer-seconds, the time to answer a named message."
  settings/settings-shape)

(defn find-client-address
  "The asker's address: the request's own, or the one the proxy put in
   client-address-header (for x-forwarded-for, its last entry)."
  [request client-address-header]
  (limits/find-client-address request client-address-header))

(defn make-rate-limited-ring-handler
  "A Ring handler for a plain route (not a message), rate-limited like a
   handler's :rate-limit, by a limiter of its own: one bucket per asker,
   holding :max and refilled at :max per :per-ms; an asker over it is
   answered 429 \"Too many requests.\" (text/plain, no-store) and
   ring-handler isn't called. Kept in this server's memory. Options, every
   key required, checked first: :rate-limit {:max :per-ms};
   :find-rate-limit-key, (fn [request]) telling askers apart, such as
   find-client-address with the settings' :client-address-header;
   :buckets-before-cleanup, above this many buckets the full ones are
   dropped.

     (bridge/make-rate-limited-ring-handler start-visitor-session
                                              {:rate-limit             {:max 10 :per-ms 60000}
                                               :find-rate-limit-key    #(bridge/find-client-address % (:client-address-header settings))
                                               :buckets-before-cleanup (:rate-limit-buckets-before-cleanup settings)})"
  [ring-handler options]
  (limits/make-rate-limited-ring-handler ring-handler (settings/check-rate-limited-options options)))

(defn make-routes-for-messages
  "The three routes, at the settings' :addresses, as reitit data, for
   make-ring-handler-from-routes. Each handler says, every key written out:
   :steps; :params-shape and :answer-shape (nil: the data passes as it is);
   :rate-limit, {:max 10 :per-ms 60000} per asker (over it, \"Too many
   requests.\"), nil for none; :run-steps-in, (fn [run context]), nil for
   none. Options, each required: :wire, the app's wire (wire.api's
   make-wire-from-settings), which every message is read and written by;
   :log, the app's log (logbook's), which each message's log is made from,
   with a new request id; :meter, the app's meter (meter's make-meter),
   which bridge's metrics are declared on here and counted into (each
   named message by name, pipe and outcome, its answer time, the open
   lines; bridge.metrics);
   :find-rate-limit-key, (fn [request])
   telling askers apart, such as find-client-address with the settings'
   :client-address-header; :on-sse-open, :on-sse-close, :on-ws-open,
   :on-ws-close, each (fn [line]) or nil when nothing runs then; each
   close hook runs once for a line, and an sse line the browser left is
   seen as closed at the latest at its next ping (:ping-every-ms). The
   settings, the handlers and the options are checked first: every problem
   at once."
  [handlers settings options]
  (settings/check-settings settings)
  (settings/check-handlers handlers)
  (settings/check-routes-options options)
  ;; a log logbook didn't make can't carry a request id: refused now, not at the first message
  (logbook/with-context (:log options) {})
  ;; and a meter meter didn't make is refused here too (:meter/not-a-meter)
  (metrics/declare-metrics! (:meter options) handlers settings)
  (let [{:keys [addresses ping-every-ms rate-limit-buckets-before-cleanup]} settings
        limiter (limits/make-limiter (:find-rate-limit-key options) rate-limit-buckets-before-cleanup)]
    [[(:http addresses) {:post {:handler (http/make-ring-handler-for-http (:wire options) (:log options) handlers limiter
                                                                          (metrics/make-note-answer (:meter options) :http))}}]
     [(:sse addresses)  {:get  {:handler (sse/make-ring-handler-for-sse settings options)}}]
     [(:ws addresses)   {:get  {:handler (ws/make-ring-handler-for-ws handlers limiter ping-every-ms options)}}]]))

(defn start-server!
  "Starts http-kit serving one Ring handler, every request on its own
   virtual thread, at the settings' :ip and :port (0 picks a free one).
   Options, every key required: {:log log}, the app's log, which
   http-kit's own errors and warnings are written to. The settings and
   options are checked first. Returns the running server:
   {:port the-port …}."
  [ring-handler settings options]
  (server/start-server! ring-handler (settings/check-settings settings) (settings/check-server-options options)))

(defn stop-server!
  "Stops taking requests, lets running ones finish (up to the settings'
   :stop-wait-ms), cuts the ones left and waits for their threads (up to
   :stop-wait-after-cut-ms), then returns whether every request's thread
   had ended."
  [server]
  (server/stop-server! server))

(defn send-message-on-sse-line!
  "Writes a named message into an open sse line with its id, nil written
   out for none; an id comes back as Last-Event-ID when the browser
   reconnects. Written by the wire the line carries. Whether the line was
   open."
  [line message id]
  (sse/send-message-on-sse-line! line message id))

(defn send-message-on-ws-line!
  "Sends a named message on an open ws line, written by the wire the line
   carries. Whether it was open."
  [line message]
  (ws/send-message-on-ws-line! line message))

(defn is-line-open?
  "Whether an sse or ws line is still open: not closed, and its socket
   not closed by the browser leaving."
  [line]
  (channel/is-channel-open? (:bridge/channel line)))

(defn answer-message
  "The answer a message gets from the handlers, as over http, without a
   server: for tests and the REPL. rate-limits is :no-rate-limits, written
   out: the server's limiter counts a handler's :rate-limit, and here there
   is none. request is the Ring request it came with (its cookie, its
   origin). Notes, when a handler has them, are beside the answers under
   :bridge/notes. Options, every key required: :wire, the app's wire;
   :log, the app's log, which the message's log is made from. The handlers
   and the options are checked first."
  [handlers rate-limits request message options]
  (settings/check-handlers handlers)
  (settings/check-rate-limits-outside-a-server rate-limits)
  (settings/check-answer-options options)
  ;; outside a server nothing is counted: answer-message takes no meter
  (messages/answer-named-messages (:wire options) (:log options) handlers rate-limits metrics/note-nothing request message))

;; -----------------------------------------------------------------------------
;; Answers

(defn is-error-answer?
  "Whether an answer is one of bridge's error answers rather than data,
   the same on both sides: {:error …} alone (a refusal: a step's, \"Not
   found.\", or bridge's own, \"Too many requests.\", \"Something went
   wrong.\"), {:errors …} alone (params that didn't fit their shape), or
   one carrying :bridge/errors (in the browser, an answer that didn't fit
   its shape). Anything else is data."
  [answer]
  (answers/is-error-answer? answer))

;; -----------------------------------------------------------------------------
;; Routes

(defn make-server-routes-for-pages
  "A GET route for each page, answered by page-handler, so a page opened
   fresh or reloaded works. Each page has a namespaced name, an :address
   and a :title (nil when the app sets the title itself); they are checked
   first, every problem at once (:bridge/bad-pages)."
  [pages page-handler]
  (routes/make-server-routes-for-pages pages page-handler))

(defn make-ring-handler-from-routes
  "The one Ring handler, from reitit routes. Options: :files, required, nil
   for none, or where a GET no route matches is answered from (the
   browser's code and styles), every key required: :folder on disk;
   :hashed-name-min-hex-digits, how many hex digits before the extension
   make a name hashed (main.3F9A0C1D.js); :hashed-file-cache-seconds, how
   long a hashed file is kept; any other file is asked again each time.
   A file is served by its exact name only. No route and no file: 404; a
   method the route doesn't have: 405."
  [routes options]
  (routes/make-ring-handler-from-routes routes (settings/check-ring-handler-options options)))

;; -----------------------------------------------------------------------------
;; Steps: a handler's work

(defn run-steps-until-answered
  "Runs each step's :enter on the context, in order, until one adds an
   answer. Returns the last context. A step is
   {:name a-keyword :enter (fn [context] context)}."
  [steps context]
  (steps/run-steps-until-answered steps context))

(defn add-answer-to-context
  "The context with an answer, at :bridge/answer; no step after this one
   runs."
  [context answer]
  (steps/add-answer-to-context context answer))

(defn does-context-have-answer?
  "Whether a step has added an answer."
  [context]
  (steps/does-context-have-answer? context))
