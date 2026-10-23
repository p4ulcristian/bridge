(ns bridge.shapes-test
  "The browser's side, on node: a message goes out by its :params-shape, an
   answer comes back by its :answer-shape, and a named message finds its
   handler. Requiring bridge.api proves the whole browser side compiles."
  (:require
   [cljs.test :refer [deftest is testing]]
   [bridge.api]
   [bridge.messages :as messages]
   [bridge.shapes :as shapes]
   [bridge.test-wire :refer [wire]]))

(def ^:private shapes-by-name
  {:shops/total     {:params-shape [:map [:shop/id :uuid]]
                     :answer-shape [:map [:shop/id :uuid] [:total :decimal]]}
   :greetings/hello {:params-shape nil :answer-shape nil}})

(defn- find-problems [f]
  (try (f) nil
       (catch :default e (ex-data e))))

(def ^:private shop-id "1d000000-0000-4000-8000-000000000001")

(deftest a-message-goes-out-by-its-shape
  (is (= {:shops/total {:shop/id shop-id}}
         (shapes/turn-message-into-json-data wire shapes-by-name {:shops/total {:shop/id shop-id}})))
  (testing "data that doesn't fit: nothing is sent"
    (is (= :bridge/bad-params
           (try (shapes/turn-message-into-json-data wire shapes-by-name {:shops/total {:shop/id shop-id :extra 1}})
                (catch :default e (:type (ex-data e)))))))
  (testing "a name whose shape is nil, written out, passes as it is"
    (is (= {:greetings/hello {:name "Anna"}}
           (shapes/turn-message-into-json-data wire shapes-by-name {:greetings/hello {:name "Anna"}}))))
  (testing "a name the shapes don't have: nothing is sent"
    (is (= :bridge/no-shapes
           (:type (find-problems #(shapes/turn-message-into-json-data wire shapes-by-name {:nobody/home {}})))))))

(deftest an-answer-comes-back-by-its-shape
  (let [answer (shapes/turn-answer-into-shaped-data wire shapes-by-name {:shops/total {:shop/id shop-id :total "120.50"}})]
    (is (= {:shops/total {:shop/id shop-id :total "120.50"}} answer)
        "fits its shape; in the browser an id and a decimal are their text (wire)"))
  (testing "an answer that doesn't fit stays as it came, with its errors"
    (let [answer (shapes/turn-answer-into-shaped-data wire shapes-by-name {:shops/total {:shop/id "seven" :total "1"}})]
      (is (contains? (:shops/total answer) :bridge/errors))))
  (testing "an error answer the server made instead of data passes as it is"
    (is (= {:shops/total {:error "Not found."}}
           (shapes/turn-answer-into-shaped-data wire shapes-by-name {:shops/total {:error "Not found."}})))
    (is (= {:shops/total {:errors {:shop/id ["should be a uuid"]}}}
           (shapes/turn-answer-into-shaped-data wire shapes-by-name {:shops/total {:errors {:shop/id ["should be a uuid"]}}}))))
  (testing "a name the shapes don't have: as it came, with its errors; notes pass"
    (let [answer (shapes/turn-answer-into-shaped-data wire shapes-by-name {:nobody/home {:a 1} :bridge/notes {:x 1}})]
      (is (= {:a 1} (get-in answer [:nobody/home :data])))
      (is (contains? (:nobody/home answer) :bridge/errors))
      (is (= {:x 1} (:bridge/notes answer))))))

(deftest a-named-message-finds-its-handler
  (let [heard (atom [])]
    (messages/hand-named-messages! {:sync/changed #(swap! heard conj %)} {:sync/changed {:table "orders"}})
    (is (= [{:table "orders"}] @heard))))

(deftest a-ws-line-is-told-when-to-reopen
  (let [problems (find-problems #(bridge.api/open-ws-line! "/mail/ws" shapes-by-name {} {:wire wire}))]
    (is (= :bridge/bad-options (:type problems)) "no :reopen-after-ms: refused before any socket opens")
    (is (contains? (:errors problems) :reopen-after-ms))))

(deftest an-sse-line-says-its-on-open
  (let [problems (find-problems #(bridge.api/open-sse-line! "/mail/sse" shapes-by-name {} {:wire wire}))]
    (is (= :bridge/bad-options (:type problems)) "no :on-open, not even nil: refused before the line opens")
    (is (contains? (:errors problems) :on-open))))

(deftest each-name-says-both-shapes
  (let [problems (find-problems #(bridge.api/send-message! "/mail/http" {:shops/total {:params-shape nil}} {:shops/total {}} {:wire wire}))]
    (is (= :bridge/bad-shapes (:type problems)) "refused before anything is sent")
    (is (= #{:answer-shape} (set (keys (:shops/total (:errors problems))))))))

(deftest every-line-and-message-takes-the-apps-wire
  (doseq [[what call] {"send-message!" #(bridge.api/send-message! "/mail/http" shapes-by-name {:greetings/hello {}} {})
                       "open-sse-line!" #(bridge.api/open-sse-line! "/mail/sse" shapes-by-name {} {:on-open nil :reconnect-after-ms 20})
                       "open-ws-line!" #(bridge.api/open-ws-line! "/mail/ws" shapes-by-name {} {:reopen-after-ms 20})}]
    (let [problems (find-problems call)]
      (is (= :bridge/bad-options (:type problems)) (str what ": refused before anything is sent or opened"))
      (is (contains? (:errors problems) :wire) (str what ": bridge makes no wire")))))

(deftest more-headers-go-with-a-message
  (testing ":headers is written out, {} for none; accept and content-type are bridge's"
    (is (contains? (:errors (find-problems #(bridge.api/send-message! "/mail/http" shapes-by-name {:greetings/hello {}} {:wire wire})))
                   :headers))
    (is (= :bridge/bad-options
           (:type (find-problems #(bridge.api/send-message! "/mail/http" shapes-by-name {:greetings/hello {}}
                                                              {:wire wire :headers {"content-type" "text/plain"}})))))
    (is (= :bridge/bad-options
           (:type (find-problems #(bridge.api/send-message! "/mail/http" shapes-by-name {:greetings/hello {}}
                                                              {:wire wire :headers {"Idempotency-Key" "k"}}))))
        "names in lowercase"))
  (testing "they go out beside bridge's own"
    (let [sent  (atom nil)
          fetch (.-fetch js/globalThis)]
      (set! (.-fetch js/globalThis) (fn [address options]
                                      (reset! sent [address (js->clj (.-headers options))])
                                      (js/Promise.resolve #js {:text (fn [] (js/Promise.resolve "{\"greetings/hello\":1}"))})))
      (try
        (bridge.api/send-message! "/mail/http" shapes-by-name {:greetings/hello {}} {:wire wire :headers {"idempotency-key" "k-1"}})
        (is (= ["/mail/http" {"idempotency-key" "k-1" "accept" "application/json" "content-type" "application/json"}] @sent))
        (finally (set! (.-fetch js/globalThis) fetch))))))

(deftest an-error-answer-is-told-from-data
  (testing "bridge's error answers"
    (doseq [answer [{:error "Not found."}
                    {:errors {:shop/id ["should be an id"]}}
                    {:bridge/errors ["No shapes named :nobody/home"] :data {:a 1}}
                    (:shops/total (shapes/turn-answer-into-shaped-data wire shapes-by-name {:shops/total {:shop/id "seven" :total "1"}}))]]
      (is (true? (bridge.api/is-error-answer? answer)) (pr-str answer))))
  (testing "data, even data with an :error key beside others"
    (doseq [answer [{:error "x" :order/id 1} {:errors [] :total 2} [] nil "Not found." 7 {}]]
      (is (false? (bridge.api/is-error-answer? answer)) (pr-str answer)))))
