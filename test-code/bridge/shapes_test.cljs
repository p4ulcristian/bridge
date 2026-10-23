(ns bridge.shapes-test
  "The browser's side, on node: a message goes out by its :params-shape, an
   answer comes back by its :answer-shape, and a named message finds its
   handler. Requiring bridge.api proves the whole browser side compiles."
  (:require
   [cljs.test :refer [async deftest is testing]]
   [bridge.api]
   [bridge.messages :as messages]
   [bridge.shapes :as shapes]
   [bridge.test-wire :refer [wire]]
   [wire.time]))

(def ^:private shapes-by-name
  {:shops/total     {:params-shape [:map [:shop/id :uuid]]
                     :answer-shape [:map [:shop/id :uuid] [:total :decimal]]
                     :refusals     {:shop/closed [:map [:shop/opens-at :time/instant]]
                                    :shop/gone   nil}}
   :greetings/hello {:params-shape nil :answer-shape nil :refusals {}}})

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
  (testing "an answer that doesn't fit: :bridge/unfit, with why"
    (let [answer (shapes/turn-answer-into-shaped-data wire shapes-by-name {:shops/total {:shop/id "seven" :total "1"}})]
      (is (= :bridge/unfit (get-in answer [:shops/total :bridge/refused])))
      (is (contains? (get-in answer [:shops/total :bridge/data :errors]) :shop/id))))
  (testing "a refusal passes the answer shape: its reason a keyword, its data by its reason's shape"
    (let [{:shops/keys [total]} (shapes/turn-answer-into-shaped-data
                                 wire shapes-by-name
                                 {:shops/total {:bridge/refused "shop/closed" :bridge/data {:shop/opens-at "2026-10-24T08:00:00Z"}
                                                :bridge/request-id "r-1"}})]
      (is (= [:shop/closed "r-1"] [(:bridge/refused total) (:bridge/request-id total)]))
      (is (instance? wire.time/Instant (get-in total [:bridge/data :shop/opens-at])) "shaped, as data is"))
    (is (= {:shops/total {:bridge/refused :shop/gone :bridge/data nil :bridge/request-id "r-2"}}
           (shapes/turn-answer-into-shaped-data wire shapes-by-name {:shops/total {:bridge/refused "shop/gone" :bridge/data nil :bridge/request-id "r-2"}})))
    (is (= {:shops/total {:bridge/refused :bridge/invalid :bridge/data {:errors {:shop/id ["should be an id"]}} :bridge/request-id "r-3"}}
           (shapes/turn-answer-into-shaped-data wire shapes-by-name {:shops/total {:bridge/refused "bridge/invalid"
                                                                                   :bridge/data {:errors {:shop/id ["should be an id"]}}
                                                                                   :bridge/request-id "r-3"}}))
        "bridge's own reasons, for every name"))
  (testing "a reason the name doesn't declare, or data that doesn't fit: :bridge/unfit, the server's request id kept"
    (doseq [refusal [{:bridge/refused "shop/burned" :bridge/data nil :bridge/request-id "r-4"}
                     {:bridge/refused "shop/closed" :bridge/data {:shop/opens-at "soon"} :bridge/request-id "r-4"}
                     {:bridge/refused "shop/gone" :bridge/data {:why "x"} :bridge/request-id "r-4"}]]
      (is (= [:bridge/unfit "r-4"]
             ((juxt :bridge/refused :bridge/request-id) (:shops/total (shapes/turn-answer-into-shaped-data wire shapes-by-name {:shops/total refusal}))))
          (pr-str refusal))))
  (testing "a name the shapes don't have: :bridge/unfit; notes pass"
    (let [answer (shapes/turn-answer-into-shaped-data wire shapes-by-name {:nobody/home {:a 1} :bridge/notes {:x 1}})]
      (is (= {:bridge/refused :bridge/unfit :bridge/data {:errors ["No shapes named :nobody/home"]}} (:nobody/home answer)))
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

(deftest each-name-says-its-shapes-and-reasons
  (let [problems (find-problems #(bridge.api/send-message! "/mail/http" {:shops/total {:params-shape nil}} {:shops/total {}} {:wire wire}))]
    (is (= :bridge/bad-shapes (:type problems)) "refused before anything is sent")
    (is (= #{:answer-shape :refusals} (set (keys (:shops/total (:errors problems)))))))
  (testing "an answer shape naming :bridge/refused, and bridge's own reasons, are refused"
    (is (= :bridge/bad-shapes (:type (find-problems #(bridge.api/send-message! "/mail/http" {:t/x {:params-shape nil :answer-shape [:map [:bridge/refused :string]] :refusals {}}}
                                                                               {:t/x {}} {:wire wire :headers {} :keep-alive? false})))))
    (is (= :bridge/bad-shapes (:type (find-problems #(bridge.api/send-message! "/mail/http" {:t/x {:params-shape nil :answer-shape nil :refusals {:bridge/failed nil}}}
                                                                               {:t/x {}} {:wire wire :headers {} :keep-alive? false})))))))

(deftest every-line-and-message-takes-the-apps-wire
  (doseq [[what call] {"send-message!" #(bridge.api/send-message! "/mail/http" shapes-by-name {:greetings/hello {}} {})
                       "open-sse-line!" #(bridge.api/open-sse-line! "/mail/sse" shapes-by-name {} {:on-open nil :reconnect-after-ms 20})
                       "open-ws-line!" #(bridge.api/open-ws-line! "/mail/ws" shapes-by-name {} {:reopen-after-ms 20})}]
    (let [problems (find-problems call)]
      (is (= :bridge/bad-options (:type problems)) (str what ": refused before anything is sent or opened"))
      (is (contains? (:errors problems) :wire) (str what ": bridge makes no wire")))))

(deftest more-headers-go-with-a-message
  (testing ":headers is written out, {} for none; accept and content-type are bridge's"
    (is (= #{:headers :keep-alive?}
           (set (keys (:errors (find-problems #(bridge.api/send-message! "/mail/http" shapes-by-name {:greetings/hello {}} {:wire wire})))))))
    (is (= :bridge/bad-options
           (:type (find-problems #(bridge.api/send-message! "/mail/http" shapes-by-name {:greetings/hello {}}
                                                              {:wire wire :headers {"content-type" "text/plain"} :keep-alive? false})))))
    (is (= :bridge/bad-options
           (:type (find-problems #(bridge.api/send-message! "/mail/http" shapes-by-name {:greetings/hello {}}
                                                              {:wire wire :headers {"Idempotency-Key" "k"} :keep-alive? false}))))
        "names in lowercase"))
  (testing "they go out beside bridge's own; :keep-alive? is fetch's keepalive"
    (let [sent  (atom [])
          fetch (.-fetch js/globalThis)]
      (set! (.-fetch js/globalThis) (fn [address options]
                                      (swap! sent conj [address (js->clj (.-headers options)) (.-keepalive options)])
                                      (js/Promise.resolve #js {:status 200 :text (fn [] (js/Promise.resolve "{\"greetings/hello\":1}"))})))
      (try
        (bridge.api/send-message! "/mail/http" shapes-by-name {:greetings/hello {}} {:wire wire :headers {"idempotency-key" "k-1"} :keep-alive? false})
        (bridge.api/send-message! "/mail/http" shapes-by-name {:greetings/hello {}} {:wire wire :headers {} :keep-alive? true})
        (is (= [["/mail/http" {"idempotency-key" "k-1" "accept" "application/json" "content-type" "application/json"} false]
                ["/mail/http" {"accept" "application/json" "content-type" "application/json"} true]]
               @sent))
        (finally (set! (.-fetch js/globalThis) fetch)))))
  (testing ":keep-alive? is true or false"
    (is (= {:keep-alive? ["should be a boolean"]}
           (:errors (find-problems #(bridge.api/send-message! "/mail/http" shapes-by-name {:greetings/hello {}}
                                                              {:wire wire :headers {} :keep-alive? nil})))))))

(deftest an-error-answer-is-told-from-data
  (testing "a refusal: one key, :bridge/refused"
    (doseq [answer [{:bridge/refused :shop/gone :bridge/data nil :bridge/request-id "r"}
                    {:bridge/refused :bridge/no-answer :bridge/data {:http-status nil}}
                    (:shops/total (shapes/turn-answer-into-shaped-data wire shapes-by-name {:shops/total {:shop/id "seven" :total "1"}}))]]
      (is (true? (bridge.api/is-error-answer? answer)) (pr-str answer))))
  (testing "data, the old error shapes among it"
    (doseq [answer [{:error "Not found."} {:errors {}} {:bridge/errors []} [] nil "Not found." 7 {}]]
      (is (false? (bridge.api/is-error-answer? answer)) (pr-str answer)))))

(defn- with-fetch
  "Runs (f) with fetch answered by (answer address options), a promise."
  [answer f]
  (let [fetch (.-fetch js/globalThis)]
    (set! (.-fetch js/globalThis) answer)
    (-> (f) (.finally #(set! (.-fetch js/globalThis) fetch)))))

(defn- respond [status text]
  (fn [_ _] (js/Promise.resolve #js {:status status :text (fn [] (js/Promise.resolve text))})))

(deftest no-answer-is-a-named-reason-not-a-failed-promise
  (async done
    (let [send!  #(bridge.api/send-message! "/mail/http" shapes-by-name {:greetings/hello {} :shops/total {:shop/id shop-id}}
                                            {:wire wire :headers {} :keep-alive? false})
          no     (fn [status] {:greetings/hello {:bridge/refused :bridge/no-answer :bridge/data {:http-status status}}
                               :shops/total     {:bridge/refused :bridge/no-answer :bridge/data {:http-status status}}})]
      (-> (with-fetch (respond 502 "<html>Bad gateway</html>") send!)
          (.then #(is (= (no 502) %) "a proxy's page: each name, with the status"))
          (.then #(with-fetch (respond 413 "Payload Too Large") send!))
          (.then #(is (= (no 413) %)))
          (.then #(with-fetch (respond 200 "[1,2]") send!))
          (.then #(is (= (no 200) %) "JSON, but not the server's answer"))
          (.then #(with-fetch (fn [_ _] (js/Promise.reject (js/TypeError. "Failed to fetch"))) send!))
          (.then #(is (= (no nil) %) "no response at all: no status"))
          (.then #(with-fetch (respond 200 "{\"greetings/hello\":{\"bridge/refused\":\"bridge/too-many\",\"bridge/data\":{\"retry-after-ms\":1500},\"bridge/request-id\":\"r\"},\"shops/total\":{\"shop/id\":\"1d000000-0000-4000-8000-000000000001\",\"total\":\"2\"}}") send!))
          (.then #(is (= {:greetings/hello {:bridge/refused :bridge/too-many :bridge/data {:retry-after-ms 1500} :bridge/request-id "r"}
                          :shops/total     {:shop/id shop-id :total "2"}}
                         %)
                      "the server's answer: a refusal and data side by side"))
          (.catch #(is false (str %)))
          (.finally done)))))
