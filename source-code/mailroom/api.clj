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

     (def handlers {:orders/of-shop {:steps [find-orders-of-shop add-orders-answer]}})

     (def settings {:ip \"0.0.0.0\" :port 8080 :client-address-header nil
                    :addresses {:http \"/mail/http\" :sse \"/mail/sse\" :ws \"/mail/ws\"}
                    :ping-every-ms 25000})

     (mailroom/start-server!
      (mailroom/make-ring-handler-from-routes
       (mailroom/make-routes-for-messages
        handlers settings
        {:find-rate-limit-key #(mailroom/find-client-address % (:client-address-header settings))})
       {:files-folder nil})
      settings)

   Nothing has a default: the settings (settings-shape) and the options are
   checked first, and a missing one refuses, named.

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
  "The settings, every key required, as a malli shape:
   {:ip :port :client-address-header :addresses {:http :sse :ws} :ping-every-ms}.
   :client-address-header: behind a proxy, the header it puts the asker's
   address in (\"cf-connecting-ip\", \"x-forwarded-for\"); nil when the
   server faces browsers itself. :ping-every-ms: how often an idle sse line
   gets a comment and a ws line {:mailroom/ping {}}, so proxies don't cut
   them."
  settings/settings-shape)

(defn find-client-address
  "The asker's address: the request's own, or the one the proxy put in
   client-address-header (for x-forwarded-for, its last entry)."
  [request client-address-header]
  (limits/find-client-address request client-address-header))

(defn make-routes-for-messages
  "The three routes, at the settings' :addresses, as reitit data, for
   make-ring-handler-from-routes. Options: :find-rate-limit-key, required,
   (fn [request]) telling askers apart for the handlers' :rate-limit
   ({:max 10 :per-ms 60000}; over it, \"Too many requests.\"), such as
   find-client-address with the settings' :client-address-header; and
   :on-sse-open, :on-sse-close, :on-ws-open, :on-ws-close, each
   (fn [line]), each left out when nothing runs then. The settings and the
   options are checked first: every problem at once."
  [handlers settings options]
  (settings/check-settings settings)
  (settings/check-routes-options options)
  (let [{:keys [addresses ping-every-ms]} settings
        limiter                           (limits/make-limiter (:find-rate-limit-key options))]
    [[(:http addresses) {:post {:handler (http/make-ring-handler-for-http handlers limiter)}}]
     [(:sse addresses)  {:get  {:handler (sse/make-ring-handler-for-sse ping-every-ms options)}}]
     [(:ws addresses)   {:get  {:handler (ws/make-ring-handler-for-ws handlers limiter ping-every-ms options)}}]]))

(defn start-server!
  "Starts http-kit serving one Ring handler, every request on its own
   virtual thread, at the settings' :ip and :port (0 picks a free one).
   The settings are checked first. Returns the running server:
   {:port the-port …}."
  [ring-handler settings]
  (server/start-server! ring-handler (settings/check-settings settings)))

(defn stop-server!
  "Stops taking requests, lets running ones finish (up to 10 s), then stops."
  [server]
  (server/stop-server! server))

(defn send-message-on-sse-line!
  "Writes a named message into an open sse line; an id comes back as
   Last-Event-ID when the browser reconnects. Whether the line was open."
  ([line message] (sse/send-message-on-sse-line! line message))
  ([line message id] (sse/send-message-on-sse-line! line message id)))

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
   server: for tests and the REPL. request is the Ring request it came with
   (its cookie, its origin). Notes, when a handler has them, are beside the
   answers under :mailroom/notes."
  [handlers request message]
  (messages/answer-named-messages handlers request message))

;; -----------------------------------------------------------------------------
;; Routes

(defn make-server-routes-for-pages
  "A GET route for each page, answered by page-handler, so a page opened
   fresh or reloaded works."
  [pages page-handler]
  (routes/make-server-routes-for-pages pages page-handler))

(defn make-ring-handler-from-routes
  "The one Ring handler, from reitit routes. Options: :files-folder,
   required, a folder on disk a GET no route matches is answered from (the
   browser's code and styles), or nil for none: a file whose name is hashed
   (main.3F9A….js) is cached for a year, any other asked again each time.
   No route and no file: 404; a method the route doesn't have: 405."
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
