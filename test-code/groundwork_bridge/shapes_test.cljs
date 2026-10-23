(ns groundwork-bridge.shapes-test
  "The browser's side, on node: a message goes out by its :params-shape, an
   answer comes back by its :answer-shape, and a named message finds its
   handler. Requiring groundwork-bridge.api proves the whole browser side compiles."
  (:require
   [cljs.test :refer [async deftest is testing]]
   [groundwork-bridge.api]
   [groundwork-bridge.messages :as messages]
   [groundwork-bridge.shapes :as shapes]
   [groundwork-bridge.test-shaper :refer [shaper]]
   [groundwork-shaped-json.time]))

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
         (shapes/turn-message-into-json-data shaper shapes-by-name {:shops/total {:shop/id shop-id}})))
  (testing "data that doesn't fit: nothing is sent"
    (is (= :groundwork-bridge/bad-params
           (try (shapes/turn-message-into-json-data shaper shapes-by-name {:shops/total {:shop/id shop-id :extra 1}})
                (catch :default e (:type (ex-data e)))))))
  (testing "a name whose shape is nil, written out, passes as it is"
    (is (= {:greetings/hello {:name "Anna"}}
           (shapes/turn-message-into-json-data shaper shapes-by-name {:greetings/hello {:name "Anna"}}))))
  (testing "a name the shapes don't have: nothing is sent"
    (is (= :groundwork-bridge/no-shapes
           (:type (find-problems #(shapes/turn-message-into-json-data shaper shapes-by-name {:nobody/home {}})))))))

(deftest an-answer-comes-back-by-its-shape
  (let [answer (shapes/turn-answer-into-shaped-data shaper shapes-by-name {:shops/total {:shop/id shop-id :total "120.50"}})]
    (is (= {:shops/total {:shop/id shop-id :total "120.50"}} answer)
        "fits its shape; in the browser an id and a decimal are their text (groundwork-shaped-json)"))
  (testing "an answer that doesn't fit: :groundwork-bridge/unfit, with why"
    (let [answer (shapes/turn-answer-into-shaped-data shaper shapes-by-name {:shops/total {:shop/id "seven" :total "1"}})]
      (is (= :groundwork-bridge/unfit (get-in answer [:shops/total :groundwork-bridge/refused])))
      (is (contains? (get-in answer [:shops/total :groundwork-bridge/data :errors]) :shop/id))))
  (testing "a refusal passes the answer shape: its reason a keyword, its data by its reason's shape"
    (let [{:shops/keys [total]} (shapes/turn-answer-into-shaped-data
                                 shaper shapes-by-name
                                 {:shops/total {:groundwork-bridge/refused "shop/closed" :groundwork-bridge/data {:shop/opens-at "2026-10-24T08:00:00Z"}
                                                :groundwork-bridge/request-id "r-1"}})]
      (is (= [:shop/closed "r-1"] [(:groundwork-bridge/refused total) (:groundwork-bridge/request-id total)]))
      (is (instance? groundwork-shaped-json.time/Instant (get-in total [:groundwork-bridge/data :shop/opens-at])) "shaped, as data is"))
    (is (= {:shops/total {:groundwork-bridge/refused :shop/gone :groundwork-bridge/data nil :groundwork-bridge/request-id "r-2"}}
           (shapes/turn-answer-into-shaped-data shaper shapes-by-name {:shops/total {:groundwork-bridge/refused "shop/gone" :groundwork-bridge/data nil :groundwork-bridge/request-id "r-2"}})))
    (is (= {:shops/total {:groundwork-bridge/refused :groundwork-bridge/invalid :groundwork-bridge/data {:errors {:shop/id ["should be an id"]}} :groundwork-bridge/request-id "r-3"}}
           (shapes/turn-answer-into-shaped-data shaper shapes-by-name {:shops/total {:groundwork-bridge/refused "groundwork-bridge/invalid"
                                                                                   :groundwork-bridge/data {:errors {:shop/id ["should be an id"]}}
                                                                                   :groundwork-bridge/request-id "r-3"}}))
        "bridge's own reasons, for every name"))
  (testing "a reason the name doesn't declare, or data that doesn't fit: :groundwork-bridge/unfit, the server's request id kept"
    (doseq [refusal [{:groundwork-bridge/refused "shop/burned" :groundwork-bridge/data nil :groundwork-bridge/request-id "r-4"}
                     {:groundwork-bridge/refused "shop/closed" :groundwork-bridge/data {:shop/opens-at "soon"} :groundwork-bridge/request-id "r-4"}
                     {:groundwork-bridge/refused "shop/gone" :groundwork-bridge/data {:why "x"} :groundwork-bridge/request-id "r-4"}]]
      (is (= [:groundwork-bridge/unfit "r-4"]
             ((juxt :groundwork-bridge/refused :groundwork-bridge/request-id) (:shops/total (shapes/turn-answer-into-shaped-data shaper shapes-by-name {:shops/total refusal}))))
          (pr-str refusal))))
  (testing "a name the shapes don't have: :groundwork-bridge/unfit; notes pass"
    (let [answer (shapes/turn-answer-into-shaped-data shaper shapes-by-name {:nobody/home {:a 1} :groundwork-bridge/notes {:x 1}})]
      (is (= {:groundwork-bridge/refused :groundwork-bridge/unfit :groundwork-bridge/data {:errors ["No shapes named :nobody/home"]}} (:nobody/home answer)))
      (is (= {:x 1} (:groundwork-bridge/notes answer))))))

(deftest a-named-message-finds-its-handler
  (let [heard (atom [])]
    (messages/hand-named-messages! {:sync/changed #(swap! heard conj %)} {:sync/changed {:table "orders"}})
    (is (= [{:table "orders"}] @heard))))

(deftest a-ws-line-is-told-when-to-reopen
  (let [problems (find-problems #(groundwork-bridge.api/open-ws-line! "/mail/ws" shapes-by-name {} {:shaper shaper}))]
    (is (= :groundwork-bridge/bad-options (:type problems)) "no :reopen-after-ms: refused before any socket opens")
    (is (contains? (:errors problems) :reopen-after-ms))))

(deftest an-sse-line-says-its-on-open
  (let [problems (find-problems #(groundwork-bridge.api/open-sse-line! "/mail/sse" shapes-by-name {} {:shaper shaper}))]
    (is (= :groundwork-bridge/bad-options (:type problems)) "no :on-open, not even nil: refused before the line opens")
    (is (contains? (:errors problems) :on-open))))

(deftest each-name-says-its-shapes-and-reasons
  (let [problems (find-problems #(groundwork-bridge.api/send-message! "/mail/http" {:shops/total {:params-shape nil}} {:shops/total {}} {:shaper shaper}))]
    (is (= :groundwork-bridge/bad-shapes (:type problems)) "refused before anything is sent")
    (is (= #{:answer-shape :refusals} (set (keys (:shops/total (:errors problems)))))))
  (testing "an answer shape naming :groundwork-bridge/refused, and bridge's own reasons, are refused"
    (is (= :groundwork-bridge/bad-shapes (:type (find-problems #(groundwork-bridge.api/send-message! "/mail/http" {:t/x {:params-shape nil :answer-shape [:map [:groundwork-bridge/refused :string]] :refusals {}}}
                                                                               {:t/x {}} {:shaper shaper :headers {} :keep-alive? false})))))
    (is (= :groundwork-bridge/bad-shapes (:type (find-problems #(groundwork-bridge.api/send-message! "/mail/http" {:t/x {:params-shape nil :answer-shape nil :refusals {:groundwork-bridge/failed nil}}}
                                                                               {:t/x {}} {:shaper shaper :headers {} :keep-alive? false})))))))

(deftest every-line-and-message-takes-the-apps-shaper
  (doseq [[what call] {"send-message!" #(groundwork-bridge.api/send-message! "/mail/http" shapes-by-name {:greetings/hello {}} {})
                       "open-sse-line!" #(groundwork-bridge.api/open-sse-line! "/mail/sse" shapes-by-name {} {:on-open nil :reconnect-after-ms 20})
                       "open-ws-line!" #(groundwork-bridge.api/open-ws-line! "/mail/ws" shapes-by-name {} {:reopen-after-ms 20})}]
    (let [problems (find-problems call)]
      (is (= :groundwork-bridge/bad-options (:type problems)) (str what ": refused before anything is sent or opened"))
      (is (contains? (:errors problems) :shaper) (str what ": bridge makes no shaper")))))

(deftest more-headers-go-with-a-message
  (testing ":headers is written out, {} for none; accept and content-type are bridge's"
    (is (= #{:headers :keep-alive?}
           (set (keys (:errors (find-problems #(groundwork-bridge.api/send-message! "/mail/http" shapes-by-name {:greetings/hello {}} {:shaper shaper})))))))
    (is (= :groundwork-bridge/bad-options
           (:type (find-problems #(groundwork-bridge.api/send-message! "/mail/http" shapes-by-name {:greetings/hello {}}
                                                              {:shaper shaper :headers {"content-type" "text/plain"} :keep-alive? false})))))
    (is (= :groundwork-bridge/bad-options
           (:type (find-problems #(groundwork-bridge.api/send-message! "/mail/http" shapes-by-name {:greetings/hello {}}
                                                              {:shaper shaper :headers {"Idempotency-Key" "k"} :keep-alive? false}))))
        "names in lowercase"))
  (testing "they go out beside bridge's own; :keep-alive? is fetch's keepalive"
    (let [sent  (atom [])
          fetch (.-fetch js/globalThis)]
      (set! (.-fetch js/globalThis) (fn [address options]
                                      (swap! sent conj [address (js->clj (.-headers options)) (.-keepalive options)])
                                      (js/Promise.resolve #js {:status 200 :text (fn [] (js/Promise.resolve "{\"greetings/hello\":1}"))})))
      (try
        (groundwork-bridge.api/send-message! "/mail/http" shapes-by-name {:greetings/hello {}} {:shaper shaper :headers {"idempotency-key" "k-1"} :keep-alive? false})
        (groundwork-bridge.api/send-message! "/mail/http" shapes-by-name {:greetings/hello {}} {:shaper shaper :headers {} :keep-alive? true})
        (is (= [["/mail/http" {"idempotency-key" "k-1" "accept" "application/json" "content-type" "application/json"} false]
                ["/mail/http" {"accept" "application/json" "content-type" "application/json"} true]]
               @sent))
        (finally (set! (.-fetch js/globalThis) fetch)))))
  (testing ":keep-alive? is true or false"
    (is (= {:keep-alive? ["should be a boolean"]}
           (:errors (find-problems #(groundwork-bridge.api/send-message! "/mail/http" shapes-by-name {:greetings/hello {}}
                                                              {:shaper shaper :headers {} :keep-alive? nil})))))))

(deftest an-error-answer-is-told-from-data
  (testing "a refusal: one key, :groundwork-bridge/refused"
    (doseq [answer [{:groundwork-bridge/refused :shop/gone :groundwork-bridge/data nil :groundwork-bridge/request-id "r"}
                    {:groundwork-bridge/refused :groundwork-bridge/no-answer :groundwork-bridge/data {:http-status nil}}
                    (:shops/total (shapes/turn-answer-into-shaped-data shaper shapes-by-name {:shops/total {:shop/id "seven" :total "1"}}))]]
      (is (true? (groundwork-bridge.api/is-error-answer? answer)) (pr-str answer))))
  (testing "data, the old error shapes among it"
    (doseq [answer [{:error "Not found."} {:errors {}} {:groundwork-bridge/errors []} [] nil "Not found." 7 {}]]
      (is (false? (groundwork-bridge.api/is-error-answer? answer)) (pr-str answer)))))

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
    (let [send!  #(groundwork-bridge.api/send-message! "/mail/http" shapes-by-name {:greetings/hello {} :shops/total {:shop/id shop-id}}
                                            {:shaper shaper :headers {} :keep-alive? false})
          no     (fn [status] {:greetings/hello {:groundwork-bridge/refused :groundwork-bridge/no-answer :groundwork-bridge/data {:http-status status}}
                               :shops/total     {:groundwork-bridge/refused :groundwork-bridge/no-answer :groundwork-bridge/data {:http-status status}}})]
      (-> (with-fetch (respond 502 "<html>Bad gateway</html>") send!)
          (.then #(is (= (no 502) %) "a proxy's page: each name, with the status"))
          (.then #(with-fetch (respond 413 "Payload Too Large") send!))
          (.then #(is (= (no 413) %)))
          (.then #(with-fetch (respond 200 "[1,2]") send!))
          (.then #(is (= (no 200) %) "JSON, but not the server's answer"))
          (.then #(with-fetch (fn [_ _] (js/Promise.reject (js/TypeError. "Failed to fetch"))) send!))
          (.then #(is (= (no nil) %) "no response at all: no status"))
          (.then #(with-fetch (respond 200 "{\"greetings/hello\":{\"groundwork-bridge/refused\":\"groundwork-bridge/too-many\",\"groundwork-bridge/data\":{\"retry-after-ms\":1500},\"groundwork-bridge/request-id\":\"r\"},\"shops/total\":{\"shop/id\":\"1d000000-0000-4000-8000-000000000001\",\"total\":\"2\"}}") send!))
          (.then #(is (= {:greetings/hello {:groundwork-bridge/refused :groundwork-bridge/too-many :groundwork-bridge/data {:retry-after-ms 1500} :groundwork-bridge/request-id "r"}
                          :shops/total     {:shop/id shop-id :total "2"}}
                         %)
                      "the server's answer: a refusal and data side by side"))
          (.catch #(is false (str %)))
          (.finally done)))))
