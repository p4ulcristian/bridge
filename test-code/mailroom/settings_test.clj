(ns mailroom.settings-test
  "Nothing has a default: a setting or an option left out refuses, named,
   with every other problem at once."
  (:require
   [clojure.test :refer [deftest is testing]]
   [mailroom.api :as mailroom]))

(def ^:private settings
  {:ip "127.0.0.1" :port 0 :client-address-header nil
   :addresses {:http "/mail/http" :sse "/mail/sse" :ws "/mail/ws"}
   :ping-every-ms 25000})

(def ^:private options {:find-rate-limit-key :remote-addr})

(defn- find-problems [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest every-setting-is-required
  (let [{:keys [type errors]} (find-problems #(mailroom/make-routes-for-messages {} (dissoc settings :addresses :ping-every-ms :client-address-header) {:find-rate-limit-key identity}))]
    (is (= :mailroom/bad-settings type))
    (is (= #{:addresses :ping-every-ms :client-address-header} (set (keys errors))) "each missing one, named"))
  (testing "an address left out"
    (is (contains? (get-in (find-problems #(mailroom/make-routes-for-messages {} (update settings :addresses dissoc :ws) {:find-rate-limit-key identity}))
                           [:errors :addresses])
                   :ws)))
  (testing "a key no setting has"
    (is (contains? (:errors (find-problems #(mailroom/start-server! identity (assoc settings :ping-ms 1))))
                   :ping-ms)))
  (testing "start-server! checks them too, before it starts"
    (is (= :mailroom/bad-settings (:type (find-problems #(mailroom/start-server! identity (dissoc settings :ip))))))))

(deftest the-asker-key-is-required
  (let [{:keys [type errors]} (find-problems #(mailroom/make-routes-for-messages {} settings {}))]
    (is (= :mailroom/bad-options type))
    (is (contains? errors :find-rate-limit-key)))
  (testing "a keyword is not a function"
    (is (contains? (:errors (find-problems #(mailroom/make-routes-for-messages {} settings options)))
                   :find-rate-limit-key))))

(deftest the-files-folder-is-written-out
  (is (= :mailroom/bad-options (:type (find-problems #(mailroom/make-ring-handler-from-routes [] {})))))
  (is (fn? (mailroom/make-ring-handler-from-routes [] {:files-folder nil}))))
