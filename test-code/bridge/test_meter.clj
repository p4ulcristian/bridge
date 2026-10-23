(ns bridge.test-meter
  "The tests' meter, made as an app makes its own, on the tests' log."
  (:require
   [clojure.string :as str]
   [bridge.test-log :refer [log]]
   [meter.api :as meter]))

(defn make-test-meter
  "A new meter: each test that reads its metrics makes its own."
  []
  (meter/make-meter {:jvm-metrics false :most-series-per-metric 1000} {:log log}))

(def meter
  "The meter the tests that don't read it share."
  (make-test-meter))

(defn find-metric-lines
  "The text's sample lines of these metrics (by the written name's start)."
  [a-meter prefix]
  (filterv #(str/starts-with? % prefix) (str/split-lines (meter/write-metrics-text a-meter))))

(defn with-new-meter
  "The options with a new meter: a meter takes one declaration of
   bridge's metrics, and the tests' handler tables differ."
  [options]
  (assoc options :meter (make-test-meter)))
