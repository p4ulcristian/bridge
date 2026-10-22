(ns mailroom.limits
  "Rate limits: a handler may say how often one asker may send its message,

     {:orders/place {:rate-limit {:max 10 :per-ms 60000} …}}

   ten in any minute, counted per asker: by the key find-key gives for the
   request (the app's :find-rate-limit-key: a session's, say, else the
   address find-client-address reads).
   A token bucket per message name and asker: it holds :max, refills at
   :max per :per-ms, and a message takes one. Kept in this server's memory,
   so behind several servers each counts its own."
  (:import
   (java.util.concurrent ConcurrentHashMap)))

(defn make-limiter
  "A limiter that tells askers apart by (find-key request)."
  [find-key]
  {:find-key find-key :buckets (ConcurrentHashMap.)})

(defn- take-token [{:keys [tokens at]} now {:keys [max per-ms]}]
  (let [refilled (min max (+ tokens (* (- now at) (/ max per-ms))))]
    (if (>= refilled 1)
      {:tokens (dec refilled) :at now :allowed? true}
      {:tokens refilled :at now :allowed? false})))

(defn- forget-full-buckets!
  "Buckets that have refilled hold nothing worth keeping."
  [^ConcurrentHashMap buckets now]
  (when (> (.size buckets) 10000)
    (doseq [[k bucket] (into {} buckets)
            :when (> (- now (:at bucket)) (:per-ms bucket))]
      (.remove buckets k))))

(defn is-message-allowed?
  "Whether this asker may send this message now; takes one from its bucket."
  [{:keys [find-key ^ConcurrentHashMap buckets]} request message-name {:keys [max] :as rate-limit}]
  (let [now (System/currentTimeMillis)
        k   [message-name (find-key request)]]
    (forget-full-buckets! buckets now)
    (:allowed? (.compute buckets k (reify java.util.function.BiFunction
                                     (apply [_ _ bucket]
                                       (assoc (take-token (or bucket {:tokens max :at now}) now rate-limit)
                                              :per-ms (:per-ms rate-limit))))))))

(defn find-client-address
  "The asker's address: the request's own, or behind a proxy the header it
   sets (\"cf-connecting-ip\"; for \"x-forwarded-for\", its last entry, the
   one the proxy added)."
  [request client-address-header]
  (if-let [header (some->> client-address-header (get (:headers request)))]
    (.trim ^String (last (.split ^String header ",")))
    (:remote-addr request)))
