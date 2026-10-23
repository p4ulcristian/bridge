(ns mailroom.limits-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [mailroom.api :as mailroom]
   [mailroom.limits :as limits]))

(deftest a-bucket-per-message-and-asker
  (let [limiter (limits/make-limiter :remote-addr 10000)
        ask     #(limits/is-message-allowed? limiter {:remote-addr %1} %2 {:max 2 :per-ms 60000})]
    (is (= [true true false] [(ask "1.1.1.1" :orders/place) (ask "1.1.1.1" :orders/place) (ask "1.1.1.1" :orders/place)]))
    (is (true? (ask "2.2.2.2" :orders/place)) "another asker has its own")
    (is (true? (ask "1.1.1.1" :orders/cancel)) "another message has its own")))

(deftest it-refills
  (let [limiter (limits/make-limiter :remote-addr 10000)
        ask     #(limits/is-message-allowed? limiter {:remote-addr "1.1.1.1"} :x {:max 1 :per-ms 100})]
    (is (= [true false] [(ask) (ask)]))
    (Thread/sleep 150)
    (is (true? (ask)))))

(deftest full-buckets-are-dropped-above-the-setting
  (let [limiter (limits/make-limiter :remote-addr 2)
        ask     #(limits/is-message-allowed? limiter {:remote-addr %} :x {:max 1 :per-ms 50})]
    (run! ask ["1.1.1.1" "2.2.2.2" "3.3.3.3"])
    (is (= 3 (.size (:buckets limiter))) "at most the setting, nothing is dropped")
    (Thread/sleep 100)
    (ask "4.4.4.4")
    (is (= 1 (.size (:buckets limiter))) "above it, the refilled ones go before the next one counts")))

(deftest the-asker-behind-a-proxy
  (testing "the proxy's header, its last entry; without a header setting, the request's own"
    (is (= "9.9.9.9" (limits/find-client-address {:remote-addr "10.0.0.1" :headers {"x-forwarded-for" "1.2.3.4, 9.9.9.9"}} "x-forwarded-for")))
    (is (= "5.5.5.5" (limits/find-client-address {:remote-addr "10.0.0.1" :headers {"cf-connecting-ip" "5.5.5.5"}} "cf-connecting-ip")))
    (is (= "10.0.0.1" (limits/find-client-address {:remote-addr "10.0.0.1" :headers {"x-forwarded-for" "1.2.3.4"}} nil)))))

(deftest a-plain-route-is-limited-by-a-limiter-of-its-own
  (let [called  (atom 0)
        handler (mailroom/make-rate-limited-ring-handler (fn [_] (swap! called inc) {:status 200 :headers {} :body "ok"})
                                                         {:rate-limit             {:max 2 :per-ms 60000}
                                                          :find-rate-limit-key    #(mailroom/find-client-address % "x-forwarded-for")
                                                          :buckets-before-cleanup 10000})
        ask     #(handler {:remote-addr "10.0.0.1" :headers {"x-forwarded-for" %}})]
    (is (= [200 200 429] (map :status [(ask "1.1.1.1") (ask "1.1.1.1") (ask "1.1.1.1")])))
    (is (= {:status 429 :headers {"content-type" "text/plain" "cache-control" "no-store"} :body "Too many requests."}
           (ask "1.1.1.1")))
    (is (= 2 @called) "over the limit, the route isn't called")
    (is (= 200 (:status (ask "2.2.2.2"))) "another asker has its own"))
  (testing "its options, every key required"
    (is (= {:type :mailroom/bad-options :errors {:buckets-before-cleanup ["missing required key"]}}
           (select-keys (ex-data (try (mailroom/make-rate-limited-ring-handler identity {:rate-limit {:max 1 :per-ms 1}
                                                                                         :find-rate-limit-key (fn [request] (:remote-addr request))})
                                      (catch Exception e e)))
                        [:type :errors])))))
