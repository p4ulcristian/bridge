(ns mailroom.shapes
  "The same shapes as the server's handlers, by name, in the browser:
   {:orders/of-shop {:params-shape … :answer-shape …}}, both written out, nil
   where the data passes as it is. A message's data goes out as JSON data
   by its :params-shape; an answer comes back as shaped data by its
   :answer-shape. A name the table doesn't have is a mistake: going out it
   throws, nothing is sent; coming in it arrives with :mailroom/errors."
  (:require
   [wire.api :as wire]))

(defn- turn-each-name [message turn]
  (into {} (map (fn [[message-name data]] [message-name (turn message-name data)])) message))

(defn turn-message-into-json-data
  "Each name's data as JSON data, by its :params-shape. A name without
   shapes, or data that doesn't fit its shape or carries a key it doesn't
   name, throws: nothing is sent."
  [shapes message]
  (turn-each-name message (fn [message-name data]
                            (when-not (contains? shapes message-name)
                              (throw (ex-info (str "No shapes named " message-name)
                                              {:type :mailroom/no-shapes :message-name message-name})))
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
   doesn't fit, or whose name has no shapes, stays as it came, with
   :mailroom/errors added. :mailroom/notes, mailroom's own, passes as it is."
  [shapes answer]
  (turn-each-name answer (fn [message-name data]
                           (cond
                             (= :mailroom/notes message-name)
                             data

                             (not (contains? shapes message-name))
                             {:mailroom/errors [(str "No shapes named " message-name)] :data data}

                             :else
                             (if-let [shape (get-in shapes [message-name :answer-shape])]
                               (let [{:keys [shaped-data errors]} (wire/turn-json-data-into-shaped-data-or-errors shape data)]
                                 (if errors {:mailroom/errors errors :data data} shaped-data))
                               data)))))
