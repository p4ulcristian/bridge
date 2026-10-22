(ns mailroom.routes-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [mailroom.api :as mailroom]
   [mailroom.test-wire :refer [wire]])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)))

(def ^:private handlers
  {:greetings/hello {:steps        [{:name :say-hello :enter #(mailroom/add-answer-to-context % "hello")}]
                     :params-shape nil :answer-shape nil :rate-limit nil :run-steps-in nil}})

(def ^:private options
  {:wire                wire
   :find-rate-limit-key #(mailroom/find-client-address % nil)
   :on-sse-open nil :on-sse-close nil :on-ws-open nil :on-ws-close nil})

(def ^:private settings
  {:ip "127.0.0.1" :port 0 :proxy-protocol :disable
   :client-address-header nil :server-header nil
   :max-body-bytes 8388608 :max-header-line-bytes 8192 :max-ws-message-bytes 4194304
   :addresses {:http "/mail/http" :sse "/mail/sse" :ws "/mail/ws"}
   :ping-every-ms 25000 :sse-reconnect-after-ms 3000 :ws-reopen-after-ms 1000
   :stop-wait-ms 10000 :stop-wait-after-cut-ms 1000
   :rate-limit-buckets-before-cleanup 10000})

(def ^:private pages
  {:orders/page {:address "/orders/:order-id" :title "Order"}})

(defn- page-handler [_request] {:status 200 :body "<html>the app</html>"})

(defn- send-request [port method path body]
  (let [response (.send (HttpClient/newHttpClient)
                        (-> (HttpRequest/newBuilder (URI. (str "http://localhost:" port path)))
                            (.method method (if body (HttpRequest$BodyPublishers/ofString body) (HttpRequest$BodyPublishers/noBody)))
                            (.build))
                        (HttpResponse$BodyHandlers/ofString))]
    [(.statusCode response) (.body response)]))

(deftest an-address-leads-to-its-pipe-or-its-page
  (let [server (mailroom/start-server!
                (mailroom/make-ring-handler-from-routes
                 (into (mailroom/make-routes-for-messages handlers settings options)
                       (mailroom/make-server-routes-for-pages pages page-handler))
                 {:files nil})
                settings)
        port   (:port server)]
    (try
      (testing "a named message on the http address"
        (is (= [200 "{\"greetings/hello\":\"hello\"}"] (send-request port "POST" "/mail/http" "{\"greetings/hello\":{}}"))))
      (testing "a page opened fresh: the page handler"
        (is (= [200 "<html>the app</html>"] (send-request port "GET" "/orders/42" nil))))
      (testing "no route, no page: 404"
        (is (= 404 (first (send-request port "GET" "/nowhere" nil)))))
      (finally (mailroom/stop-server! server)))))

(deftest a-page-needs-a-namespaced-name-an-address-and-a-title
  (let [{:keys [type errors]} (try (mailroom/make-server-routes-for-pages {:page {:address "/x" :title nil} :orders/page {}} page-handler)
                                   (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :mailroom/bad-pages type))
    (is (contains? errors :page) "a name without a namespace")
    (is (= #{:address :title} (set (keys (:orders/page errors)))) "each missing key, named"))
  (is (= 1 (count (mailroom/make-server-routes-for-pages {:orders/page {:address "/orders" :title nil}} page-handler)))
      "a title of nil, written out: the app sets the title itself"))

(defn- make-files-folder []
  (let [folder (.toFile (java.nio.file.Files/createTempDirectory "mailroom-files" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (doseq [[path text] {"main.3F9A0C1D.js" "hashed" "main.3F9A.js" "short" "app.css" "plain" "docs/index.html" "index"}]
      (let [file (java.io.File. folder ^String path)]
        (.mkdirs (.getParentFile file))
        (spit file text)))
    (str folder)))

(deftest files-are-cached-by-the-options
  (let [folder  (make-files-folder)
        handler (mailroom/make-ring-handler-from-routes [] {:files {:folder                     folder
                                                                    :hashed-name-min-hex-digits 8
                                                                    :hashed-file-cache-seconds  600}})
        get-file #(handler {:request-method :get :uri % :headers {}})]
    (testing "a hashed name: kept as long as the option says"
      (is (= "public, max-age=600, immutable" (get-in (get-file "/main.3F9A0C1D.js") [:headers "cache-control"]))))
    (testing "fewer hex digits than the option: not hashed, asked again each time"
      (is (= "no-cache" (get-in (get-file "/main.3F9A.js") [:headers "cache-control"])))
      (is (= "no-cache" (get-in (get-file "/app.css") [:headers "cache-control"]))))
    (testing "a file by its exact name only: a folder's address is no file"
      (is (= 404 (:status (get-file "/docs/"))))
      (is (= 404 (:status (get-file "/docs"))))
      (is (= 200 (:status (get-file "/docs/index.html")))))))

(deftest the-files-options-are-written-out
  (is (= :mailroom/bad-options
         (try (mailroom/make-ring-handler-from-routes [] {:files {:folder "x"}})
              (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
  (is (fn? (mailroom/make-ring-handler-from-routes [] {:files nil}))))
