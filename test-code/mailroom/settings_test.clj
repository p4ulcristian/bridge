(ns mailroom.settings-test
  "Nothing has a default: a setting or an option left out refuses, named,
   with every other problem at once."
  (:require
   [clojure.test :refer [deftest is testing]]
   [mailroom.api :as mailroom]))

(def ^:private settings
  {:ip "127.0.0.1" :port 0 :proxy-protocol :disable
   :client-address-header nil :server-header nil
   :max-body-bytes 8388608 :max-header-line-bytes 8192 :max-ws-message-bytes 4194304
   :addresses {:http "/mail/http" :sse "/mail/sse" :ws "/mail/ws"}
   :ping-every-ms 25000 :sse-reconnect-after-ms 3000 :ws-reopen-after-ms 1000
   :stop-wait-ms 10000 :stop-wait-after-cut-ms 1000
   :rate-limit-buckets-before-cleanup 10000})

(def ^:private options
  {:find-rate-limit-key identity
   :on-sse-open nil :on-sse-close nil :on-ws-open nil :on-ws-close nil})

(defn- find-problems [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest every-setting-is-required
  (let [missing               (disj (set (keys settings)) :ip)
        {:keys [type errors]} (find-problems #(mailroom/make-routes-for-messages {} (apply dissoc settings missing) options))]
    (is (= :mailroom/bad-settings type))
    (is (= missing (set (keys errors))) "each missing one, named"))
  (testing "an address left out"
    (is (contains? (get-in (find-problems #(mailroom/make-routes-for-messages {} (update settings :addresses dissoc :ws) options))
                           [:errors :addresses])
                   :ws)))
  (testing "a key no setting has"
    (is (contains? (:errors (find-problems #(mailroom/start-server! identity (assoc settings :ping-ms 1))))
                   :ping-ms)))
  (testing "start-server! checks them too, before it starts"
    (is (= :mailroom/bad-settings (:type (find-problems #(mailroom/start-server! identity (dissoc settings :ip))))))))

(deftest the-asker-key-and-the-hooks-are-required
  (let [{:keys [type errors]} (find-problems #(mailroom/make-routes-for-messages {} settings {}))]
    (is (= :mailroom/bad-options type))
    (is (= #{:find-rate-limit-key :on-sse-open :on-sse-close :on-ws-open :on-ws-close} (set (keys errors)))
        "each missing one, named"))
  (testing "a keyword is not a function"
    (is (contains? (:errors (find-problems #(mailroom/make-routes-for-messages {} settings (assoc options :find-rate-limit-key :remote-addr))))
                   :find-rate-limit-key)))
  (testing "a hook is a function or nil, written out"
    (is (= 3 (count (mailroom/make-routes-for-messages {} settings (assoc options :on-sse-open (fn [_line]))))))
    (is (contains? (:errors (find-problems #(mailroom/make-routes-for-messages {} settings (assoc options :on-ws-open "open"))))
                   :on-ws-open))))

(deftest the-files-are-written-out
  (is (= :mailroom/bad-options (:type (find-problems #(mailroom/make-ring-handler-from-routes [] {})))))
  (is (= #{:hashed-name-min-hex-digits :hashed-file-cache-seconds}
         (set (keys (get-in (find-problems #(mailroom/make-ring-handler-from-routes [] {:files {:folder "public"}}))
                            [:errors :files]))))
      "with a folder, each of its keys, named")
  (is (fn? (mailroom/make-ring-handler-from-routes [] {:files nil}))))

(deftest every-handler-key-is-written-out
  (let [step                  {:name :answer :enter #(mailroom/add-answer-to-context % "hi")}
        {:keys [type errors]} (find-problems #(mailroom/make-routes-for-messages {:greetings/hello {:steps [step]}} settings options))]
    (is (= :mailroom/bad-handlers type))
    (is (= #{:params-shape :answer-shape :rate-limit :run-steps-in} (set (keys (:greetings/hello errors))))
        "each missing one, named"))
  (testing "nil written out: no shapes, no limit, run plainly; a key of the app's own rides along"
    (is (= 3 (count (mailroom/make-routes-for-messages
                     {:greetings/hello {:steps [] :params-shape nil :answer-shape nil :rate-limit nil :run-steps-in nil
                                        :access/rule :anyone}}
                     settings options)))))
  (testing "answer-message checks them too"
    (is (= :mailroom/bad-handlers
           (:type (find-problems #(mailroom/answer-message {:greetings/hello {:steps []}} :no-rate-limits {} {:greetings/hello {}})))))))
