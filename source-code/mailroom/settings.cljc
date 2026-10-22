(ns mailroom.settings
  "mailroom's settings, and the data its functions take, as malli shapes,
   checked before anything starts. Nothing has a default: every key is
   written out (nil where it is a choice), and a missing or unknown one
   refuses, named, with every other problem at once.

   The server's settings and its functions' options are closed maps. A
   handler, a page and a name's shapes are open: the app and the libraries
   on top keep their own keys in them (an access rule beside a handler's
   steps), and mailroom requires only its own.

   They are checked by malli itself, not through wire: they are values the
   app hands in, not data crossing an edge. The functions that do send or
   read data across an edge take the app's wire under :wire in their
   options; mailroom never makes one. On the server, the functions that
   answer messages or run the server take the app's log under :log;
   mailroom never decides how a line is written."
  (:require
   [malli.core :as malli]
   [malli.error :as malli-error]))

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
   broken sse line; sent on the line as its retry: field when it opens, and
   handed to the browser by the app (open-sse-line!'s :reconnect-after-ms)
   for a line the server answered with an HTTP error, which the browser
   doesn't reopen by itself.
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

(def wire-entry
  "The app's wire (wire.api's make-wire-from-settings), made once by the
   app and handed to every function that sends or reads a message."
  [:wire [some? {:error/message "should be the app's wire, made by wire.api/make-wire-from-settings"}]])

(def log-entry
  "The app's log function, (log level event data), made by logbook's
   log-function or with-context: each message gets one with its request id
   as context, and a handler's failure and the server's errors are written
   to it."
  [:log [fn? {:error/message "should be the app's log function, made by logbook"}]])

(def routes-options-shape
  "make-routes-for-messages's options, every key required.
   :wire: the app's wire, for every message the three routes read and
   write; each line they open carries it, for send-message-on-sse-line!
   and send-message-on-ws-line!.
   :log: the app's log; each message's steps get one with a new request id
   as context (:mailroom/log), and a handler's failure is written to it.
   :find-rate-limit-key: (fn [request]) telling askers apart for the
   handlers' :rate-limit. The four hooks: each (fn [line]), or nil written
   out when nothing runs then."
  [:map {:closed true}
   wire-entry
   log-entry
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

(def answer-options-shape
  "answer-message's options, every key required. :wire: the app's wire,
   which turns the message's data into shaped data and the answer into
   JSON data, as over http. :log: the app's log, as the routes take it."
  [:map {:closed true}
   wire-entry
   log-entry])

(def server-options-shape
  "start-server!'s options, every key required. :log: the app's log;
   http-kit's own errors and warnings are written to it."
  [:map {:closed true}
   log-entry])

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

(def ^:private headers-mailroom-sets
  "The request headers send-message! writes itself: what the body is and
   what the answer must be."
  #{"accept" "content-type"})

(defn- leaves-mailroom-headers-alone? [headers]
  (not-any? headers-mailroom-sets (keys headers)))

(defn- has-lowercase-names? [headers]
  (every? #(= % (.toLowerCase ^String %)) (keys headers)))

(def send-options-shape
  "send-message!'s options, every key required. :wire: the app's wire,
   made from the browser's settings. :headers: more request headers, by
   lowercase name, {} for none (a write's one-time key, say); accept and
   content-type are mailroom's own and refused."
  [:map {:closed true}
   wire-entry
   [:headers [:and
              [:map-of :string :string]
              [:fn {:error/message "header names are written in lowercase"} has-lowercase-names?]
              [:fn {:error/message "accept and content-type are mailroom's own"} leaves-mailroom-headers-alone?]]]])

(def sse-options-shape
  "open-sse-line!'s options, every key required. :wire: the app's wire.
   :on-open: (on-open again?)
   each time the line opens, again? true after a break; nil when nothing
   runs then. :reconnect-after-ms: how long it waits before it opens a new
   line when the server answered with an HTTP error, which the browser
   doesn't reopen by itself (the server's :sse-reconnect-after-ms setting,
   which the app hands to the browser)."
  [:map {:closed true}
   wire-entry
   [:on-open [:maybe fn?]]
   [:reconnect-after-ms [:int {:min 1}]]])

(def following-options-shape
  "start-following-address!'s options, every key required.
   :on-page: (fn [page scroll]) now and on every move.
   :query-shapes-of-pages: by page name, every page's and no other: a
   malli map its query's text values are read by, or nil to keep them text.
   :may-leave?: (fn []) -> whether to move, asked before a move to another
   page; nil when nothing is asked.
   :languages: nil without languages, or the app's functions for the
   language prefix of an address and the language the page came in:
   {:split-language-from-path (fn [path]) -> {:language :path},
    :make-path-in-language (fn [path language]) -> path,
    :language-of-page \"en\"}."
  [:map {:closed true}
   [:on-page fn?]
   [:query-shapes-of-pages [:map-of :qualified-keyword :any]]
   [:may-leave? [:maybe fn?]]
   [:languages [:maybe [:map {:closed true}
                        [:split-language-from-path fn?]
                        [:make-path-in-language fn?]
                        [:language-of-page :string]]]]])

(def ws-options-shape
  "open-ws-line!'s options, every key required. :wire: the app's wire; the
   line carries it, for send-message-on-ws-line!. :reopen-after-ms: how
   long it waits before it reopens a broken line."
  [:map {:closed true}
   wire-entry
   [:reopen-after-ms [:int {:min 1}]]])

;; -----------------------------------------------------------------------------
;; Checking

(defn check-data-for-shape
  "Returns the data; throws {:type kind :errors …} with every problem at
   once when it doesn't fit the shape."
  [what kind shape data]
  (when-let [explanation (malli/explain shape data)]
    (let [errors (malli-error/humanize explanation)]
      (throw (ex-info (str what " have problems: " (pr-str errors))
                      {:type kind :errors errors}))))
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

(defn check-server-options [options]
  (check-data-for-shape "The options" :mailroom/bad-options server-options-shape options))

(defn check-answer-options [options]
  (check-data-for-shape "The options" :mailroom/bad-options answer-options-shape options))

(defn check-pages [pages]
  (check-data-for-shape "The pages" :mailroom/bad-pages pages-shape pages))

(defn check-shapes-by-name [shapes-by-name]
  (check-data-for-shape "The shapes" :mailroom/bad-shapes shapes-by-name-shape shapes-by-name))

(defn check-sse-options [options]
  (check-data-for-shape "The options" :mailroom/bad-options sse-options-shape options))

(defn check-send-options [options]
  (check-data-for-shape "The options" :mailroom/bad-options send-options-shape options))

(defn check-following-options
  "The options, and that :query-shapes-of-pages names every page and no
   other: :mailroom/bad-options with every problem at once."
  [pages options]
  (check-data-for-shape "The options" :mailroom/bad-options following-options-shape options)
  (let [declared (set (keys (:query-shapes-of-pages options)))
        missing  (remove declared (keys pages))
        extra    (remove (set (keys pages)) declared)]
    (when (or (seq missing) (seq extra))
      (throw (ex-info (str "The options have problems: :query-shapes-of-pages names "
                           (when (seq missing) (str "no query shape for " (vec missing)))
                           (when (and (seq missing) (seq extra)) " and ")
                           (when (seq extra) (str "pages there are none of " (vec extra))))
                      {:type   :mailroom/bad-options
                       :errors {:query-shapes-of-pages (vec (concat (map #(str "no query shape for the page " %) missing)
                                                                    (map #(str "no page " % " in the pages") extra)))}}))))
  options)

(defn check-ws-options [options]
  (check-data-for-shape "The options" :mailroom/bad-options ws-options-shape options))
