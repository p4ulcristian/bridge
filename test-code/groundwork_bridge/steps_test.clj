(ns groundwork-bridge.steps-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [groundwork-bridge.api :as bridge]))

(defn- add-to-trail [step-name]
  {:name  step-name
   :enter (fn [context] (update context :trail conj step-name))})

(deftest steps-run-in-order-until-answered
  (testing "every step runs, in order, each on what the one before returned"
    (is (= [:a :b :c]
           (:trail (bridge/run-steps-until-answered
                    [(add-to-trail :a) (add-to-trail :b) (add-to-trail :c)]
                    {:trail []})))))
  (testing "an answer stops the steps after it"
    (let [context (bridge/run-steps-until-answered
                   [(add-to-trail :a)
                    {:name :answer :enter #(bridge/add-answer-to-context % :done)}
                    (add-to-trail :c)]
                   {:trail []})]
      (is (= [:a] (:trail context)))
      (is (bridge/does-context-have-answer? context))
      (is (= :done (:groundwork-bridge/answer context)))))
  (testing "no steps: the context comes back as it was"
    (is (= {:trail []} (bridge/run-steps-until-answered [] {:trail []})))))
