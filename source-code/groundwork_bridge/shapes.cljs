(ns groundwork-bridge.shapes
  "The same shapes as the server's handlers, by name, in the browser:
   {:orders/of-shop {:params-shape … :answer-shape … :refusals {…}}}, each
   written out, nil where the data passes as it is, {} for no reasons. A
   message's data goes out as JSON data by its :params-shape; an answer
   comes back as shaped data by its :answer-shape, a refusal with its data
   by its reason's shape (the name's :refusals, or bridge's own). A name
   the table doesn't have is a mistake: going out it throws, nothing is
   sent; coming in it is refused :groundwork-bridge/unfit, as an answer or a refusal
   that doesn't fit. groundwork-shaped-json is the app's, which every shape is used by."
  (:require
   [groundwork-bridge.answers :as answers]
   [groundwork-shaped-json.api :as shaped-json]))

(defn- turn-each-name [message turn]
  (into {} (map (fn [[message-name data]] [message-name (turn message-name data)])) message))

(defn turn-message-into-json-data
  "Each name's data as JSON data, by its :params-shape. A name without
   shapes, or data that doesn't fit its shape or carries a key it doesn't
   name, throws: nothing is sent."
  [shaper shapes message]
  (turn-each-name message (fn [message-name data]
                            (when-not (contains? shapes message-name)
                              (throw (ex-info (str "No shapes named " message-name)
                                              {:type :groundwork-bridge/no-shapes :message-name message-name})))
                            (if-let [shape (get-in shapes [message-name :params-shape])]
                              (let [{:keys [json-data errors]} (shaped-json/turn-shaped-data-into-json-data-or-errors shaper shape data)]
                                (when errors
                                  (throw (ex-info (str message-name "'s params don't fit its shape")
                                                  {:type         :groundwork-bridge/bad-params
                                                   :message-name message-name
                                                   :errors       errors})))
                                json-data)
                              data))))

(defn- make-unfit
  "The browser's own refusal of an answer that doesn't fit: :groundwork-bridge/unfit
   with why, and the server's request id when it sent one."
  [errors request-id]
  (cond-> (answers/make-refusal :groundwork-bridge/unfit {:errors errors})
    request-id (assoc :groundwork-bridge/request-id request-id)))

(defn- turn-refusal-into-shaped-data
  "A refusal from the server, its reason a keyword again and its data
   shaped by the reason's shape: the name's :refusals, or bridge's own. A
   reason neither declares, or data that doesn't fit, :groundwork-bridge/unfit."
  [shaper name-refusals {reason :groundwork-bridge/refused data :groundwork-bridge/data request-id :groundwork-bridge/request-id}]
  (let [reason   (if (string? reason) (keyword reason) reason)
        refusals (merge answers/refusals name-refusals)]
    (if-not (and (keyword? reason) (contains? refusals reason))
      (make-unfit [(str "No refusal named " reason)] request-id)
      (let [turned (answers/turn-refusal-data-in shaper (get refusals reason) data)]
        (if (contains? turned :errors)
          (make-unfit (:errors turned) request-id)
          (cond-> (answers/make-refusal reason (:data turned))
            request-id (assoc :groundwork-bridge/request-id request-id)))))))

(defn turn-answer-into-shaped-data
  "Each name's answer as shaped data, by its :answer-shape. A refusal
   (:groundwork-bridge/refused) is shaped by its reason's shape instead: the server
   sends it past the :answer-shape. An answer or a refusal that doesn't
   fit, or whose name has no shapes, is refused :groundwork-bridge/unfit, with why.
   :groundwork-bridge/notes, bridge's own, passes as it is."
  [shaper shapes answer]
  (turn-each-name answer (fn [message-name data]
                           (cond
                             (= :groundwork-bridge/notes message-name)
                             data

                             (not (contains? shapes message-name))
                             (make-unfit [(str "No shapes named " message-name)] nil)

                             (answers/is-error-answer? data)
                             (turn-refusal-into-shaped-data shaper (get-in shapes [message-name :refusals]) data)

                             :else
                             (if-let [shape (get-in shapes [message-name :answer-shape])]
                               (let [{:keys [shaped-data errors]} (shaped-json/turn-json-data-into-shaped-data-or-errors shaper shape data)]
                                 (if errors (make-unfit errors nil) shaped-data))
                               data)))))

(defn make-no-answer
  "The answer to a message that got none, the browser's own refusal under
   each of its names: :groundwork-bridge/no-answer with the HTTP status, nil when no
   response came."
  [message http-status]
  (update-vals message (fn [_] (answers/make-refusal :groundwork-bridge/no-answer {:http-status http-status}))))
