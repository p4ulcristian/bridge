(ns bridge.settings-test
  "Nothing has a default: a setting or an option left out refuses, named,
   with every other problem at once."
  (:require
   [clojure.test :refer [deftest is testing]]
   [bridge.api :as bridge]
   [bridge.test-log :as test-log :refer [log]]
   [bridge.test-meter :refer [meter with-new-meter with-meter-for-answers]]
   [bridge.test-wire :refer [wire]]))

(def ^:private settings
  {:ip "127.0.0.1" :port 0 :proxy-protocol :disable
   :client-address-header nil :server-header nil
   :max-body-bytes 8388608 :max-request-head-bytes 8192 :max-ws-message-bytes 4194304
   :idle-timeout-ms 120000 :threads {:min 8 :max 50 :idle-timeout-ms 60000}
   :addresses {:http "/mail/http" :sse "/mail/sse" :ws "/mail/ws"}
   :ping-every-ms 25000 :sse-reconnect-after-ms 3000 :ws-reopen-after-ms 1000
   :stop-wait-ms 10000 :stop-wait-after-cut-ms 1000
   :rate-limit-buckets-before-cleanup 10000
   :metric-buckets {:answer-seconds [0.005 0.05 0.5 5]}})

(def ^:private options
  {:wire                wire
   :log                 log
   :meter               meter
   :find-rate-limit-key identity
   :on-sse-open nil :on-sse-close nil :on-ws-open nil :on-ws-close nil})

(defn- find-problems [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest every-setting-is-required
  (let [missing               (disj (set (keys settings)) :ip)
        {:keys [type errors]} (find-problems #(bridge/make-routes-for-messages {} (apply dissoc settings missing) (with-new-meter options)))]
    (is (= :bridge/bad-settings type))
    (is (= missing (set (keys errors))) "each missing one, named"))
  (testing "an address left out"
    (is (contains? (get-in (find-problems #(bridge/make-routes-for-messages {} (update settings :addresses dissoc :ws) (with-new-meter options)))
                           [:errors :addresses])
                   :ws)))
  (testing "a key no setting has"
    (is (contains? (:errors (find-problems #(bridge/start-server! identity (assoc settings :ping-ms 1) {:log log})))
                   :ping-ms)))
  (testing "start-server! checks them too, before it starts"
    (is (= :bridge/bad-settings (:type (find-problems #(bridge/start-server! identity (dissoc settings :ip) {:log log}))))))
  (testing "a pinged line outlives the idle timeout"
    (is (= {:idle-timeout-ms ["should have :idle-timeout-ms longer than :ping-every-ms, so a pinged line is never cut"]}
           (:errors (find-problems #(bridge/start-server! identity (assoc settings :idle-timeout-ms 25000) {:log log}))))))
  (testing "the threads: at least :min, at most :max"
    (is (= {:threads ["should have :min at most :max"]}
           (:errors (find-problems #(bridge/start-server! identity (assoc-in settings [:threads :min] 51) {:log log}))))))
  (testing "and its options: the app's log, which failures no step caught go to"
    (is (= {:type :bridge/bad-options :errors {:log ["missing required key"]}}
           (select-keys (find-problems #(bridge/start-server! identity settings {})) [:type :errors])))))

(deftest the-asker-key-and-the-hooks-are-required
  (let [{:keys [type errors]} (find-problems #(bridge/make-routes-for-messages {} settings {}))]
    (is (= :bridge/bad-options type))
    (is (= #{:wire :log :meter :find-rate-limit-key :on-sse-open :on-sse-close :on-ws-open :on-ws-close} (set (keys errors)))
        "each missing one, named"))
  (testing "the meter is the app's, never nil: one meter didn't make is refused"
    (is (= ["should be the app's meter, made by meter.api/make-meter"]
           (get-in (find-problems #(bridge/make-routes-for-messages {} settings (assoc options :meter nil))) [:errors :meter])))
    (is (= :meter/not-a-meter (:type (find-problems #(bridge/make-routes-for-messages {} settings (assoc options :meter {})))))))
  (testing "the answer time's buckets are a setting"
    (is (= {:metric-buckets ["missing required key"]}
           (:errors (find-problems #(bridge/make-routes-for-messages {} (dissoc settings :metric-buckets) (with-new-meter options))))))
    (is (= :meter/bad-metrics
           (:type (find-problems #(bridge/make-routes-for-messages {} (assoc settings :metric-buckets {:answer-seconds [1 0.5]})
                                                                     (with-new-meter options)))))
        "meter checks they go up"))
  (testing "the log is the app's, made by logbook: one it didn't make can't carry a request id"
    (is (= :logbook/not-a-log (:type (find-problems #(bridge/make-routes-for-messages {} settings (assoc (with-new-meter options) :log (fn [_ _ _]))))))))
  (testing "the wire is the app's, never nil: bridge makes none"
    (is (contains? (:errors (find-problems #(bridge/make-routes-for-messages {} settings (assoc (with-new-meter options) :wire nil))))
                   :wire)))
  (testing "a keyword is not a function"
    (is (contains? (:errors (find-problems #(bridge/make-routes-for-messages {} settings (assoc (with-new-meter options) :find-rate-limit-key :remote-addr))))
                   :find-rate-limit-key)))
  (testing "a hook is a function or nil, written out"
    (is (= 3 (count (bridge/make-routes-for-messages {} settings (assoc (with-new-meter options) :on-sse-open (fn [_line]))))))
    (is (contains? (:errors (find-problems #(bridge/make-routes-for-messages {} settings (assoc (with-new-meter options) :on-ws-open "open"))))
                   :on-ws-open))))

(deftest the-files-are-written-out
  (is (= :bridge/bad-options (:type (find-problems #(bridge/make-ring-handler-from-routes [] {})))))
  (is (= #{:hashed-name-min-hex-digits :hashed-file-cache-seconds}
         (set (keys (get-in (find-problems #(bridge/make-ring-handler-from-routes [] {:files {:folder "public"}}))
                            [:errors :files]))))
      "with a folder, each of its keys, named")
  (is (fn? (bridge/make-ring-handler-from-routes [] {:files nil :not-found nil}))))

(deftest every-handler-key-is-written-out
  (let [step                  {:name :answer :enter #(bridge/add-answer-to-context % "hi")}
        {:keys [type errors]} (find-problems #(bridge/make-routes-for-messages {:greetings/hello {:steps [step]}} settings (with-new-meter options)))]
    (is (= :bridge/bad-handlers type))
    (is (= #{:params-shape :answer-shape :refusals :rate-limit :run-steps-in} (set (keys (:greetings/hello errors))))
        "each missing one, named"))
  (testing "nil written out: no shapes, no limit, run plainly; a key of the app's own rides along"
    (is (= 3 (count (bridge/make-routes-for-messages
                     {:greetings/hello {:steps [] :params-shape nil :answer-shape nil :refusals {} :rate-limit nil :run-steps-in nil
                                        :access/rule :anyone}}
                     settings (with-new-meter options))))))
  (testing "answer-message checks them too"
    (is (= :bridge/bad-handlers
           (:type (find-problems #(bridge/answer-message {:greetings/hello {:steps []}} :no-rate-limits {} {:greetings/hello {}} (with-meter-for-answers {:wire wire :log log}))))))))
