(ns bridge.steps
  "Work done as a list of steps over one map, the context.

   A step is {:name a-keyword :enter (fn [context] context)}: it takes the
   context and returns it, changed. The steps run in order, each getting
   what the one before returned, until one adds an answer; the steps after
   it don't run.")

(defn add-answer-to-context
  "The context with an answer; no step after this one runs."
  [context answer]
  (assoc context :bridge/answer answer))

(defn does-context-have-answer?
  "Whether a step has added an answer."
  [context]
  (contains? context :bridge/answer))

(defn- stop-when-answered
  ;; reduced tells reduce to stop here and return this context
  [context]
  (if (does-context-have-answer? context)
    (reduced context)
    context))

(defn run-steps-until-answered
  "Runs each step's :enter on the context, in order, until one adds an
   answer. Returns the last context."
  [steps context]
  (reduce (fn [context step]
            (stop-when-answered ((:enter step) context)))
          context
          steps))
