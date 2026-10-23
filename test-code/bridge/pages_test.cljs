(ns bridge.pages-test
  "The browser's routing, on node: an address read into a page (its query
   by its shape, its language by the app's functions), an address made,
   and following the address bar over a stand-in browser: moves, how each
   scrolls, Back and Forward, and the question before leaving."
  (:require
   [cljs.test :refer [deftest is testing]]
   [bridge.api :as bridge]
   [bridge.routes :as routes]))

(def ^:private pages
  {:orders/list  {:address "/orders" :title "Orders"}
   :orders/order {:address "/orders/:order-id" :title "Order"}})

(def ^:private query-shapes
  {:orders/list  [:map {:closed true} [:page {:optional true} :int] [:stage {:optional true} :keyword]]
   :orders/order nil})

(def ^:private languages
  "As parlor makes them from dictionary's: en has no prefix, de has /de."
  {:split-language-from-path (fn [path]
                               (if-let [[_ more] (re-matches #"/de(/.*)?" path)]
                                 {:language "de" :path (or more "/")}
                                 {:language nil :path path}))
   :make-path-in-language    (fn [path language] (if (= "de" language) (str "/de" path) path))
   :language-of-page         "en"})

(def ^:private router (routes/make-router pages))

(deftest an-address-is-read-into-a-page
  (let [page (routes/turn-address-into-page router query-shapes nil "/orders?page=2&stage=curing#notes")]
    (is (= {:name :orders/list :path-params {} :query {:page 2 :stage :curing} :query-errors nil
            :fragment "notes" :language nil}
           (dissoc page :page))
        "the query read by the page's shape: real types"))
  (testing "a value that doesn't fit is left out, and named"
    (let [{:keys [query query-errors]} (routes/turn-address-into-page router query-shapes nil "/orders?page=two&stage=curing")]
      (is (= {:stage :curing} query))
      (is (contains? query-errors :page))))
  (testing "a page without a shape keeps its query as text"
    (is (= {:tab "notes"} (:query (routes/turn-address-into-page router query-shapes nil "/orders/7?tab=notes")))))
  (testing "the language: the prefix, else the page's"
    (is (= ["de" {:order-id "7"}]
           ((juxt :language :path-params) (routes/turn-address-into-page router query-shapes languages "/de/orders/7"))))
    (is (= "en" (:language (routes/turn-address-into-page router query-shapes languages "/orders/7")))))
  (is (nil? (routes/turn-address-into-page router query-shapes nil "/nowhere")) "no page"))

(deftest an-address-is-made
  (is (= "/orders/7" (routes/make-address router nil :orders/order {:order-id 7} {} nil)))
  (is (= "/orders?page=2&stage=curing" (routes/make-address router nil :orders/list {} {:page 2 :stage :curing :search nil} nil))
      "a nil value leaves its key out")
  (is (= "/de/orders?q=a%20b" (routes/make-address router languages :orders/list {} {:q "a b"} "de")))
  (is (= :bridge/no-page (try (routes/make-address router nil :orders/order {} {} nil)
                                (catch :default e (:type (ex-data e)))))))

(deftest how-a-move-scrolls
  (let [list-page  {:name :orders/list :path-params {} :query {} :language nil}
        order-page {:name :orders/order :path-params {:order-id "7"} :query {} :language nil}]
    (is (= :top (routes/find-scroll-of-move list-page order-page)) "a new page from the top")
    (is (= [:element "notes"] (routes/find-scroll-of-move list-page (assoc order-page :fragment "notes"))) "or at its #fragment")
    (is (= :keep (routes/find-scroll-of-move list-page (assoc list-page :query {:page 2}))) "only the query: where it is")
    (is (= [:element "a"] (routes/find-scroll-of-move list-page (assoc list-page :fragment "a"))))
    (is (= :top (routes/find-scroll-of-move order-page (assoc-in order-page [:path-params :order-id] "8")))
        "another order is another page")))

(defn- make-stand-in-browser
  "A browser's history as data: the entries, where it is, the title, the
   scroll, what was scrolled to."
  [address]
  (let [state (atom {:entries [{:address address :index nil}] :at 0 :scroll 0 :scrolled [] :title nil})]
    {:state   state
     :browser {:read-address       #(get-in @state [:entries (:at @state) :address])
               :read-index         #(get-in @state [:entries (:at @state) :index])
               :push-address!      (fn [address index]
                                     (swap! state (fn [{:keys [at] :as s}]
                                                    (-> s
                                                        (update :entries #(conj (subvec % 0 (inc at)) {:address address :index index}))
                                                        (update :at inc)))))
               :replace-address!   (fn [address index] (swap! state #(assoc-in % [:entries (:at %)] {:address address :index index})))
               :go!                (fn [delta] (swap! state update :at + delta))
               :set-title!         #(swap! state assoc :title %)
               :read-scroll        #(:scroll @state)
               :scroll-to-top!     #(swap! state update :scrolled conj :top)
               :scroll-to-y!       #(swap! state update :scrolled conj [:y %])
               :scroll-to-element! #(swap! state update :scrolled conj [:element %])
               :read-positions     (constantly {})
               :keep-positions!    (fn [_] nil)
               :listen!            (fn [hooks] (swap! state assoc :hooks hooks))}}))

(defn- press-back! [{:keys [state]}]
  (swap! state update :at dec)
  ((get-in @state [:hooks :on-pop])))

(deftest following-the-address-bar
  (let [{:keys [state browser] :as stand-in} (make-stand-in-browser "/orders?page=1")
        arrived  (atom [])
        leave?   (atom true)
        asked    (atom 0)]
    (reset! routes/browser browser)
    (bridge/start-following-address! pages {:on-page               (fn [page scroll] (swap! arrived conj [(:name page) (:query page) scroll]))
                                              :query-shapes-of-pages query-shapes
                                              :may-leave?            (fn [] (swap! asked inc) @leave?)
                                              :languages             nil})
    (is (= [[:orders/list {:page 1} :keep]] @arrived) "the page it loaded at")
    (is (= "Orders" (:title @state)))
    (testing "a move to another page: from the top"
      (swap! state assoc :scroll 420)
      (bridge/go-to-page! :orders/order {:order-id "7"} {})
      (is (= [:orders/order {} :top] (peek @arrived)))
      (is (= "/orders/7" (get-in @state [:entries (:at @state) :address])))
      (is (= 1 @asked) "asked before leaving"))
    (testing "Back: where that entry was left"
      (press-back! stand-in)
      (is (= [:orders/list {:page 1} [:y 420]] (peek @arrived))))
    (testing "only the query: where it is, and nothing asked"
      (reset! asked 0)
      (bridge/go-to-page! :orders/list {} {:page 2})
      (is (= [:orders/list {:page 2} :keep] (peek @arrived)))
      (is (= 0 @asked)))
    (testing "a link to a page moves in the app; to an address no page has, it doesn't"
      (is (true? ((get-in @state [:hooks :on-link]) "/orders/8")))
      (is (= :orders/order (first (peek @arrived))))
      (is (false? ((get-in @state [:hooks :on-link]) "/elsewhere"))))
    (testing "may-leave? says no: nothing moves"
      (reset! leave? false)
      (let [before (count @arrived)
            entry  (get-in @state [:entries (:at @state) :address])]
        (bridge/go-to-page! :orders/list {} {})
        (is (= before (count @arrived)))
        (is (= entry (get-in @state [:entries (:at @state) :address])))
        (testing "and Back is put back"
          (press-back! stand-in)
          (is (= before (count @arrived)))
          (is (= entry (get-in @state [:entries (:at @state) :address])) "moved forward again")
          ((get-in @state [:hooks :on-pop]))
          (is (= before (count @arrived)) "the pop of going forward again is not a move"))))
    (testing "the app scrolls as told"
      (doseq [scroll [:top [:y 5] [:element "notes"] :keep]] (bridge/scroll-as-told! scroll))
      (is (= [:top [:y 5] [:element "notes"]] (:scrolled @state))))))

(deftest the-options-are-checked
  (let [{:keys [browser]} (make-stand-in-browser "/orders")]
    (reset! routes/browser browser)
    (let [{:keys [type errors]} (try (bridge/start-following-address! pages {:on-page identity})
                                     (catch :default e (ex-data e)))]
      (is (= :bridge/bad-options type))
      (is (= #{:query-shapes-of-pages :may-leave? :languages} (set (keys errors)))))
    (is (= {:query-shapes-of-pages ["no query shape for the page :orders/order"]}
           (try (bridge/start-following-address! pages {:on-page identity :query-shapes-of-pages {:orders/list nil}
                                                          :may-leave? nil :languages nil})
                (catch :default e (:errors (ex-data e))))))))
