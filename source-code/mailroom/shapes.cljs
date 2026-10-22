(ns mailroom.shapes
  "The same shapes as the server's handlers, by name, in the browser:
   {:orders/of-shop {:params-shape … :answer-shape …}}. A message's data
   goes out as JSON data by its :params-shape; an answer comes back as
   shaped data by its :answer-shape. A name without a shape passes as it is."
  (:require
   [wire.api :as wire]))

(defn- turn-each-name [message turn]
  (into {} (map (fn [[message-name data]] [message-name (turn message-name data)])) message))

(defn turn-message-into-json-data
  "Each name's data as JSON data, by its :params-shape. Data that doesn't
   fit it, or carries a key it doesn't name, throws: nothing is sent."
  [shapes message]
  (turn-each-name message (fn [message-name data]
                            (if-let [shape (get-in shapes [message-name :params-shape])]
                              (let [{:keys [json-data errors]} (wire/turn-shaped-data-into-json-data-or-errors shape data)]
                                (when errors
                                  (throw (ex-info (str message-name "'s params don't fit its shape")
                                                  {:type         :mailroom/bad-params
                                                   :message-name message-name
                                                   :errors       errors})))
                                json-data)
                              data))))

(defn turn-answer-into-shaped-data
  "Each name's answer as shaped data, by its :answer-shape. An answer that
   doesn't fit stays as it came, with :mailroom/errors added."
  [shapes answer]
  (turn-each-name answer (fn [message-name data]
                           (if-let [shape (get-in shapes [message-name :answer-shape])]
                             (let [{:keys [shaped-data errors]} (wire/turn-json-data-into-shaped-data-or-errors shape data)]
                               (if errors {:mailroom/errors errors :data data} shaped-data))
                             data))))
