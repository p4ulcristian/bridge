(ns bridge.messages
  "Named messages: {:orders/of-shop {:shop/id 7}}. Each name finds its
   handler in the table:

     {:orders/of-shop {:params-shape [:map [:shop/id :uuid]]
                       :answer-shape [:vector [:map [:order/id :uuid] [:order/total :decimal]]]
                       :steps        [find-orders-of-shop add-orders-answer]}}

   The data comes in as JSON data; by its :params-shape it is turned into
   shaped data (real UUIDs, decimals, dates) or answered {:errors …} by key
   before any step runs (a :params-shape of nil passes it as it is). The steps run over

     {:bridge/request      the request
      :bridge/message-name :orders/of-shop
      :bridge/message-data {:shop/id #uuid \"…\"}}

   with what every step may use, beside them:

     {:bridge/log  the app's log, with {:request-id \"…\"} as its context,
                     a new random id for each message (every name in it)
      :bridge/wire the app's wire}

   and the answer a step adds goes out as JSON data, by the :answer-shape
   (nil: as it is). Each name runs apart: a failure answers {:error …}
   under its own name, and its details go to the log only.

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

(def ^:private answer-doesnt-fit
  {:error "The answer doesn't fit its shape."})

(defn- turn-answer-into-json-data
  "[the answer as JSON data, :ok], by its :answer-shape, checked against
   it with every map closed: an answer with a key the shape doesn't name
   is never sent (an extra column, a password hash), [\"The answer doesn't
   fit its shape.\", :unfit]."
  [wire {:keys [answer-shape]} answer]
  (if answer-shape
    (let [{:keys [json-data errors]} (wire/turn-shaped-data-into-json-data-or-errors wire answer-shape answer)]
      (if errors [answer-doesnt-fit :unfit] [json-data :ok]))
    [answer :ok]))

(defn- run-steps-until-answered [handler-steps context]
  (let [context (steps/run-steps-until-answered handler-steps context)]
    (if (steps/does-context-have-answer? context)
      context
      (steps/add-answer-to-context context {:error "No step answered."}))))

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
        run         #(run-steps-until-answered noted-steps (vreset! latest %))]
    (if run-steps-in (run-steps-in run context) (run context))))

(defn- run-handler
  "{:answer … :notes … :outcome …} for one named message."
  [wire latest handler request message-name json-data]
  (let [{:keys [shaped-data errors]} (turn-params-into-shaped-data-or-errors wire handler json-data)]
    (if errors
      {:answer {:errors errors} :outcome :invalid}
      (let [context (run-steps latest handler (make-context wire (:bridge/log @latest) request message-name shaped-data))
            answer  (:bridge/answer context)
            [answer outcome] (if (answers/is-refusal? answer)
                               [answer :refused]
                               (turn-answer-into-json-data wire handler answer))]
        {:answer  answer
         :notes   (:bridge/notes context)
         :outcome outcome}))))

(def ^:private went-wrong {:error "Something went wrong."})

(def ^:private too-many {:error "Too many requests."})

(defn- is-over-limit?
  "limiter is the server's, or :no-rate-limits outside a server."
  [limiter request message-name {:keys [rate-limit]}]
  (and (not= :no-rate-limits limiter)
       rate-limit
       (not (limits/is-message-allowed? limiter request message-name rate-limit))))

(defn- run-named-message
  "[name {:answer … :notes …}]. Over its :rate-limit, \"Too many requests.\"
   and nothing runs. A failure is written with its details to the log the
   steps had got to (the message's, with whatever a step added) and
   answered with a plain error: a database's message never reaches the
   browser. How it was answered and how long it took go to note-answer!."
  [wire log handlers limiter note-answer! request [message-name json-data]]
  (let [started (System/nanoTime)
        result  (if-let [handler (get handlers message-name)]
                  (let [latest (volatile! {:bridge/log log})]
                    (try (if (is-over-limit? limiter request message-name handler)
                           {:answer too-many :outcome :too-many}
                           (run-handler wire latest handler request message-name json-data))
                         (catch Exception e
                           ((or (:bridge/log @latest) log) :error :bridge/handler-failed {:bridge/message-name message-name :error e})
                           {:answer went-wrong :outcome :failed})))
                  {:answer {:error (str "No handler named " message-name)} :outcome :no-handler})]
    (note-answer! message-name (:outcome result) (/ (- (System/nanoTime) started) 1e9))
    [message-name result]))

(defn answer-named-messages
  "The answer to a message: the same names, each with its handler's answer,
   and :bridge/notes beside them when a handler has notes. limiter is the
   server's (limits.clj), counting each handler's :rate-limit, or
   :no-rate-limits, written out, where there is no server (tests, the REPL).
   wire is the app's, which the data comes in and the answers go out by;
   log the app's, which this message's log is made from, with a new
   request id as its context. The id isn't in the answer.
   (note-answer! name outcome seconds) is called for each name answered
   (bridge.metrics)."
  [wire log handlers limiter note-answer! request message]
  (let [request-log (logbook/with-context log {:request-id (str (random-uuid))})
        results     (into {} (map #(run-named-message wire request-log handlers limiter note-answer! request %)) message)
        notes       (into {} (keep (fn [[message-name {:keys [notes]}]] (when notes [message-name notes]))) results)]
    (cond-> (update-vals results :answer)
      (seq notes) (assoc :bridge/notes notes))))
