(ns bridge.messages
  "Named messages: {:orders/of-shop {:shop/id 7}}. Each name finds its
   handler in the table:

     {:orders/of-shop {:params-shape [:map [:shop/id :uuid]]
                       :answer-shape [:vector [:map [:order/id :uuid] [:order/total :decimal]]]
                       :refusals     {:shop/closed nil}
                       :steps        [find-orders-of-shop add-orders-answer]}}

   The data comes in as JSON data; by its :params-shape it is turned into
   shaped data (real UUIDs, decimals, dates) or refused :bridge/invalid,
   {:errors …} by key, before any step runs (a :params-shape of nil passes
   it as it is). The steps run over

     {:bridge/request      the request
      :bridge/message-name :orders/of-shop
      :bridge/message-data {:shop/id #uuid \"…\"}}

   with what every step may use, beside them:

     {:bridge/log  the app's log, with {:request-id \"…\"} as its context,
                     a new random id for each message (every name in it)
      :bridge/wire the app's wire}

   and the answer a step adds goes out as JSON data, by the :answer-shape
   (nil: as it is). A step may refuse instead (refuse): a reason the
   handler's :refusals declare, its data by the reason's shape. Each name
   runs apart, and every refusal carries the message's request id,
   :bridge/request-id. A refusal is written to the log, :info
   :bridge/refused; a bug (a step threw, no step answered, an answer or a
   refusal that doesn't fit its shape, a reason the handler doesn't
   declare, a name no handler has) is written there with its details and
   answered :bridge/failed: a database's message never reaches the
   browser.

   A handler may carry notes about its answer, besides it: what the context
   holds at :bridge/notes when the steps end (the tables the answer was
   read from, say, put there by a library on top). They go out beside the
   answers, under one more name:

     {:orders/of-shop […] :bridge/notes {:orders/of-shop {:tables [\"orders\"]}}}"
  (:require
   [logbook.api :as logbook]
   [bridge.answers :as answers]
   [bridge.steps :as steps]
   [bridge.limits :as limits]
   [wire.api :as wire]))

(defn- make-context [wire log request message-name message-data]
  {:bridge/request      request
   :bridge/message-name message-name
   :bridge/message-data message-data
   :bridge/log          log
   :bridge/wire         wire})

(defn- turn-params-into-shaped-data-or-errors [wire {:keys [params-shape]} json-data]
  (if params-shape
    (wire/turn-json-data-into-shaped-data-or-errors wire params-shape json-data)
    {:shaped-data json-data}))

(def ^:private failed (answers/make-refusal :bridge/failed nil))

(defn- answer-failed
  "A bug, written to the log with its details: answered :bridge/failed,
   counted as outcome."
  [log outcome event data]
  (log :error event data)
  {:answer failed :outcome outcome})

(defn- turn-answer-into-json-data
  "{:answer … :outcome :ok}, the answer as JSON data by its :answer-shape,
   checked against it with every map closed: an answer with a key the
   shape doesn't name is never sent (an extra column, a password hash);
   :bridge/failed, :unfit, when it doesn't fit."
  [wire log message-name {:keys [answer-shape]} answer]
  (if answer-shape
    (let [{:keys [json-data errors]} (wire/turn-shaped-data-into-json-data-or-errors wire answer-shape answer)]
      (if errors
        (answer-failed log :unfit :bridge/answer-unfit {:bridge/message-name message-name :errors errors})
        {:answer json-data :outcome :ok}))
    {:answer answer :outcome :ok}))

(defn- turn-refusal-into-json-data
  "{:answer … :outcome :refused}, a step's refusal with its data as JSON
   data, by the shape its handler's :refusals declare. A reason the
   handler doesn't declare, :bridge/failed, :failed; data that doesn't
   fit its shape, :bridge/failed, :unfit."
  [wire log message-name {:keys [refusals]} {reason :bridge/refused data :bridge/data}]
  (if-not (contains? refusals reason)
    (answer-failed log :failed :bridge/undeclared-refusal {:bridge/message-name message-name :bridge/refused reason})
    (let [turned (answers/turn-refusal-data-out wire (get refusals reason) data)]
      (if (contains? turned :errors)
        (answer-failed log :unfit :bridge/refusal-unfit {:bridge/message-name message-name :bridge/refused reason
                                                         :errors (:errors turned)})
        {:answer (answers/make-refusal reason (:data turned)) :outcome :refused}))))

(defn- note-each-context
  "The steps, each noting the context it returns in latest, so a failure is
   written to the log the steps had got to (one that knows who is asking)."
  [latest handler-steps]
  (mapv (fn [{:keys [enter] :as step}]
          (assoc step :enter (fn [context] (vreset! latest (enter context)))))
        handler-steps))

(defn- run-steps
  "The handler's steps over the context, to the context they end with. A
   handler's :run-steps-in, when it isn't nil, is handed the running of them,
   to run them inside what it sets up (a transaction): (run-steps-in run
   context), returning the context run returned, or one with its own answer.
   latest holds the newest context the steps were handed or returned."
  [latest {handler-steps :steps :keys [run-steps-in]} context]
  (let [noted-steps (note-each-context latest handler-steps)
        run         #(steps/run-steps-until-answered noted-steps (vreset! latest %))]
    (if run-steps-in (run-steps-in run context) (run context))))

(defn- run-handler
  "{:answer … :notes … :outcome …} for one named message."
  [wire latest handler request message-name json-data]
  (let [{:keys [shaped-data errors]} (turn-params-into-shaped-data-or-errors wire handler json-data)]
    (if errors
      {:answer (answers/make-refusal :bridge/invalid {:errors errors}) :outcome :invalid}
      (let [context (run-steps latest handler (make-context wire (:bridge/log @latest) request message-name shaped-data))
            log     (or (:bridge/log context) (:bridge/log @latest))
            answer  (:bridge/answer context)]
        (assoc (cond
                 (not (steps/does-context-have-answer? context))
                 (answer-failed log :failed :bridge/no-step-answered {:bridge/message-name message-name})

                 (answers/is-error-answer? answer)
                 (turn-refusal-into-json-data wire log message-name handler answer)

                 :else
                 (turn-answer-into-json-data wire log message-name handler answer))
               :notes (:bridge/notes context))))))

(defn- find-wait-ms
  "nil when the message may run; else how long until it may, in ms, over
   its :rate-limit. limiter is :no-rate-limits (nothing is counted) or a
   limiter (limits.clj): the server's, or one from make-message-limiter."
  [limiter request message-name {:keys [rate-limit]}]
  (when (and (not= :no-rate-limits limiter) rate-limit)
    (limits/find-wait-ms limiter request message-name rate-limit)))

(defn- run-named-message
  "[name {:answer … :notes …}]. Over its :rate-limit, :bridge/too-many and
   nothing runs. A failure is written with its details to the log the
   steps had got to (the message's, with whatever a step added) and
   answered :bridge/failed: a database's message never reaches the
   browser. A refusal is written there too, :info. Every refusal carries
   the message's request id. How it was answered and how long it took go
   to note-answer!."
  [wire log request-id handlers limiter note-answer! request [message-name json-data]]
  (let [started (System/nanoTime)
        latest  (volatile! {:bridge/log log})
        result  (if-let [handler (get handlers message-name)]
                  (try (if-let [wait-ms (find-wait-ms limiter request message-name handler)]
                         {:answer (answers/make-refusal :bridge/too-many {:retry-after-ms wait-ms}) :outcome :too-many}
                         (run-handler wire latest handler request message-name json-data))
                       (catch Exception e
                         (answer-failed (or (:bridge/log @latest) log) :failed
                                        :bridge/handler-failed {:bridge/message-name message-name :error e})))
                  ;; :warn, not :error: a name from another version of the app, or a scanner's
                  (do (log :warn :bridge/no-handler {:bridge/message-name message-name})
                      {:answer failed :outcome :no-handler}))
        answer  (:answer result)]
    (when (#{:refused :invalid :too-many} (:outcome result))
      ((or (:bridge/log @latest) log) :info :bridge/refused {:bridge/message-name message-name
                                                             :bridge/refused      (:bridge/refused answer)}))
    (note-answer! message-name (:outcome result) (/ (- (System/nanoTime) started) 1e9))
    [message-name (cond-> result
                    (answers/is-error-answer? answer) (assoc-in [:answer :bridge/request-id] request-id))]))

(defn answer-named-messages
  "The answer to a message: the same names, each with its handler's answer
   or a refusal, and :bridge/notes beside them when a handler has notes.
   limiter is a limiter (limits.clj) counting each handler's :rate-limit:
   the server's, or one from make-message-limiter for answer-message; or
   :no-rate-limits, written out, and nothing is counted (tests, the REPL).
   wire is the app's, which the data comes in and the answers go out by;
   log the app's, which this message's log is made from, with a new
   request id as its context. The id is on every refusal,
   :bridge/request-id, and on no data.
   (note-answer! name outcome seconds) is called for each name answered
   (bridge.metrics)."
  [wire log handlers limiter note-answer! request message]
  (let [request-id  (str (random-uuid))
        request-log (logbook/with-context log {:request-id request-id})
        results     (into {} (map #(run-named-message wire request-log request-id handlers limiter note-answer! request %)) message)
        notes       (into {} (keep (fn [[message-name {:keys [notes]}]] (when notes [message-name notes]))) results)]
    (cond-> (update-vals results :answer)
      (seq notes) (assoc :bridge/notes notes))))
