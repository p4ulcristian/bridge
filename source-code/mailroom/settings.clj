(ns mailroom.settings
  "mailroom's settings, and the options its functions take, checked before
   anything starts. Nothing has a default: every setting is written out
   (nil where it is a choice), and a function mailroom needs is given; a
   missing one refuses, named, with every other problem at once."
  (:require
   [wire.api :as wire]))

(def settings-shape
  "The settings, every key required.
   :client-address-header: behind a proxy, the header it puts the asker's
   address in (\"cf-connecting-ip\", \"x-forwarded-for\"); nil when the
   server faces browsers itself.
   :addresses: where the three pipes are, {:http … :sse … :ws …}.
   :ping-every-ms: how often an idle sse line gets a comment and a ws line
   {:mailroom/ping {}}, so proxies don't cut them.
   :stop-wait-ms: how long stop-server! lets running requests finish before
   it cuts them (a slow report needs longer).
   :ws-reopen-after-ms: how long the browser waits before it reopens a
   broken ws line; the server doesn't read it, the app hands it to the
   browser with the addresses (open-ws-line!'s :reopen-after-ms).
   :rate-limit-buckets-before-cleanup: rate limits keep a bucket per message
   name and asker in memory; above this many, each message first drops the
   buckets that have refilled full."
  [:map {:closed true}
   [:ip :string]
   [:port :int]
   [:client-address-header [:maybe :string]]
   [:addresses [:map {:closed true}
                [:http :string]
                [:sse :string]
                [:ws :string]]]
   [:ping-every-ms [:int {:min 1}]]
   [:stop-wait-ms [:int {:min 0}]]
   [:ws-reopen-after-ms [:int {:min 1}]]
   [:rate-limit-buckets-before-cleanup [:int {:min 1}]]])

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
  "make-ring-handler-from-routes's options. :files-folder: the folder on
   disk a GET no route matches is answered from, or nil for none."
  [:map {:closed true}
   [:files-folder [:maybe :string]]])

(defn- check-data-for-shape [what kind shape data]
  (when-let [errors (wire/find-errors-in-data-for-shape shape data)]
    (throw (ex-info (str what " have problems: " (pr-str errors))
                    {:type kind :errors errors})))
  data)

(defn check-settings
  "Returns the settings; throws with every problem at once."
  [settings]
  (check-data-for-shape "The settings" :mailroom/bad-settings settings-shape settings))

(defn check-routes-options
  "Returns the options; throws with every problem at once."
  [options]
  (check-data-for-shape "The options" :mailroom/bad-options routes-options-shape options))

(defn check-ring-handler-options
  "Returns the options; throws with every problem at once."
  [options]
  (check-data-for-shape "The options" :mailroom/bad-options ring-handler-options-shape options))
