(ns bridge.routes
  "The server's routes, with reitit: the three addresses, and each page,
   answered by the app's page handler when it is opened fresh; files (the
   browser's code, its styles) from a folder on disk. Any other address is
   a 404.

   The app's pages are a map by name, {:orders/page {:address \"/orders/:order-id\" …}};
   the browser gets the same map from the page (the app puts it there), so
   the two sides can't disagree."
  (:require
   [bridge.settings :as settings]
   [reitit.ring :as reitit]))

(defn make-server-routes-for-pages
  "A GET route for each page, answered by page-handler (the page's HTML and
   the app's JS), so a page opened fresh or reloaded works. The pages are
   checked first (settings/pages-shape): each a namespaced name, an
   :address and a :title (nil when the app sets the title itself)."
  [pages page-handler]
  (settings/check-pages pages)
  (vec (for [[page-name page] pages]
         [(:address page) {:name page-name :get {:handler page-handler}}])))

(defn- make-hashed-name-pattern
  "main.3F9A0C1D2E….js: a release build's file, whose name changes with its
   content; at least min-hex-digits hex digits before the extension."
  [min-hex-digits]
  (re-pattern (str "\\.[0-9A-Fa-f]{" min-hex-digits ",}\\.[a-z]+$")))

(defn- does-browser-take-gzip? [request]
  (boolean (some-> (get-in request [:headers "accept-encoding"]) (.contains "gzip"))))

(defn- send-gzipped-copy-when-there
  "A built file with file.gz beside it goes out gzipped to a browser that
   takes gzip: the same file, a tenth of the bytes."
  [file-handler files-folder]
  (fn [request]
    (let [gzipped (when (does-browser-take-gzip? request)
                    (java.io.File. (str files-folder (:uri request) ".gz")))]
      (if-let [response (and gzipped (.isFile gzipped) (file-handler request))]
        (-> response
            (assoc :body gzipped)
            (update :headers dissoc "Content-Length" "content-length")
            (assoc-in [:headers "content-encoding"] "gzip")
            (assoc-in [:headers "vary"] "accept-encoding"))
        (file-handler request)))))

(defn- add-cache-header
  "A hashed file never changes, so it is kept :hashed-file-cache-seconds;
   any other file is asked again each time (its name stays when it
   changes)."
  [file-handler {:keys [hashed-name-min-hex-digits hashed-file-cache-seconds]}]
  (let [hashed-name (make-hashed-name-pattern hashed-name-min-hex-digits)
        kept        (str "public, max-age=" hashed-file-cache-seconds ", immutable")]
    (fn [request]
      (when-let [response (file-handler request)]
        (assoc-in response [:headers "cache-control"]
                  (if (re-find hashed-name (:uri request)) kept "no-cache"))))))

(defn- make-file-handler
  "Files from the folder, by their exact names: a folder's address is never
   answered with a file in it nor redirected (a page is answered by name,
   by the page handler), and no link leads out of the folder. Nil when the
   folder has no such file, so the default handler answers."
  [{:keys [folder] :as files}]
  (-> (reitit/create-file-handler {:path               "/"
                                   :root               folder
                                   :index-files        []
                                   :canonicalize-uris? false
                                   :index-redirect?    false
                                   :allow-symlinks?    false})
      (send-gzipped-copy-when-there folder)
      (add-cache-header files)))

(defn- make-default-handler
  "Written out as reitit's own: no route and no file, not-found's answer
   (or 404, empty, without one); a route without the request's method, 405;
   a handler that answered nil, 406."
  [not-found]
  (reitit/create-default-handler {:not-found          (or not-found (constantly {:status 404 :headers {} :body ""}))
                                  :method-not-allowed (constantly {:status 405 :headers {} :body ""})
                                  :not-acceptable     (constantly {:status 406 :headers {} :body ""})}))

(defn make-ring-handler-from-routes
  "The one Ring handler for the server, from reitit routes. With :files
   (nil for none), a GET no route matches is answered from its :folder on
   disk, when it has the file. Otherwise a path that matches no route is
   answered by :not-found (nil: 404, empty), a method the route doesn't
   have 405."
  [routes {:keys [files not-found]}]
  (reitit/ring-handler (reitit/router routes)
                       (if files
                         (reitit/routes (make-file-handler files) (make-default-handler not-found))
                         (make-default-handler not-found))))
