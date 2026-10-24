(ns groundwork-bridge.metrics
  "bridge's metrics, declared on the app's meter by
   make-routes-for-messages and by answer-message: each named message
   answered, by name, pipe and outcome (answer-message's under pipe http,
   as the same message over http); the time to answer it, by name; the
   open sse and ws lines. The names' set is the handler table's; a name
   outside it (one no handler has) is counted as \"other\"."
  (:require
   [groundwork-metrics.api :as metrics]))

(def outcomes
  "How a named message was answered:
   :ok         its answer, data
   :invalid    its params didn't fit their shape (:groundwork-steps/invalid)
   :refused    a step refused it, with a reason its handler declares
   :too-many   over its rate limit (:groundwork-steps/too-many)
   :failed     a step threw, no step answered, or a step refused with a reason its
               handler doesn't declare (:groundwork-steps/failed)
   :unfit      its answer didn't fit its :answer-shape, or its refusal's data its
               reason's shape (:groundwork-steps/failed)
   :no-handler no handler has its name (:groundwork-steps/failed)"
  #{:ok :invalid :refused :too-many :failed :unfit :no-handler})

(defn declare-metrics!
  "Declares bridge's metrics on the meter: the names' set is the
   handlers', the answer time's buckets the :metric-buckets of the map
   given (the settings, or answer-message's options). The same
   declaration again is no change; another is refused by meter."
  [meter handlers {{:keys [answer-seconds]} :metric-buckets}]
  (let [names (set (keys handlers))]
    (metrics/declare-metrics!
     meter
     [{:name    :groundwork-bridge/messages-total
       :type    :counter
       :help    "Named messages answered, by name, pipe and outcome."
       :labels  {:name names :pipe #{:http :ws} :outcome outcomes}
       :buckets nil
       :read    nil}
      {:name    :groundwork-bridge/answer-seconds
       :type    :histogram
       :help    "Time to answer a named message, in seconds, by name."
       :labels  {:name names}
       :buckets answer-seconds
       :read    nil}
      {:name    :groundwork-bridge/open-lines
       :type    :gauge
       :help    "Open sse and ws lines."
       :labels  {:pipe #{:sse :ws}}
       :buckets nil
       :read    nil}])))

(defn make-note-answer
  "(note-answer! message-name outcome seconds) for one pipe: counts the
   message and records its time."
  [meter pipe]
  (fn [message-name outcome seconds]
    (metrics/add-to-counter! meter :groundwork-bridge/messages-total {:name message-name :pipe pipe :outcome outcome} 1)
    (metrics/record-in-histogram! meter :groundwork-bridge/answer-seconds {:name message-name} seconds)))

(defn add-to-open-lines!
  "A line opened, +1; closed, -1."
  [meter pipe amount]
  (metrics/add-to-gauge! meter :groundwork-bridge/open-lines {:pipe pipe} amount))
