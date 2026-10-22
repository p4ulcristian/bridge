(ns mailroom.routes
  "The browser's routes, with reitit-frontend: the address in the bar leads
   to a page, found by name. Back and Forward work (HTML5 history), and the
   page's :title becomes the tab's title on every move. The pages come from
   the server, in the page, already checked there."
  (:require
   [reitit.frontend :as reitit]
   [reitit.frontend.easy :as easy]))

(defn- set-tab-title! [match]
  (when-let [title (get-in match [:data :title])]
    (set! (.-title js/document) title)))

(defn- turn-match-into-page [match]
  (when match
    {:name        (get-in match [:data :name])
     :page        (:data match)
     :path-params (:path-params match)
     :query       (:query-params match)}))

(defn- turn-pages-into-reitit-routes [pages]
  (vec (for [[page-name page] pages]
         [(:address page) (assoc page :name page-name)])))

(defn start-following-address!
  "Follows the address bar: (on-page {:name … :page … :path-params … :query …})
   now and on every move, nil for an address no page has."
  [pages on-page]
  (easy/start! (reitit/router (turn-pages-into-reitit-routes pages))
               (fn [match _history]
                 (set-tab-title! match)
                 (on-page (turn-match-into-page match)))
               {:use-fragment false}))

(defn go-to-page!
  "Moves to the page with this name and these path params; Back returns."
  ([page-name] (go-to-page! page-name {}))
  ([page-name path-params] (easy/push-state page-name path-params)))

(defn make-address-of-page
  "A page's address, for a link: (make-address-of-page :orders/page {:order-id 42}) -> \"/orders/42\"."
  ([page-name] (make-address-of-page page-name {}))
  ([page-name path-params] (easy/href page-name path-params)))
