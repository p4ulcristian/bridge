(ns groundwork-bridge.test-meter
  "The tests' meter, made as an app makes its own, on the tests' log."
  (:require
   [clojure.string :as str]
   [groundwork-bridge.test-log :refer [log]]
   [groundwork-metrics.api :as metrics]))

(defn make-test-meter
  "A new meter: each test that reads its metrics makes its own."
  []
  (metrics/make-meter {:jvm-metrics false :most-series-per-metric 1000} {:log log}))

(def meter
  "The meter the tests that don't read it share."
  (make-test-meter))

(defn find-metric-lines
  "The text's sample lines of these metrics (by the written name's start)."
  [a-meter prefix]
  (filterv #(str/starts-with? % prefix) (str/split-lines (metrics/write-metrics-text a-meter))))

(defn with-new-meter
  "The options with a new meter: a meter takes one declaration of
   bridge's metrics, and the tests' handler tables differ."
  [options]
  (assoc options :meter (make-test-meter)))

(def metric-buckets
  "The tests' :metric-buckets for answer-message."
  {:answer-seconds [0.05 1]})

(defn with-meter-for-answers
  "answer-message's options with a new meter and the tests' buckets: the
   tests' handler tables differ, and a meter takes one."
  [options]
  (assoc options :meter (make-test-meter) :metric-buckets metric-buckets))
