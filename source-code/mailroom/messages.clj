(ns mailroom.messages
  "Named messages: {:orders/of-shop {:shop/id 7}}. Each name finds its
   handler in the table:

     {:orders/of-shop {:params-shape [:map [:shop/id :uuid]]
                       :answer-shape [:vector [:map [:order/id :uuid] [:order/total :decimal]]]
                       :steps        [find-orders-of-shop add-orders-answer]}}

   The data comes in as JSON data; with a :params-shape it is turned into
   shaped data (real UUIDs, decimals, dates) or answered {:errors …} by key
   before any step runs. The steps run over

     {:mailroom/request      the request
      :mailroom/message-name :orders/of-shop
      :mailroom/message-data {:shop/id #uuid \"…\"}}

   and the answer a step adds goes out as JSON data, by the :answer-shape
   when there is one. Each name runs apart: a failure answers {:error …}
   under its own name, and its details go to the log only.

   A handler may carry notes about its answer, besides it: what the context
   holds at :mailroom/notes when the steps end (the tables the answer was
   read from, say, put there by a library on top). They go out beside the
   answers, under one more name:

     {:orders/of-shop […] :mailroom/notes {:orders/of-shop {:tables [\"orders\"]}}}"
  (:require
   [mailroom.steps :as steps]
   [mailroom.limits :as limits]
   [wire.api :as wire])
  (:import
   (java.lang System$Logger System$Logger$Level)))

(defn- make-context [request message-name message-data]
  {:mailroom/request      request
   :mailroom/message-name message-name
   :mailroom/message-data message-data})

(defn- turn-params-into-shaped-data-or-errors [{:keys [params-shape]} json-data]
  (if params-shape
    (wire/turn-json-data-into-shaped-data-or-errors params-shape json-data)
    {:shaped-data json-data}))

(def ^:private answer-doesnt-fit
  {:error "The answer doesn't fit its shape."})

(defn- turn-answer-into-json-data
  "The answer as JSON data by its :answer-shape, checked against it with
   every map closed: an answer with a key the shape doesn't name is never
   sent (an extra column, a password hash)."
  [{:keys [answer-shape]} answer]
  (if answer-shape
    (let [{:keys [json-data errors]} (wire/turn-shaped-data-into-json-data-or-errors answer-shape answer)]
      (if errors answer-doesnt-fit json-data))
    answer))

(defn- run-steps-until-answered [handler-steps context]
  (let [context (steps/run-steps-until-answered handler-steps context)]
    (if (steps/does-context-have-answer? context)
      context
      (steps/add-answer-to-context context {:error "No step answered."}))))

(defn- is-only-an-error?
  "{:error \"…\"} and nothing else: a refusal, sent as it is."
  [answer]
  (and (map? answer) (= #{:error} (set (keys answer)))))

(defn- run-steps
  "The handler's steps over the context, to the context they end with. A
   handler's :run-steps-in, when it has one, is handed the running of them,
   to run them inside what it sets up (a transaction): (run-steps-in run
   context), returning the context run returned, or one with its own answer."
  [{handler-steps :steps :keys [run-steps-in]} context]
  (let [run #(run-steps-until-answered handler-steps %)]
    (if run-steps-in (run-steps-in run context) (run context))))

(defn- run-handler
  "{:answer … :notes …} for one named message."
  [handler request message-name json-data]
  (let [{:keys [shaped-data errors]} (turn-params-into-shaped-data-or-errors handler json-data)]
    (if errors
      {:answer {:errors errors}}
      (let [context (run-steps handler (make-context request message-name shaped-data))
            answer  (:mailroom/answer context)]
        {:answer (if (is-only-an-error? answer) answer (turn-answer-into-json-data handler answer))
         :notes  (:mailroom/notes context)}))))

(def ^:private ^System$Logger logger (System/getLogger "mailroom"))

(def ^:private went-wrong {:error "Something went wrong."})

(def ^:private too-many {:error "Too many requests."})

(defn- is-over-limit? [limiter request message-name {:keys [rate-limit]}]
  (and limiter rate-limit (not (limits/is-message-allowed? limiter request message-name rate-limit))))

(defn- run-named-message
  "[name {:answer … :notes …}]. Over its :rate-limit, \"Too many requests.\"
   and nothing runs. A failure is logged with its details and answered with
   a plain error: a database's message never reaches the browser."
  [handlers limiter request [message-name json-data]]
  [message-name
   (if-let [handler (get handlers message-name)]
     (try (if (is-over-limit? limiter request message-name handler)
            {:answer too-many}
            (run-handler handler request message-name json-data))
          (catch Exception e
            (.log logger System$Logger$Level/ERROR (str "Handler " message-name " failed") ^Throwable e)
            {:answer went-wrong}))
     {:answer {:error (str "No handler named " message-name)}})])

(defn answer-named-messages
  "The answer to a message: the same names, each with its handler's answer,
   and :mailroom/notes beside them when a handler has notes. With a
   limiter (limits.clj), a handler's :rate-limit holds."
  ([handlers request message] (answer-named-messages handlers nil request message))
  ([handlers limiter request message]
   (let [results (into {} (map #(run-named-message handlers limiter request %)) message)
         notes   (into {} (keep (fn [[message-name {:keys [notes]}]] (when notes [message-name notes]))) results)]
     (cond-> (update-vals results :answer)
       (seq notes) (assoc :mailroom/notes notes)))))
