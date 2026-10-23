(ns bridge.api-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [logbook.api :as logbook]
   [bridge.api :as bridge]
   [bridge.test-log :as test-log :refer [log]]
   [bridge.test-meter :refer [meter with-new-meter]]
   [bridge.test-wire :refer [wire]])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers WebSocket$Listener)))

(def ^:private say-hello
  {:name  :say-hello
   :enter #(bridge/add-answer-to-context % {:hello (get-in % [:bridge/message-data :name])})})

(def ^:private answer-shop-total
  {:name  :answer-shop-total
   :enter (fn [context]
            (let [shop-id (get-in context [:bridge/message-data :shop/id])]
              (bridge/add-answer-to-context context {:shop/id shop-id :total (if (uuid? shop-id) 120.50M 0M)})))})

(def ^:private settings
  {:ip "127.0.0.1" :port 0 :proxy-protocol :disable
   :client-address-header nil :server-header nil
   :max-body-bytes 8388608 :max-header-line-bytes 8192 :max-ws-message-bytes 4194304
   :addresses {:http "/mail/http" :sse "/mail/sse" :ws "/mail/ws"}
   :ping-every-ms 25000 :sse-reconnect-after-ms 3000 :ws-reopen-after-ms 1000
   :stop-wait-ms 10000 :stop-wait-after-cut-ms 1000
   :rate-limit-buckets-before-cleanup 10000
   :metric-buckets {:answer-seconds [0.005 0.05 0.5 5]}})

(def ^:private options
  {:wire                wire
   :log                 log
   :meter               meter
   :find-rate-limit-key #(bridge/find-client-address % (:client-address-header settings))
   :on-sse-open nil :on-sse-close nil :on-ws-open nil :on-ws-close nil})

(defn- make-handler
  "A handler with every key written out: no shapes, no limit, run plainly."
  [steps]
  {:steps steps :params-shape nil :answer-shape nil :rate-limit nil :run-steps-in nil})

(def ^:private handlers
  {:greetings/hello (make-handler [say-hello])
   :shops/total     (assoc (make-handler [answer-shop-total])
                           :params-shape [:map [:shop/id :uuid]]
                           :answer-shape [:map [:shop/id :uuid] [:total :decimal]])
   :greetings/fail  (make-handler [{:name :fail :enter (fn [_] (throw (ex-info "it broke" {})))}])})

(defn- find-handler
  "The Ring handler of one of the three routes: 0 http, 1 sse, 2 ws."
  [routes index method]
  (get-in (nth routes index) [1 method :handler]))

(defn- with-server [ring-handler f]
  (let [server (bridge/start-server! ring-handler settings {:log log})]
    (try (f (:port server)) (finally (bridge/stop-server! server)))))

(defn- post-json [port body]
  (let [response (.send (HttpClient/newHttpClient)
                        (-> (HttpRequest/newBuilder (URI. (str "http://localhost:" port "/mail/http")))
                            (.POST (HttpRequest$BodyPublishers/ofString body))
                            (.build))
                        (HttpResponse$BodyHandlers/ofString))]
    [(.statusCode response) (.body response)]))

(deftest the-three-routes-are-at-the-settings-addresses
  (is (= ["/mail/http" "/mail/sse" "/mail/ws"]
         (map first (bridge/make-routes-for-messages handlers settings (with-new-meter options))))))

(deftest http-answers-each-name-apart
  (with-server (find-handler (bridge/make-routes-for-messages handlers settings (with-new-meter options)) 0 :post)
    (fn [port]
      (testing "each name answered under its name; one failing doesn't stop the other"
        (is (= [200 "{\"greetings/hello\":{\"hello\":\"Anna\"},\"greetings/fail\":{\"error\":\"Something went wrong.\"}}"]
               (post-json port "{\"greetings/hello\":{\"name\":\"Anna\"},\"greetings/fail\":{}}"))))
      (testing "an unknown name: an error under it"
        (is (= [200 "{\"nobody/home\":{\"error\":\"No handler named :nobody/home\"}}"]
               (post-json port "{\"nobody/home\":{}}"))))
      (testing "with shapes: params arrive as real types, the answer leaves as JSON by its shape"
        (is (= [200 "{\"shops/total\":{\"shop/id\":\"1d000000-0000-4000-8000-000000000001\",\"total\":\"120.50\"}}"]
               (post-json port "{\"shops/total\":{\"shop/id\":\"1d000000-0000-4000-8000-000000000001\"}}"))))
      (testing "params that don't fit the shape: errors by key, no step runs"
        (is (= [200 "{\"shops/total\":{\"errors\":{\"shop/id\":[\"should be an id\"]}}}"]
               (post-json port "{\"shops/total\":{\"shop/id\":\"seven\"}}"))))
      (testing "not a JSON object: 400"
        (is (= 400 (first (post-json port "[1,2]"))))))))

(deftest sse-writes-named-messages
  (let [opened (promise)
        routes (bridge/make-routes-for-messages handlers settings (assoc (with-new-meter options) :on-sse-open #(deliver opened %)))]
    (with-server (find-handler routes 1 :get)
      (fn [port]
        (let [response (.send (HttpClient/newHttpClient)
                              (.build (HttpRequest/newBuilder (URI. (str "http://localhost:" port "/mail/sse"))))
                              (HttpResponse$BodyHandlers/ofInputStream))
              reader   (io/reader (.body response))
              line     (deref opened 5000 nil)]
          (is (bridge/is-line-open? line))
          (testing "the line first tells the browser when to reopen it: the setting, as retry:"
            (is (= ["retry: 3000" ""] [(.readLine reader) (.readLine reader)])))
          (is (true? (bridge/send-message-on-sse-line! line {:bells/changed {:table "orders"}} 17)))
          (is (= ["id: 17" "data: {\"bells/changed\":{\"table\":\"orders\"}}" ""]
                 [(.readLine reader) (.readLine reader) (.readLine reader)]))
          (testing "no id, written out"
            (is (true? (bridge/send-message-on-sse-line! line {:bells/changed {:table "shops"}} nil)))
            (is (= "data: {\"bells/changed\":{\"table\":\"shops\"}}" (.readLine reader))))
          (.close reader))))))

(deftest ws-answers-by-the-same-handlers
  (with-server (find-handler (bridge/make-routes-for-messages handlers settings (with-new-meter options)) 2 :get)
    (fn [port]
      (let [received (promise)
            socket   (.join (.buildAsync (.newWebSocketBuilder (HttpClient/newHttpClient))
                                         (URI. (str "ws://localhost:" port "/mail/ws"))
                                         (reify WebSocket$Listener
                                           (onText [_ socket text _last]
                                             (deliver received (str text))
                                             (.request socket 1)
                                             nil))))]
        (.join (.sendText socket "{\"greetings/hello\":{\"name\":\"Bob\"}}" true))
        (is (= "{\"greetings/hello\":{\"hello\":\"Bob\"}}" (deref received 5000 nil)))
        (.join (.sendClose socket 1000 "done"))))))

(deftest ws-pings-so-proxies-keep-the-line
  (with-server (find-handler (bridge/make-routes-for-messages handlers (assoc settings :ping-every-ms 100) (with-new-meter options)) 2 :get)
    (fn [port]
      (let [received (promise)
            socket   (.join (.buildAsync (.newWebSocketBuilder (HttpClient/newHttpClient))
                                         (URI. (str "ws://localhost:" port "/mail/ws"))
                                         (reify WebSocket$Listener
                                           (onText [_ socket text _last]
                                             (deliver received (str text))
                                             (.request socket 1)
                                             nil))))]
        (is (= "{\"bridge/ping\":{}}" (deref received 5000 nil)))
        (.join (.sendClose socket 1000 "done"))))))

(deftest a-rate-limit-counts-each-asker-behind-the-proxy
  (let [behind-proxy (assoc settings :client-address-header "x-forwarded-for")
        limited      {:greetings/hello (assoc (make-handler [say-hello]) :rate-limit {:max 1 :per-ms 60000})}
        http-handler (find-handler (bridge/make-routes-for-messages
                                    limited behind-proxy
                                    (assoc (with-new-meter options) :find-rate-limit-key #(bridge/find-client-address % (:client-address-header behind-proxy))))
                                   0 :post)
        ask          (fn [asker]
                       (:body (http-handler {:remote-addr "10.0.0.1"
                                             :headers     {"x-forwarded-for" asker}
                                             :body        (java.io.ByteArrayInputStream. (.getBytes "{\"greetings/hello\":{\"name\":\"Anna\"}}"))})))]
    (is (= "{\"greetings/hello\":{\"hello\":\"Anna\"}}" (ask "1.1.1.1")))
    (is (= "{\"greetings/hello\":{\"error\":\"Too many requests.\"}}" (ask "1.1.1.1")))
    (is (= "{\"greetings/hello\":{\"hello\":\"Anna\"}}" (ask "2.2.2.2"))
        "another asker behind the same proxy has its own bucket")))

(deftest answer-message-says-there-are-no-rate-limits
  (let [limited {:greetings/hello (assoc (make-handler [say-hello]) :rate-limit {:max 1 :per-ms 60000})}
        ask     #(bridge/answer-message limited :no-rate-limits {:remote-addr "1.1.1.1"} {:greetings/hello {:name "Anna"}} {:wire wire :log log})]
    (is (= [{:greetings/hello {:hello "Anna"}} {:greetings/hello {:hello "Anna"}}] [(ask) (ask)])
        "outside a server nothing counts, and the call says so"))
  (is (= :bridge/bad-options
         (try (bridge/answer-message handlers nil {} {:greetings/hello {:name "Anna"}} {:wire wire :log log})
              (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
      "nil is not a choice: :no-rate-limits is written out")
  (let [{:keys [type errors]} (try (bridge/answer-message handlers :no-rate-limits {} {:greetings/hello {:name "Anna"}} {})
                                   (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :bridge/bad-options type) "bridge makes no wire and no log: the caller hands its own in")
    (is (= #{:wire :log} (set (keys errors))))))

(def ^:private person-id #uuid "41000000-0000-4000-8000-000000000041")

(def ^:private what-steps-may-use (atom []))

(def ^:private steps-that-log
  {:greetings/noted  (make-handler [{:name  :note
                                     :enter (fn [{:bridge/keys [log wire] :as context}]
                                              (swap! what-steps-may-use conj (some? wire))
                                              (log :info :test/step-ran {})
                                              (bridge/add-answer-to-context context "noted"))}])
   :greetings/broken (make-handler [{:name  :find-person
                                     :enter #(update % :bridge/log logbook/with-context {:person-id person-id})}
                                    {:name  :break
                                     :enter (fn [{:bridge/keys [log]}]
                                              (log :info :test/about-to-break {})
                                              (throw (ex-info "it broke" {:cause :test})))}])})

(deftest each-message-gets-a-log-with-its-own-request-id
  (reset! test-log/lines [])
  (let [ask #(bridge/answer-message steps-that-log :no-rate-limits {} % {:wire wire :log log})]
    (is (= {:greetings/noted "noted" :greetings/broken {:error "Something went wrong."}}
           (ask {:greetings/noted {} :greetings/broken {}})))
    (ask {:greetings/noted {}})
    (let [[first-noted second-noted] (test-log/find-lines "test/step-ran")
          [about-to-break]           (test-log/find-lines "test/about-to-break")
          [failed]                   (test-log/find-lines "bridge/handler-failed")
          request-id                 #(get-in % ["context" "request-id"])]
      (is (re-matches #"[0-9a-f-]{36}" (request-id first-noted)) "a new random id")
      (is (= (request-id first-noted) (request-id about-to-break) (request-id failed))
          "every name of one message shares its message's id")
      (is (not= (request-id first-noted) (request-id second-noted)) "the next message has its own")
      (is (= [true true] @what-steps-may-use) "the steps get the app's wire too")
      (testing "a failure is written to the log the steps had got to"
        (is (= ["error" (str person-id) {"bridge/message-name" "greetings/broken"} "it broke"]
               [(get failed "level") (get-in failed ["context" "person-id"]) (get failed "data")
                (get-in failed ["error" "message"])])
            "the person a step added is in the failure's line")))))

(deftest stop-lets-a-running-request-finish-then-returns
  (let [started  (promise)
        slow     {:greetings/slow (make-handler [{:name  :slow
                                                  :enter (fn [context]
                                                           (deliver started true)
                                                           (Thread/sleep 300)
                                                           (bridge/add-answer-to-context context "done"))}])}
        server   (bridge/start-server! (find-handler (bridge/make-routes-for-messages slow settings (with-new-meter options)) 0 :post)
                                         (assoc settings :stop-wait-ms 2000 :stop-wait-after-cut-ms 500)
                                         {:log log})
        answer   (future (post-json (:port server) "{\"greetings/slow\":{}}"))]
    (deref started 5000 nil)
    (is (true? (bridge/stop-server! server)) "every request's thread had ended")
    (is (= [200 "{\"greetings/slow\":\"done\"}"] (deref answer 5000 nil)) "within :stop-wait-ms it finishes")))

(deftest stop-cuts-a-request-past-the-wait
  (let [started (promise)
        slow    {:greetings/slow (make-handler [{:name  :slow
                                                 :enter (fn [context]
                                                          (deliver started true)
                                                          (Thread/sleep 5000)
                                                          (bridge/add-answer-to-context context "done"))}])}
        server  (bridge/start-server! (find-handler (bridge/make-routes-for-messages slow settings (with-new-meter options)) 0 :post)
                                        (assoc settings :stop-wait-ms 100 :stop-wait-after-cut-ms 1000)
                                        {:log log})
        answer  (future (try (post-json (:port server) "{\"greetings/slow\":{}}") (catch Exception _ :cut)))
        _       (deref started 5000 nil)
        began   (System/currentTimeMillis)]
    (bridge/stop-server! server)
    (is (< (- (System/currentTimeMillis) began) 2000) "it waited :stop-wait-ms, cut, and didn't wait out the 5 s")
    (is (not= [200 "{\"greetings/slow\":\"done\"}"] (deref answer 5000 nil)))))

(deftest a-body-over-the-setting-is-refused
  (let [server (bridge/start-server! (find-handler (bridge/make-routes-for-messages handlers settings (with-new-meter options)) 0 :post)
                                       (assoc settings :max-body-bytes 16)
                                       {:log log})]
    (try
      (is (= 413 (first (post-json (:port server) "{\"greetings/hello\":{\"name\":\"Anna\"}}"))))
      (is (= 200 (first (post-json (:port server) "{\"x/y\":{}}"))) "within it, answered")
      (finally (bridge/stop-server! server)))))

(deftest the-server-s-error-answers-are-told-from-data
  (let [answer (bridge/answer-message handlers :no-rate-limits {}
                                        {:greetings/hello {:name "Anna"}
                                         :greetings/fail  {}
                                         :shops/total     {:shop/id "seven"}
                                         :nobody/home     {}}
                                        {:wire wire :log log})]
    (is (= {:greetings/hello false :greetings/fail true :shops/total true :nobody/home true}
           (update-vals answer bridge/is-error-answer?))
        "a refusal, params that didn't fit, an unknown name: errors; the greeting: data")))
