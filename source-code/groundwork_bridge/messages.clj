(ns groundwork-bridge.messages
  "Named messages: {:orders/of-shop {:shop/id 7}}. Each name finds its
   handler in the table:

     {:orders/of-shop {:params-shape [:map [:shop/id :uuid]]
                       :answer-shape [:vector [:map [:order/id :uuid] [:order/total :decimal]]]
                       :refusals     {:shop/closed nil}
                       :steps        [find-orders-of-shop add-orders-answer]}}

   The data comes in as JSON data; by its :params-shape it is turned into
   shaped data (real UUIDs, decimals, dates) or refused :groundwork-steps/invalid,
   {:errors …} by key, before any step runs (a :params-shape of nil passes
   it as it is). The steps run over

     {:groundwork-steps/request      the request
      :groundwork-steps/message-name :orders/of-shop
      :groundwork-steps/message-data {:shop/id #uuid \"…\"}}

   with what every step may use, beside them:

     {:groundwork-steps/log  the app's log, with {:request-id \"…\"} as its context,
                     a new random id for each message (every name in it)
      :groundwork-steps/shaper the app's shaper}

   and the answer a step adds goes out as JSON data, by the :answer-shape
   (nil: as it is). A step may refuse instead (refuse): a reason the
   handler's :refusals declare, its data by the reason's shape. Each name
   runs apart, and every refusal carries the message's request id,
   :groundwork-steps/request-id. A refusal is written to the log, :info
   :groundwork-bridge/refused; a bug (a step threw, no step answered, an answer or a
   refusal that doesn't fit its shape, a reason the handler doesn't
   declare, a name no handler has) is written there with its details and
   answered :groundwork-steps/failed: a database's message never reaches the
   browser.

   A handler may carry notes about its answer, besides it: what the context
   holds at :groundwork-steps/notes when the steps end (the tables the answer was
   read from, say, put there by a library on top). They go out beside the
   answers, under one more name:

     {:orders/of-shop […] :groundwork-steps/notes {:orders/of-shop {:tables [\"orders\"]}}}"
  (:require
   [groundwork-logs.api :as logs]
   [groundwork-bridge.answers :as answers]
   [groundwork-steps.api :as steps]
   [groundwork-bridge.limits :as limits]
   [groundwork-shaped-json.api :as shaped-json]))

(defn- make-context [shaper log request message-name message-data]
  {:groundwork-steps/request      request
   :groundwork-steps/message-name message-name
   :groundwork-steps/message-data message-data
   :groundwork-steps/log          log
   :groundwork-steps/shaper         shaper})

(defn- turn-params-into-shaped-data-or-errors [shaper {:keys [params-shape]} json-data]
  (if params-shape
    (shaped-json/turn-json-data-into-shaped-data-or-errors shaper params-shape json-data)
    {:shaped-data json-data}))

(def ^:private failed (steps/make-refusal :groundwork-steps/failed nil))

(defn- answer-failed
  "A bug, written to the log with its details: answered :groundwork-steps/failed,
   counted as outcome."
  [log outcome event data]
  (log :error event data)
  {:answer failed :outcome outcome})

(defn- turn-answer-into-json-data
  "{:answer … :outcome :ok}, the answer as JSON data by its :answer-shape,
   checked against it with every map closed: an answer with a key the
   shape doesn't name is never sent (an extra column, a password hash);
   :groundwork-steps/failed, :unfit, when it doesn't fit."
  [shaper log message-name {:keys [answer-shape]} answer]
  (if answer-shape
    (let [{:keys [json-data errors]} (shaped-json/turn-shaped-data-into-json-data-or-errors shaper answer-shape answer)]
      (if errors
        (answer-failed log :unfit :groundwork-bridge/answer-unfit {:groundwork-steps/message-name message-name :errors errors})
        {:answer json-data :outcome :ok}))
    {:answer answer :outcome :ok}))

(defn- turn-refusal-into-json-data
  "{:answer … :outcome :refused}, a step's refusal with its data as JSON
   data, by the shape its handler's :refusals declare. A reason the
   handler doesn't declare, :groundwork-steps/failed, :failed; data that doesn't
   fit its shape, :groundwork-steps/failed, :unfit."
  [shaper log message-name {:keys [refusals]} {reason :groundwork-steps/refused data :groundwork-steps/data}]
  (if-not (contains? refusals reason)
    (answer-failed log :failed :groundwork-bridge/undeclared-refusal {:groundwork-steps/message-name message-name :groundwork-steps/refused reason})
    (let [turned (answers/turn-refusal-data-out shaper (get refusals reason) data)]
      (if (contains? turned :errors)
        (answer-failed log :unfit :groundwork-bridge/refusal-unfit {:groundwork-steps/message-name message-name :groundwork-steps/refused reason
                                                         :errors (:errors turned)})
        {:answer (steps/make-refusal reason (:data turned)) :outcome :refused}))))

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
  [shaper latest handler request message-name json-data]
  (let [{:keys [shaped-data errors]} (turn-params-into-shaped-data-or-errors shaper handler json-data)]
    (if errors
      {:answer (steps/make-refusal :groundwork-steps/invalid {:errors errors}) :outcome :invalid}
      (let [context (run-steps latest handler (make-context shaper (:groundwork-steps/log @latest) request message-name shaped-data))
            log     (or (:groundwork-steps/log context) (:groundwork-steps/log @latest))
            answer  (:groundwork-steps/answer context)]
        (assoc (cond
                 (not (steps/does-context-have-answer? context))
                 (answer-failed log :failed :groundwork-bridge/no-step-answered {:groundwork-steps/message-name message-name})

                 (steps/is-error-answer? answer)
                 (turn-refusal-into-json-data shaper log message-name handler answer)

                 :else
                 (turn-answer-into-json-data shaper log message-name handler answer))
               :notes (:groundwork-steps/notes context))))))

(defn- find-wait-ms
  "nil when the message may run; else how long until it may, in ms, over
   its :rate-limit. limiter is :no-rate-limits (nothing is counted) or a
   limiter (limits.clj): the server's, or one from make-message-limiter."
  [limiter request message-name {:keys [rate-limit]}]
  (when (and (not= :no-rate-limits limiter) rate-limit)
    (limits/find-wait-ms limiter request message-name rate-limit)))

(defn- run-named-message
  "[name {:answer … :notes …}]. Over its :rate-limit, :groundwork-steps/too-many and
   nothing runs. A failure is written with its details to the log the
   steps had got to (the message's, with whatever a step added) and
   answered :groundwork-steps/failed: a database's message never reaches the
   browser. A refusal is written there too, :info. Every refusal carries
   the message's request id. How it was answered and how long it took go
   to note-answer!."
  [shaper log request-id handlers limiter note-answer! request [message-name json-data]]
  (let [started (System/nanoTime)
        latest  (volatile! {:groundwork-steps/log log})
        result  (if-let [handler (get handlers message-name)]
                  (try (if-let [wait-ms (find-wait-ms limiter request message-name handler)]
                         {:answer (steps/make-refusal :groundwork-steps/too-many {:retry-after-ms wait-ms}) :outcome :too-many}
                         (run-handler shaper latest handler request message-name json-data))
                       (catch Exception e
                         (answer-failed (or (:groundwork-steps/log @latest) log) :failed
                                        :groundwork-bridge/handler-failed {:groundwork-steps/message-name message-name :error e})))
                  ;; :warn, not :error: a name from another version of the app, or a scanner's
                  (do (log :warn :groundwork-bridge/no-handler {:groundwork-steps/message-name message-name})
                      {:answer failed :outcome :no-handler}))
        answer  (:answer result)]
    (when (#{:refused :invalid :too-many} (:outcome result))
      ((or (:groundwork-steps/log @latest) log) :info :groundwork-bridge/refused {:groundwork-steps/message-name message-name
                                                             :groundwork-steps/refused      (:groundwork-steps/refused answer)}))
    (note-answer! message-name (:outcome result) (/ (- (System/nanoTime) started) 1e9))
    [message-name (cond-> result
                    (steps/is-error-answer? answer) (assoc-in [:answer :groundwork-steps/request-id] request-id))]))

(defn answer-named-messages
  "The answer to a message: the same names, each with its handler's answer
   or a refusal, and :groundwork-steps/notes beside them when a handler has notes.
   limiter is a limiter (limits.clj) counting each handler's :rate-limit:
   the server's, or one from make-message-limiter for answer-message; or
   :no-rate-limits, written out, and nothing is counted (tests, the REPL).
   groundwork-shaped-json is the app's, which the data comes in and the answers go out by;
   log the app's, which this message's log is made from, with a new
   request id as its context. The id is on every refusal,
   :groundwork-steps/request-id, and on no data.
   (note-answer! name outcome seconds) is called for each name answered
   (groundwork-bridge.metrics)."
  [shaper log handlers limiter note-answer! request message]
  (let [request-id  (str (random-uuid))
        request-log (logs/with-context log {:request-id request-id})
        results     (into {} (map #(run-named-message shaper request-log request-id handlers limiter note-answer! request %)) message)
        notes       (into {} (keep (fn [[message-name {:keys [notes]}]] (when notes [message-name notes]))) results)]
    (cond-> (update-vals results :answer)
      (seq notes) (assoc :groundwork-steps/notes notes))))
