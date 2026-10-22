(ns mailroom.settings
  "mailroom's settings, and the data its functions take, as malli shapes,
   checked before anything starts. Nothing has a default: every key is
   written out (nil where it is a choice), and a missing or unknown one
   refuses, named, with every other problem at once.

   The server's settings and its functions' options are closed maps. A
   handler, a page and a name's shapes are open: the app and the libraries
   on top keep their own keys in them (an access rule beside a handler's
   steps), and mailroom requires only its own."
  (:require
   [wire.api :as wire]))

;; -----------------------------------------------------------------------------
;; The server's settings

(def settings-shape
  "The settings, every key required.
   :ip, :port: where the server listens; port 0 picks a free one.
   :proxy-protocol: :disable when browsers or an http proxy connect; :enable
   behind a load balancer that speaks the PROXY protocol (the asker's
   address then comes from it); :optional takes both.
   :client-address-header: behind a proxy, the header it puts the asker's
   address in (\"cf-connecting-ip\", \"x-forwarded-for\"); nil when the
   server faces browsers itself.
   :server-header: what the Server header says, nil for none.
   :max-body-bytes: the largest request body; a bigger one is refused (413).
   :max-header-line-bytes: the longest request line or header line; a
   longer one is refused (414).
   :max-ws-message-bytes: the largest message a browser may send on ws.
   :addresses: where the three pipes are, {:http … :sse … :ws …}.
   :ping-every-ms: how often an idle sse line gets a comment and a ws line
   {:mailroom/ping {}}, so proxies don't cut them.
   :sse-reconnect-after-ms: how long the browser waits before it reopens a
   broken sse line; sent on the line as its retry: field when it opens.
   :ws-reopen-after-ms: how long the browser waits before it reopens a
   broken ws line; the server doesn't read it, the app hands it to the
   browser with the addresses (open-ws-line!'s :reopen-after-ms).
   :stop-wait-ms: how long stop-server! lets running requests finish
   before it cuts them (a slow report needs longer).
   :stop-wait-after-cut-ms: after the cut, how long stop-server! waits for
   the cut requests' threads to end before it returns.
   :rate-limit-buckets-before-cleanup: rate limits keep a bucket per message
   name and asker in memory; above this many, each message first drops the
   buckets that have refilled full."
  [:map {:closed true}
   [:ip :string]
   [:port :int]
   [:proxy-protocol [:enum :disable :enable :optional]]
   [:client-address-header [:maybe :string]]
   [:server-header [:maybe :string]]
   [:max-body-bytes [:int {:min 1}]]
   [:max-header-line-bytes [:int {:min 1}]]
   [:max-ws-message-bytes [:int {:min 1}]]
   [:addresses [:map {:closed true}
                [:http :string]
                [:sse :string]
                [:ws :string]]]
   [:ping-every-ms [:int {:min 1}]]
   [:sse-reconnect-after-ms [:int {:min 1}]]
   [:ws-reopen-after-ms [:int {:min 1}]]
   [:stop-wait-ms [:int {:min 0}]]
   [:stop-wait-after-cut-ms [:int {:min 0}]]
   [:rate-limit-buckets-before-cleanup [:int {:min 1}]]])

;; -----------------------------------------------------------------------------
;; The server's options and data

(def routes-options-shape
  "make-routes-for-messages's options, every key required.
   :find-rate-limit-key: (fn [request]) telling askers apart for the
   handlers' :rate-limit. The four hooks: each (fn [line]), or nil written
   out when nothing runs then."
  [:map {:closed true}
   [:find-rate-limit-key fn?]
   [:on-sse-open [:maybe fn?]]
   [:on-sse-close [:maybe fn?]]
   [:on-ws-open [:maybe fn?]]
   [:on-ws-close [:maybe fn?]]])

(def ring-handler-options-shape
  "make-ring-handler-from-routes's options. :files: nil for none, or where
   the files a GET no route matches is answered from:
   :folder, the folder on disk;
   :hashed-name-min-hex-digits, how many hex digits a name needs before its
   extension to count as hashed (main.3F9A0C1D.js), a release build's file
   whose name changes with its content;
   :hashed-file-cache-seconds, how long a browser keeps a hashed file
   without asking again."
  [:map {:closed true}
   [:files [:maybe [:map {:closed true}
                    [:folder :string]
                    [:hashed-name-min-hex-digits [:int {:min 1}]]
                    [:hashed-file-cache-seconds [:int {:min 0}]]]]]])

(def handler-shape
  "One handler, every key mailroom reads required, nil written out where
   nothing applies. :steps, its work. :params-shape and :answer-shape, the
   malli shapes its data comes in by and its answer leaves by; nil passes
   it as it is. :rate-limit, {:max 10 :per-ms 60000} per asker, nil for
   none. :run-steps-in, (fn [run context]) the steps run inside (a
   transaction), nil to run them plainly."
  [:map
   [:steps [:sequential [:map [:name :keyword] [:enter fn?]]]]
   [:params-shape :any]
   [:answer-shape :any]
   [:rate-limit [:maybe [:map {:closed true}
                         [:max [:int {:min 1}]]
                         [:per-ms [:int {:min 1}]]]]]
   [:run-steps-in [:maybe fn?]]])

(def handlers-shape
  "The handler table: message name to handler."
  [:map-of :keyword handler-shape])

(def rate-limits-outside-a-server-shape
  "answer-message's rate limits: the server's limiter counts them, and
   outside a server there is none, so the caller writes :no-rate-limits."
  [:= :no-rate-limits])

(def pages-shape
  "The app's pages by namespaced name. :address, its path with its params
   (\"/orders/:order-id\"); :title, the tab's title while it shows, nil
   when the app sets the title itself."
  [:map-of :qualified-keyword [:map
                               [:address :string]
                               [:title [:maybe :string]]]])

;; -----------------------------------------------------------------------------
;; The browser's options and data

(def shapes-by-name-shape
  "The browser's shapes, by message name: the same two a handler says,
   each written out, nil where the data passes as it is."
  [:map-of :keyword [:map
                     [:params-shape :any]
                     [:answer-shape :any]]])

(def sse-options-shape
  "open-sse-line!'s options, every key required. :on-open: (on-open again?)
   each time the line opens, again? true after a break; nil when nothing
   runs then."
  [:map {:closed true}
   [:on-open [:maybe fn?]]])

(def ws-options-shape
  "open-ws-line!'s options, every key required. :reopen-after-ms: how long
   it waits before it reopens a broken line."
  [:map {:closed true}
   [:reopen-after-ms [:int {:min 1}]]])

;; -----------------------------------------------------------------------------
;; Checking

(defn check-data-for-shape
  "Returns the data; throws {:type kind :errors …} with every problem at
   once when it doesn't fit the shape."
  [what kind shape data]
  (when-let [errors (wire/find-errors-in-data-for-shape shape data)]
    (throw (ex-info (str what " have problems: " (pr-str errors))
                    {:type kind :errors errors})))
  data)

(defn check-settings [settings]
  (check-data-for-shape "The settings" :mailroom/bad-settings settings-shape settings))

(defn check-routes-options [options]
  (check-data-for-shape "The options" :mailroom/bad-options routes-options-shape options))

(defn check-ring-handler-options [options]
  (check-data-for-shape "The options" :mailroom/bad-options ring-handler-options-shape options))

(defn check-handlers [handlers]
  (check-data-for-shape "The handlers" :mailroom/bad-handlers handlers-shape handlers))

(defn check-rate-limits-outside-a-server [rate-limits]
  (check-data-for-shape "The rate limits" :mailroom/bad-options rate-limits-outside-a-server-shape rate-limits))

(defn check-pages [pages]
  (check-data-for-shape "The pages" :mailroom/bad-pages pages-shape pages))

(defn check-shapes-by-name [shapes-by-name]
  (check-data-for-shape "The shapes" :mailroom/bad-shapes shapes-by-name-shape shapes-by-name))

(defn check-sse-options [options]
  (check-data-for-shape "The options" :mailroom/bad-options sse-options-shape options))

(defn check-ws-options [options]
  (check-data-for-shape "The options" :mailroom/bad-options ws-options-shape options))
