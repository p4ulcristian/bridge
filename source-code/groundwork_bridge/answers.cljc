(ns groundwork-bridge.answers
  "A refusal: what bridge answers instead of data, one definition for both
   sides. It is a named reason with its data, and the request id of the
   message it answers:

     {:groundwork-bridge/refused    :order/already-shipped
      :groundwork-bridge/data       {:order/shipped-at #inst \"…\"}    ; nil for a reason that carries none
      :groundwork-bridge/request-id \"5f0c…\"}

   A handler declares the reasons its steps may give, each with the shape
   of its data (nil: none), as it declares its answer:

     {:orders/ship {… :refusals {:order/already-shipped [:map [:order/shipped-at :time/instant]]
                                 :order/no-address      nil}}}

   bridge's own reasons are below, in refusals; a handler never declares
   them. The reason is the key of its text in the app's catalogs, and its
   data the text's arguments: no English travels."
  (:require
   [malli.core :as malli]
   [groundwork-shaped-json.api :as shaped-json]))

(def refusals
  "bridge's own reasons, each with the shape of its data, nil for none.
   The server makes the first three, the browser the last two:
   :groundwork-bridge/too-many   over the handler's :rate-limit: {:retry-after-ms n}, how long until one more is let through
   :groundwork-bridge/failed     a bug: a step threw, no step answered, an answer or a refusal didn't fit its shape,
                      a reason the handler doesn't declare, a name no handler has; the details are in the
                      log, under the refusal's :groundwork-bridge/request-id
   :groundwork-bridge/invalid    the params didn't fit the :params-shape: {:errors {:shop/id [\"should be an id\"]}}
   :groundwork-bridge/no-answer  no answer came: the network, or a body that isn't the server's JSON (a proxy's 502,
                      a 413): {:http-status n}, nil when no response came at all
   :groundwork-bridge/unfit      the server's answer didn't fit the browser's shapes, or its name has none:
                      {:errors …}"
  {:groundwork-bridge/too-many  [:map {:closed true} [:retry-after-ms :int]]
   :groundwork-bridge/failed    nil
   :groundwork-bridge/invalid   [:map {:closed true} [:errors :any]]
   :groundwork-bridge/no-answer [:map {:closed true} [:http-status [:maybe :int]]]
   :groundwork-bridge/unfit     [:map {:closed true} [:errors :any]]})

(defn make-refusal
  "The refusal of a reason with its data; the request id is added as it
   leaves."
  [reason data]
  {:groundwork-bridge/refused reason :groundwork-bridge/data data})

(defn is-error-answer?
  "Whether an answer is a refusal rather than data: a map carrying
   :groundwork-bridge/refused."
  [answer]
  (and (map? answer) (contains? answer :groundwork-bridge/refused)))

(defn does-shape-name-refused?
  "Whether a shape names :groundwork-bridge/refused anywhere: an :answer-shape may
   not, or data could read as a refusal."
  [shape]
  (boolean (some #{:groundwork-bridge/refused} (tree-seq coll? seq (if (malli/schema? shape) (malli/form shape) shape)))))

(defn- has-no-own-reasons? [handler-refusals]
  (or (not (map? handler-refusals)) (not-any? #(= "groundwork-bridge" (namespace %)) (filter keyword? (keys handler-refusals)))))

(def refusals-shape
  "A handler's :refusals: each reason its steps may give, a namespaced
   keyword, with the shape of its data, nil for none; {} for none. bridge's
   own reasons are bridge's, never a handler's."
  [:and
   [:map-of :qualified-keyword :any]
   [:fn {:error/message "bridge's own reasons (:bridge/…) are bridge's: a handler doesn't declare them"} has-no-own-reasons?]])

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
