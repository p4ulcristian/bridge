(ns groundwork-bridge.routes
  "The browser's routes: the address in the bar leads to a page, found by
   name, with its path params, its query and its #fragment, and, when the
   app has languages, its language (the address's prefix, read by the
   functions the app hands in). The pages come from the server, in the
   page, already checked there.

   Following the address bar: a plain click on a link to a page, and
   go-to-page!, move without a reload (HTML5 history), Back and Forward
   too; the page's :title becomes the tab's title. Before a move to
   another page (not one that changes only the query or the #fragment),
   the app's :may-leave? is asked, with the move; no, and nothing moves
   (Back and Forward are put back), and the app may finish the move later
   with finish-move! (its own dialog's Leave).

   Each move tells how the page should scroll once it is drawn: a new page
   from the top, or at its #fragment's element; a change of only the
   query where it is; Back and Forward where that entry was left (kept per
   history entry, in sessionStorage too, so a reload finds it). The app
   draws, so the app scrolls, with scroll-as-told!."
  (:require
   [clojure.string :as str]
   [goog.Uri]
   [goog.Uri.QueryData]
   [malli.core :as malli]
   [malli.error :as malli-error]
   [malli.transform :as malli-transform]
   [reitit.core :as reitit]
   [groundwork-bridge.settings :as settings]
   [reitit.frontend :as reitit-frontend]))

;; -----------------------------------------------------------------------------
;; Addresses and pages, plain functions over a router

(defn make-router
  "A reitit router of the pages: {:orders/page {:address \"/orders/:order-id\" …}}."
  [pages]
  (reitit/router (vec (for [[page-name page] pages]
                        [(:address page) (assoc page :name page-name)]))))

(defn- remove-keys-that-dont-fit [query errors]
  (apply dissoc query (filter keyword? (keys errors))))

(defn read-query
  "The query's text values read by the page's query shape (a malli map),
   or as they are when the page has none: {:query … :query-errors …}. A
   value that doesn't fit is left out, and its key named in :query-errors
   (nil when every one fits)."
  [query-shape query]
  (if (nil? query-shape)
    {:query query :query-errors nil}
    (let [decoded (malli/decode query-shape query (malli-transform/string-transformer))]
      (if-let [explanation (malli/explain query-shape decoded)]
        (let [errors (malli-error/humanize explanation)]
          {:query (remove-keys-that-dont-fit decoded (if (map? errors) errors {})) :query-errors errors})
        {:query decoded :query-errors nil}))))

(defn turn-address-into-page
  "The page an address (path, query, #fragment) leads to:
   {:name :page :path-params :query :query-errors :fragment :language},
   or nil for an address no page has. languages: nil, or the app's
   {:split-language-from-path (fn [path]) -> {:language :path} …
    :language-of-page …}, the language an address without a prefix is in."
  [router query-shapes-of-pages languages address]
  (let [uri                     (goog.Uri/parse address)
        {:keys [language path]} (if languages
                                  ((:split-language-from-path languages) (.getPath uri))
                                  {:language nil :path (.getPath uri)})]
    (when-let [match (reitit/match-by-path router path)]
      (let [page-name (get-in match [:data :name])]
        (merge {:name        page-name
                :page        (:data match)
                :path-params (:path-params match)
                :fragment    (when (.hasFragment uri) (not-empty (.getFragment uri)))
                :language    (when languages (or language (:language-of-page languages)))}
               (read-query (get query-shapes-of-pages page-name) (reitit-frontend/query-params uri)))))))

(defn- turn-value-into-text [value]
  (cond (keyword? value)    (subs (str value) 1)
        (sequential? value) (to-array (map turn-value-into-text value))
        :else               (str value)))

(defn- make-query-text [query]
  (let [kept (into {} (keep (fn [[k v]] (when (some? v) [(name k) (turn-value-into-text v)]))) query)]
    (when (seq kept)
      (str (goog.Uri.QueryData/createFromMap (clj->js kept))))))

(defn make-address
  "A page's address by name, with its path params ({} for none), its
   query ({} for none; a nil value leaves its key out) and in a language
   (nil without languages), made by the app's :make-path-in-language."
  [router languages page-name path-params query language]
  (let [match (reitit/match-by-name router page-name path-params)]
    (when (or (nil? match) (reitit/partial-match? match))
      (throw (ex-info (str "No page " page-name " with these path params")
                      {:type :groundwork-bridge/no-page :page page-name :path-params path-params})))
    (let [path       (:path match)
          query-text (make-query-text query)]
      (str (if languages ((:make-path-in-language languages) path language) path)
           (when query-text (str "?" query-text))))))

(defn find-kind-of-move
  "How the page at to differs from the one at from: :another-page (its
   name, path params or language), :query (only its query), :fragment
   (only its #fragment), or :none."
  [from to]
  (cond
    (or (nil? from) (nil? to)
        (not= (select-keys from [:name :path-params :language]) (select-keys to [:name :path-params :language])))
    :another-page
    (not= (:query from) (:query to))       :query
    (not= (:fragment from) (:fragment to)) :fragment
    :else                                  :none))

(defn find-scroll-of-move
  "How a page reached by a link or go-to-page! should scroll once drawn:
   [:element id] for its #fragment, :top for another page, :keep when
   only the query changed or nothing did."
  [from to]
  (case (find-kind-of-move from to)
    :another-page (if (:fragment to) [:element (:fragment to)] :top)
    :fragment     (if (:fragment to) [:element (:fragment to)] :keep)
    :keep))

;; -----------------------------------------------------------------------------
;; The browser underneath, as functions; the tests replace them

(defonce ^{:doc "What following asks of the browser; tests replace it."} browser
  (atom {:read-address       (fn [] (str js/location.pathname js/location.search js/location.hash))
         :read-index         (fn [] (some-> js/history.state (unchecked-get "bridge")))
         :push-address!      (fn [address index] (js/history.pushState #js {:bridge index} "" address))
         :replace-address!   (fn [address index] (js/history.replaceState #js {:bridge index} "" address))
         :go!                (fn [delta] (js/history.go delta))
         :set-title!         (fn [title] (set! (.-title js/document) title))
         :read-scroll        (fn [] js/window.scrollY)
         :scroll-to-top!     (fn [] (js/window.scrollTo 0 0))
         :scroll-to-y!       (fn [y] (js/window.scrollTo 0 y))
         :scroll-to-element! (fn [id] (some-> (js/document.getElementById id) (.scrollIntoView)))
         :read-positions     (fn [] (or (some-> (js/sessionStorage.getItem "bridge-scroll") js/JSON.parse (js->clj)) {}))
         :keep-positions!    (fn [positions] (js/sessionStorage.setItem "bridge-scroll" (js/JSON.stringify (clj->js positions))))
         :listen!            (fn [{:keys [on-pop on-link on-scroll on-leave-page]}]
                               (set! (.-scrollRestoration js/history) "manual")
                               (js/window.addEventListener "popstate" (fn [_] (on-pop)))
                               (js/window.addEventListener "scroll" (fn [_] (on-scroll)) #js {:passive true})
                               (js/window.addEventListener "pagehide" (fn [_] (on-leave-page)))
                               (js/document.addEventListener
                                "click"
                                (fn [^js event]
                                  (when (and (zero? (.-button event))
                                             (not (.-defaultPrevented event))
                                             (not (or (.-metaKey event) (.-ctrlKey event) (.-shiftKey event) (.-altKey event))))
                                    (when-let [^js link (some-> (.-target event) (.closest "a[href]"))]
                                      (when (and (= (.-origin link) js/location.origin)
                                                 (str/blank? (.-target link))
                                                 (not (.hasAttribute link "download"))
                                                 (on-link (str (.-pathname link) (.-search link) (.-hash link))))
                                        (.preventDefault event)))))))}))

(defn- ask-browser [k & args]
  (apply (get @browser k) args))

;; -----------------------------------------------------------------------------
;; Following the address bar

(defonce ^:private following
  ;; {:router :options :page :index :reverting? :letting-through :go-after-revert :positions {index y}}
  (atom nil))

(defn- find-page-at [address]
  (let [{:keys [router] {:keys [query-shapes-of-pages languages]} :options :as now} @following]
    (turn-address-into-page router query-shapes-of-pages
                            (when languages (assoc languages :language-of-page (:language-of-page now)))
                            address)))

(defn- arrive!
  "The page is now this one: the tab's title, the language the next
   address keeps, and the app's on-page, with how to scroll."
  [page scroll]
  (swap! following assoc :page page)
  (when page
    (swap! following assoc :language-of-page (:language page))
    (when-let [title (get-in page [:page :title])]
      (ask-browser :set-title! title)))
  ((get-in @following [:options :on-page]) page scroll))

(defn- may-leave?
  "Whether the move goes now: yes without :may-leave?, and for a move that
   stays on the page; else the app's answer, asked with the move."
  [from to move]
  (let [ask (get-in @following [:options :may-leave?])]
    (or (nil? ask) (not= :another-page (find-kind-of-move from to)) (boolean (ask move)))))

(defn- keep-scroll-of-entry! []
  (let [{:keys [index]} @following]
    (swap! following assoc-in [:positions (str index)] (ask-browser :read-scroll))))

(def ways-of-moving
  "How a move to an address goes into the history: :push adds an entry,
   which Back returns from; :replace takes the place of the one shown."
  #{:push :replace})

(defn- check-way! [way]
  (when-not (contains? ways-of-moving way)
    (throw (ex-info (str "A move is :push or :replace, written out, not " (pr-str way))
                    {:type :groundwork-bridge/bad-way :way way}))))

(defn- move-to-address-asking!
  "move-to-address!, asking :may-leave? first when ask? is true."
  [address way ask?]
  (let [{from :page :keys [index]} @following
        to                         (find-page-at address)]
    (cond
      (nil? to)                                                        false
      (and ask? (not (may-leave? from to {:address address :way way :index nil}))) true
      :else
      (let [replace?   (or (= :replace way) (= :none (find-kind-of-move from to)))
            next-index (if replace? index (inc index))]
        (keep-scroll-of-entry!)
        (ask-browser (if replace? :replace-address! :push-address!) address next-index)
        (swap! following assoc :index next-index)
        (arrive! to (find-scroll-of-move from to))
        true))))

(defn move-to-address!
  "Moves to an address, as a link would: true when it leads to a page
   (moved, or kept where it is when :may-leave? said no), false when no
   page has it (the caller lets the browser load it). way: :push adds a
   step for Back, :replace doesn't (a move to the address shown replaces
   either way)."
  [address way]
  (check-way! way)
  (move-to-address-asking! address way true))

(defn- go-to-index!
  "Back or Forward to the history entry with this index, let through
   without asking when its pop comes; nothing when it is shown."
  [to-index]
  (let [delta (- to-index (:index @following))]
    (when-not (zero? delta)
      (swap! following assoc :letting-through to-index)
      (ask-browser :go! delta))))

(defn- follow-pop!
  "Back or Forward has moved the address already: arrive, scrolled where
   that entry was left; or, when :may-leave? says no, put the address back.
   A move finished while the address was being put back goes once it is."
  []
  (let [{from :page :keys [index reverting? positions letting-through go-after-revert]} @following
        address    (ask-browser :read-address)
        to         (find-page-at address)
        to-index   (or (ask-browser :read-index) index)
        delta      (- to-index index)]
    (cond
      reverting?
      (do (swap! following assoc :reverting? false :go-after-revert nil)
          (when go-after-revert (go-to-index! go-after-revert)))

      (and (not (zero? delta)) (not= letting-through to-index)
           (not (may-leave? from to {:address address :way :history :index to-index})))
      (do (swap! following assoc :reverting? true)
          (ask-browser :go! (- delta)))

      :else
      (do (keep-scroll-of-entry!)
          (swap! following assoc :index to-index :letting-through nil)
          (arrive! to (if-let [y (get positions (str to-index))] [:y y] :top))))))

(defn finish-move!
  "Finishes a move :may-leave? held back, as it would have gone, without
   asking again: a link's or go-to-page!'s moves to its address, :push or
   :replace; Back's or Forward's goes back or forward to its entry for
   real, adding none."
  [move]
  (let [{:keys [address way index]} (settings/check-move move)]
    (if (= :history way)
      (if (:reverting? @following)
        (swap! following assoc :go-after-revert index)
        (go-to-index! index))
      (move-to-address-asking! address way false))
    nil))

(defn start-following-address!
  "Follows the address bar (see the namespace): (on-page page scroll) now
   and on every move, page nil for an address no page has. Options, every
   key required: :on-page; :query-shapes-of-pages, a malli map or nil for
   every page, by name, nil reading the query as text; :may-leave?, (fn
   [move]) asked before moving to another page, or nil; :languages, nil, or
   {:split-language-from-path f :make-path-in-language f :language-of-page
   l}, the app's functions and the language the page came in."
  [pages options]
  (settings/check-following-options pages options)
  (let [index     (or (ask-browser :read-index) 0)
        positions (ask-browser :read-positions)]
    (reset! following {:router           (make-router pages)
                       :options          options
                       :page             nil
                       :index            index
                       :reverting?       false
                       :letting-through  nil
                       :go-after-revert  nil
                       :positions        positions
                       :language-of-page (get-in options [:languages :language-of-page])})
    (ask-browser :listen! {:on-pop        follow-pop!
                           :on-link       #(move-to-address! % :push)
                           :on-scroll     keep-scroll-of-entry!
                           :on-leave-page #(ask-browser :keep-positions! (:positions @following))})
    (let [address (ask-browser :read-address)
          page    (find-page-at address)]
      (ask-browser :replace-address! address index)
      (arrive! page (cond (:fragment page)             [:element (:fragment page)]
                          (get positions (str index)) [:y (get positions (str index))]
                          :else                       :keep)))))

(defn make-address-of-page
  "A page's address, in the language of the page now shown."
  [page-name path-params query]
  (let [{:keys [router language-of-page] {:keys [languages]} :options} @following]
    (make-address router languages page-name path-params query language-of-page)))

(defn go-to-page!
  "Moves to a page by name, as a link would, in the language of the page
   now shown; way :push or :replace (ways-of-moving)."
  [page-name path-params query way]
  (check-way! way)
  (move-to-address! (make-address-of-page page-name path-params query) way))

(defn scroll-as-told!
  "Scrolls as a move told: :top, [:y y], [:element id], or :keep (not at all)."
  [scroll]
  (cond
    (= :top scroll)              (ask-browser :scroll-to-top!)
    (= :keep scroll)             nil
    (= :y (first scroll))        (ask-browser :scroll-to-y! (second scroll))
    (= :element (first scroll))  (ask-browser :scroll-to-element! (second scroll))))
