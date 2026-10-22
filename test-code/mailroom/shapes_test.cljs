(ns mailroom.shapes-test
  "The browser's side, on node: a message goes out by its :params-shape, an
   answer comes back by its :answer-shape, and a named message finds its
   handler. Requiring mailroom.api proves the whole browser side compiles."
  (:require
   [cljs.test :refer [deftest is testing]]
   [mailroom.api]
   [mailroom.messages :as messages]
   [mailroom.shapes :as shapes]))

(def ^:private shapes-by-name
  {:shops/total {:params-shape [:map [:shop/id :uuid]]
                 :answer-shape [:map [:shop/id :uuid] [:total :decimal]]}})

(def ^:private shop-id "1d000000-0000-4000-8000-000000000001")

(deftest a-message-goes-out-by-its-shape
  (is (= {:shops/total {:shop/id shop-id}}
         (shapes/turn-message-into-json-data shapes-by-name {:shops/total {:shop/id shop-id}})))
  (testing "data that doesn't fit: nothing is sent"
    (is (= :mailroom/bad-params
           (try (shapes/turn-message-into-json-data shapes-by-name {:shops/total {:shop/id shop-id :extra 1}})
                (catch :default e (:type (ex-data e)))))))
  (testing "a name without a shape passes as it is"
    (is (= {:greetings/hello {:name "Anna"}}
           (shapes/turn-message-into-json-data shapes-by-name {:greetings/hello {:name "Anna"}})))))

(deftest an-answer-comes-back-by-its-shape
  (let [answer (shapes/turn-answer-into-shaped-data shapes-by-name {:shops/total {:shop/id shop-id :total "120.50"}})]
    (is (= {:shops/total {:shop/id shop-id :total "120.50"}} answer)
        "fits its shape; in the browser an id and a decimal are their text (wire)"))
  (testing "an answer that doesn't fit stays as it came, with its errors"
    (let [answer (shapes/turn-answer-into-shaped-data shapes-by-name {:shops/total {:shop/id "seven" :total "1"}})]
      (is (contains? (:shops/total answer) :mailroom/errors)))))

(deftest a-named-message-finds-its-handler
  (let [heard (atom [])]
    (messages/hand-named-messages! {:sync/changed #(swap! heard conj %)} {:sync/changed {:table "orders"}})
    (is (= [{:table "orders"}] @heard))))

(deftest a-ws-line-is-told-when-to-reopen
  (let [problems (try (mailroom.api/open-ws-line! "/mail/ws" shapes-by-name {} {})
                      (catch :default e (ex-data e)))]
    (is (= :mailroom/bad-options (:type problems)) "no :reopen-after-ms: refused before any socket opens")
    (is (contains? (:errors problems) :reopen-after-ms))))
