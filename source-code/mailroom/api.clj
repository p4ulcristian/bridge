(ns mailroom.api
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
                    :rate-limit-buckets-before-cleanup 10000})

     (mailroom/start-server!
      (mailroom/make-ring-handler-from-routes
       (mailroom/make-routes-for-messages
        handlers settings
        {:find-rate-limit-key #(mailroom/find-client-address % (:client-address-header settings))
         :on-sse-open nil :on-sse-close nil :on-ws-open nil :on-ws-close nil})
       {:files nil})
      settings)

   Nothing has a default: the settings (settings-shape), the handlers and
   the options are checked first, every key written out, nil where nothing
   applies; a missing one refuses, named, with every other problem at once.

   A line (an open sse or ws connection) is
   {:mailroom/channel … :mailroom/request …}."
  (:require
   [mailroom.http :as http]
   [mailroom.limits :as limits]
   [mailroom.messages :as messages]
   [mailroom.routes :as routes]
   [mailroom.server :as server]
   [mailroom.settings :as settings]
   [mailroom.sse :as sse]
   [mailroom.steps :as steps]
   [mailroom.ws :as ws]
   [org.httpkit.server :as http-kit]))

(def settings-shape
  "The settings, every key required, as a malli shape, the map closed:
   {:ip :port :proxy-protocol :client-address-header :server-header
    :max-body-bytes :max-header-line-bytes :max-ws-message-bytes
    :addresses {:http :sse :ws} :ping-every-ms :sse-reconnect-after-ms
    :ws-reopen-after-ms :stop-wait-ms :stop-wait-after-cut-ms
    :rate-limit-buckets-before-cleanup}.
   :proxy-protocol: :disable, :enable or :optional, for a load balancer
   that speaks the PROXY protocol. :client-address-header: behind a proxy,
   the header it puts the asker's address in (\"cf-connecting-ip\",
   \"x-forwarded-for\"); nil when the server faces browsers itself.
   :server-header: the Server header, nil for none. :max-body-bytes,
   :max-header-line-bytes, :max-ws-message-bytes: the largest request body,
   request or header line, and ws message taken. :ping-every-ms: how often
   an idle sse line gets a comment and a ws line {:mailroom/ping {}}, so
   proxies don't cut them. :sse-reconnect-after-ms: how long the browser
   waits before it reopens a broken sse line, sent on the line (retry:);
   the app hands it to the browser with the addresses too, for a line the
   server answered with an HTTP error. :ws-reopen-after-ms: the same for
   ws; the app hands it to the browser with the addresses. :stop-wait-ms: how long stop-server! lets running
   requests finish; :stop-wait-after-cut-ms: how long it then waits for the
   cut ones' threads to end. :rate-limit-buckets-before-cleanup: above this
   many rate-limit buckets (one per message name and asker) in memory, the
   full ones are dropped."
  settings/settings-shape)

(defn find-client-address
  "The asker's address: the request's own, or the one the proxy put in
   client-address-header (for x-forwarded-for, its last entry)."
  [request client-address-header]
  (limits/find-client-address request client-address-header))

(defn make-routes-for-messages
  "The three routes, at the settings' :addresses, as reitit data, for
   make-ring-handler-from-routes. Each handler says, every key written out:
   :steps; :params-shape and :answer-shape (nil: the data passes as it is);
   :rate-limit, {:max 10 :per-ms 60000} per asker (over it, \"Too many
   requests.\"), nil for none; :run-steps-in, (fn [run context]), nil for
   none. Options, each required: :find-rate-limit-key, (fn [request])
   telling askers apart, such as find-client-address with the settings'
   :client-address-header; :on-sse-open, :on-sse-close, :on-ws-open,
   :on-ws-close, each (fn [line]) or nil when nothing runs then. The
   settings, the handlers and the options are checked first: every problem
   at once."
  [handlers settings options]
  (settings/check-settings settings)
  (settings/check-handlers handlers)
  (settings/check-routes-options options)
  (let [{:keys [addresses ping-every-ms rate-limit-buckets-before-cleanup]} settings
        limiter (limits/make-limiter (:find-rate-limit-key options) rate-limit-buckets-before-cleanup)]
    [[(:http addresses) {:post {:handler (http/make-ring-handler-for-http handlers limiter)}}]
     [(:sse addresses)  {:get  {:handler (sse/make-ring-handler-for-sse settings options)}}]
     [(:ws addresses)   {:get  {:handler (ws/make-ring-handler-for-ws handlers limiter ping-every-ms options)}}]]))

(defn start-server!
  "Starts http-kit serving one Ring handler, every request on its own
   virtual thread, at the settings' :ip and :port (0 picks a free one).
   The settings are checked first. Returns the running server:
   {:port the-port …}."
  [ring-handler settings]
  (server/start-server! ring-handler (settings/check-settings settings)))

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
   reconnects. Whether the line was open."
  [line message id]
  (sse/send-message-on-sse-line! line message id))

(defn send-message-on-ws-line!
  "Sends a named message on an open ws line. Whether it was open."
  [line message]
  (ws/send-message-on-ws-line! line message))

(defn is-line-open?
  "Whether an sse or ws line is still open."
  [line]
  (http-kit/open? (:mailroom/channel line)))

(defn answer-message
  "The answer a message gets from the handlers, as over http, without a
   server: for tests and the REPL. rate-limits is :no-rate-limits, written
   out: the server's limiter counts a handler's :rate-limit, and here there
   is none. request is the Ring request it came with (its cookie, its
   origin). Notes, when a handler has them, are beside the answers under
   :mailroom/notes. The handlers are checked first."
  [handlers rate-limits request message]
  (settings/check-handlers handlers)
  (settings/check-rate-limits-outside-a-server rate-limits)
  (messages/answer-named-messages handlers rate-limits request message))

;; -----------------------------------------------------------------------------
;; Routes

(defn make-server-routes-for-pages
  "A GET route for each page, answered by page-handler, so a page opened
   fresh or reloaded works. Each page has a namespaced name, an :address
   and a :title (nil when the app sets the title itself); they are checked
   first, every problem at once (:mailroom/bad-pages)."
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
  "The context with an answer, at :mailroom/answer; no step after this one
   runs."
  [context answer]
  (steps/add-answer-to-context context answer))

(defn does-context-have-answer?
  "Whether a step has added an answer."
  [context]
  (steps/does-context-have-answer? context))
