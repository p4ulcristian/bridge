(ns groundwork-bridge.answers
  "A refusal on the wire, both sides. Its shape is groundwork-steps's, a
   named reason with its data, and the request id of the message it
   answers:

     {:groundwork-steps/refused    :order/already-shipped
      :groundwork-steps/data       {:order/shipped-at #inst \"…\"}    ; nil for a reason that carries none
      :groundwork-steps/request-id \"5f0c…\"}

   A handler declares the reasons its steps may give, each with the shape
   of its data (nil: none), as it declares its answer:

     {:orders/ship {… :refusals {:order/already-shipped [:map [:order/shipped-at :time/instant]]
                                 :order/no-address      nil}}}

   The reasons every answer may carry besides its handler's own are
   groundwork-steps's refusals, :groundwork-steps/…, which bridge gives; a
   handler never declares them. Here: what a shape may not name, a
   handler's :refusals as a shape, and a refusal's data turned by its
   reason's shape on its way out and in."
  (:require
   [malli.core :as malli]
   [groundwork-shaped-json.api :as shaped-json]))

(defn does-shape-name-refused?
  "Whether a shape names :groundwork-steps/refused anywhere: an :answer-shape may
   not, or data could read as a refusal."
  [shape]
  (boolean (some #{:groundwork-steps/refused} (tree-seq coll? seq (if (malli/schema? shape) (malli/form shape) shape)))))

(defn- has-no-shared-reasons? [handler-refusals]
  (or (not (map? handler-refusals)) (not-any? #(= "groundwork-steps" (namespace %)) (filter keyword? (keys handler-refusals)))))

(def refusals-shape
  "A handler's :refusals: each reason its steps may give, a namespaced
   keyword, with the shape of its data, nil for none; {} for none. The
   reasons every answer may carry (groundwork-steps's refusals,
   :groundwork-steps/…) are never a handler's."
  [:and
   [:map-of :qualified-keyword :any]
   [:fn {:error/message "the reasons every answer may carry (:groundwork-steps/…) are groundwork-steps's: a handler doesn't declare them"} has-no-shared-reasons?]])

(defn turn-refusal-data
  "A refusal's data by its reason's shape, through turn (groundwork-shaped-json's
   turn-shaped-data-into-json-data-or-errors on the way out,
   turn-json-data-into-shaped-data-or-errors on the way in): {:data …} or
   {:errors …}. A reason of nil shape carries nil."
  [turn shaper shape data]
  (if (nil? shape)
    (if (nil? data) {:data nil} {:errors ["should be nil: this reason carries no data"]})
    (let [turned (turn shaper shape data)]
      (if (contains? turned :errors)
        {:errors (:errors turned)}
        {:data (if (contains? turned :json-data) (:json-data turned) (:shaped-data turned))}))))

(defn turn-refusal-data-out
  "A refusal's data as JSON data, by its shape: {:data …} or {:errors …}."
  [shaper shape data]
  (turn-refusal-data shaped-json/turn-shaped-data-into-json-data-or-errors shaper shape data))

(defn turn-refusal-data-in
  "A refusal's JSON data as shaped data, by its shape: {:data …} or
   {:errors …}."
  [shaper shape data]
  (turn-refusal-data shaped-json/turn-json-data-into-shaped-data-or-errors shaper shape data))
