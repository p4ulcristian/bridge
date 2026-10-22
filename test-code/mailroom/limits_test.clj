(ns mailroom.limits-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [mailroom.limits :as limits]))

(deftest a-bucket-per-message-and-asker
  (let [limiter (limits/make-limiter :remote-addr)
        ask     #(limits/is-message-allowed? limiter {:remote-addr %1} %2 {:max 2 :per-ms 60000})]
    (is (= [true true false] [(ask "1.1.1.1" :orders/place) (ask "1.1.1.1" :orders/place) (ask "1.1.1.1" :orders/place)]))
    (is (true? (ask "2.2.2.2" :orders/place)) "another asker has its own")
    (is (true? (ask "1.1.1.1" :orders/cancel)) "another message has its own")))

(deftest it-refills
  (let [limiter (limits/make-limiter :remote-addr)
        ask     #(limits/is-message-allowed? limiter {:remote-addr "1.1.1.1"} :x {:max 1 :per-ms 100})]
    (is (= [true false] [(ask) (ask)]))
    (Thread/sleep 150)
    (is (true? (ask)))))

(deftest the-asker-behind-a-proxy
  (testing "the proxy's header, its last entry; without a header setting, the request's own"
    (is (= "9.9.9.9" (limits/find-client-address {:remote-addr "10.0.0.1" :headers {"x-forwarded-for" "1.2.3.4, 9.9.9.9"}} "x-forwarded-for")))
    (is (= "5.5.5.5" (limits/find-client-address {:remote-addr "10.0.0.1" :headers {"cf-connecting-ip" "5.5.5.5"}} "cf-connecting-ip")))
    (is (= "10.0.0.1" (limits/find-client-address {:remote-addr "10.0.0.1" :headers {"x-forwarded-for" "1.2.3.4"}} nil)))))
