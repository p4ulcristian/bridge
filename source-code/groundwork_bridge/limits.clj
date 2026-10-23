(ns groundwork-bridge.limits
  "Rate limits: a handler may say how often one asker may send its message,

     {:orders/place {:rate-limit {:max 10 :per-ms 60000} …}}

   ten in any minute, counted per asker: by the key find-key gives for the
   request (the app's :find-rate-limit-key: a session's, say, else the
   address find-client-address reads).
   A token bucket per message name and asker: it holds :max, refills at
   :max per :per-ms, and a message takes one. Kept in this server's memory,
   so behind several servers each counts its own; above the settings'
   :rate-limit-buckets-before-cleanup buckets, the full ones are dropped.

   A plain Ring route (not a message: a POST that sets a cookie, say) is
   limited the same way by make-rate-limited-ring-handler, its own
   limiter, one bucket per asker, answered 429 over it."
  (:import
   (java.util.concurrent ConcurrentHashMap)))

(defrecord Limiter [find-key buckets-before-cleanup buckets])

(defn make-limiter
  "A limiter that tells askers apart by (find-key request) and keeps up to
   buckets-before-cleanup buckets before it drops the full ones. Each
   limiter has its own buckets."
  [find-key buckets-before-cleanup]
  (->Limiter find-key buckets-before-cleanup (ConcurrentHashMap.)))

(defn is-limiter?
  "Whether x is a limiter made by make-limiter."
  [x]
  (instance? Limiter x))

(defn- take-token
  "The bucket after one message: a token taken when there is one, else
   :wait-ms, how long until there is (at least 1)."
  [{:keys [tokens at]} now {most :max :keys [per-ms]}]
  (let [refilled (min most (+ tokens (* (- now at) (/ most per-ms))))]
    (if (>= refilled 1)
      {:tokens (dec refilled) :at now :wait-ms nil}
      {:tokens refilled :at now :wait-ms (max 1 (long (Math/ceil (double (/ (* (- 1 refilled) per-ms) most)))))})))

(defn- forget-full-buckets!
  "Buckets that have refilled hold nothing worth keeping."
  [^ConcurrentHashMap buckets buckets-before-cleanup now]
  (when (> (.size buckets) buckets-before-cleanup)
    (doseq [[k bucket] (into {} buckets)
            :when (> (- now (:at bucket)) (:per-ms bucket))]
      (.remove buckets k))))

(defn find-wait-ms
  "nil when this asker may send this message now, and one is taken from
   its bucket; else how long, in ms, until it may."
  [{:keys [find-key buckets-before-cleanup ^ConcurrentHashMap buckets]} request message-name {:keys [max] :as rate-limit}]
  (let [now (System/currentTimeMillis)
        k   [message-name (find-key request)]]
    (forget-full-buckets! buckets buckets-before-cleanup now)
    (:wait-ms (.compute buckets k (reify java.util.function.BiFunction
                                    (apply [_ _ bucket]
                                      (assoc (take-token (or bucket {:tokens max :at now}) now rate-limit)
                                             :per-ms (:per-ms rate-limit))))))))

(def too-many-requests
  "A plain route's answer over its rate limit."
  {:status 429 :headers {"content-type" "text/plain" "cache-control" "no-store"} :body "Too many requests."})

(defn make-rate-limited-ring-handler
  "ring-handler behind a limiter of its own: an asker over :rate-limit is
   answered 429 and ring-handler isn't called."
  [ring-handler {:keys [rate-limit find-rate-limit-key buckets-before-cleanup]}]
  (let [limiter (make-limiter find-rate-limit-key buckets-before-cleanup)]
    (fn [request]
      (if (nil? (find-wait-ms limiter request :route rate-limit))
        (ring-handler request)
        too-many-requests))))

(defn find-client-address
  "The asker's address: the request's own, or behind a proxy the header it
   sets (\"cf-connecting-ip\"; for \"x-forwarded-for\", its last entry, the
   one the proxy added)."
  [request client-address-header]
  (if-let [header (some->> client-address-header (get (:headers request)))]
    (.trim ^String (last (.split ^String header ",")))
    (:remote-addr request)))
