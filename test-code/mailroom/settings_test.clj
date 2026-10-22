(ns mailroom.settings-test
  "Nothing has a default: a setting or an option left out refuses, named,
   with every other problem at once."
  (:require
   [clojure.test :refer [deftest is testing]]
   [mailroom.api :as mailroom]))

(def ^:private settings
  {:ip "127.0.0.1" :port 0 :client-address-header nil
   :addresses {:http "/mail/http" :sse "/mail/sse" :ws "/mail/ws"}
   :ping-every-ms 25000 :stop-wait-ms 10000 :ws-reopen-after-ms 1000
   :rate-limit-buckets-before-cleanup 10000})

(def ^:private options
  {:find-rate-limit-key identity
   :on-sse-open nil :on-sse-close nil :on-ws-open nil :on-ws-close nil})

(defn- find-problems [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest every-setting-is-required
  (let [missing               #{:addresses :ping-every-ms :client-address-header
                                :stop-wait-ms :ws-reopen-after-ms :rate-limit-buckets-before-cleanup}
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

(deftest the-files-folder-is-written-out
  (is (= :mailroom/bad-options (:type (find-problems #(mailroom/make-ring-handler-from-routes [] {})))))
  (is (fn? (mailroom/make-ring-handler-from-routes [] {:files-folder nil}))))
