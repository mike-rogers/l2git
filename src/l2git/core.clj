(ns l2git.core
  "HTTP routing and server bootstrap.

  Git URLs carry the session token and scenario id:
      git clone http://localhost:8070/r/<token>/<scenario>.git
  API:
      POST /api/session                            -> new session payload
      GET  /api/session/<token>                    -> session + scenario list
      GET  /api/session/<token>/graph/<scenario>   -> commit DAG for the viz
           ?refs=main=<sha>,...   render as of a past ref snapshot
           ?extra=<sha>,...       include these tips too, marked :ghost
      GET  /api/session/<token>/object/<scenario>/<sha> -> x-ray one object
      GET  /api/session/<token>/events             -> SSE stream of live events
  Everything else is served from web/dist (the built frontend)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [l2git.git-http :as git-http]
            [l2git.graph :as graph]
            [l2git.scenarios :as scenarios]
            [l2git.session :as session]
            [ring.adapter.jetty :as jetty]
            [ring.core.protocols :as ring-proto]
            [ring.util.mime-type :as mime])
  (:import [java.util.concurrent BlockingQueue TimeUnit]
           [java.io OutputStream])
  (:gen-class))

(def port 8070)

(defn- json-response
  ([body] (json-response 200 body))
  ([status body]
   {:status  status
    :headers {"Content-Type" "application/json"}
    :body    (json/write-str body)}))

;; ---------------------------------------------------------------- sessions

(defn- request-host
  "The host:port the client used to reach us — clone URLs must be built from
  it, or containers/port-mappings/LAN access would hand out broken commands."
  [req]
  (get-in req [:headers "host"] (str "localhost:" port)))

(defn- clone-url [host token scenario-id]
  (str "http://" host "/r/" token "/" scenario-id ".git"))

(defn- session-payload
  "Everything the frontend needs to (re)hydrate a session: the scenario
  catalog with per-session clone URLs, statuses, and step progress, plus
  the event log."
  [token host]
  (when-let [s (session/status token)]
    (assoc s :scenarios
           (mapv (fn [{:keys [id title instructions level steps]}]
                   (let [status    (get-in s [:scenarios id :status] "not-started")
                         complete? (= status "complete")
                         step-idx  (get-in s [:scenarios id :step-idx] 0)]
                     {:id           id
                      :title        title
                      :level        (name level)
                      :instructions instructions
                      :clone-url    (clone-url host token id)
                      :status       status
                      :current-step step-idx
                      :steps        (vec (map-indexed
                                          (fn [i st]
                                            {:id           (name (:id st))
                                             :title        (:title st)
                                             :instructions (:instructions st)
                                             :hints        (:hints st)
                                             :status       (cond
                                                             (or complete? (< i step-idx)) "complete"
                                                             (= i step-idx)                "current"
                                                             :else                         "upcoming")})
                                          steps))}))
                 scenarios/catalog))))

(defn- query-params
  "Tiny query-string parser: 'a=1&b=2' -> {\"a\" \"1\", \"b\" \"2\"}.
  Values are split on the first '=' only, so snapshot refs ('main=<sha>')
  survive inside a value."
  [req]
  (into {}
        (keep (fn [pair]
                (let [i (string/index-of pair "=")]
                  (when (and i (pos? i))
                    [(subs pair 0 i) (subs pair (inc i))]))))
        (string/split (or (:query-string req) "") #"&")))

(defn- graph-opts
  "Scrubber params for the graph endpoint: refs=main=<sha>,feat=<sha> is a
  past ref snapshot to render instead of the live refs; extra=<sha>,<sha>
  are tips to include as ghosts (e.g. a rejected push's commit)."
  [req]
  (let [qp        (query-params req)
        parse-ref (fn [s] (let [i (string/index-of s "=")]
                            (when (and i (pos? i))
                              [(subs s 0 i) (subs s (inc i))])))]
    {:refs-override (some->> (get qp "refs")
                             (#(string/split % #","))
                             (keep parse-ref)
                             seq
                             (into {}))
     :extra-tips    (some-> (get qp "extra")
                            (string/split #","))}))

;; --------------------------------------------------------------------- SSE

(defn- sse-events
  "Server-sent events: everything record-event! broadcasts for this token,
  JSON per event. A comment ping every 15s keeps proxies and EventSource
  honest; a dead client surfaces as an IOException on write."
  [token]
  (let [^BlockingQueue q (session/add-listener! token)]
    {:status  200
     :headers {"Content-Type"      "text/event-stream"
               "Cache-Control"     "no-cache"
               "X-Accel-Buffering" "no"}
     :body
     (reify ring-proto/StreamableResponseBody
       (write-body-to-stream [_ _ os]
         (let [w (io/writer os)]
           (try
             (.write w "retry: 2000\n\n")
             (.flush w)
             (loop []
               (if-let [ev (.poll q 15 TimeUnit/SECONDS)]
                 (do (.write w (str "data: " (json/write-str ev) "\n\n"))
                     (.flush w))
                 (do (.write w ": ping\n\n")
                     (.flush w)))
               (recur))
             (catch Exception _)                 ; client went away
             (finally
               (session/remove-listener! token q))))))}))

;; --------------------------------------------------------------------- git

(def ^:private git-url-re #"^/r/([a-z0-9]+)/([a-z0-9-]+)\.git(/.*)?$")

(defn- handle-git [req token scenario-id ^String tail]
  (if-let [{:keys [repo]} (session/repo-for! token scenario-id)]
    (cond
      (and (= :get (:request-method req)) (= tail "/info/refs"))
      (let [service (get (or (some->> (:query-string req)
                                      (re-find #"service=([a-z-]+)")) [])
                         1)]
        (if (#{"git-upload-pack" "git-receive-pack"} service)
          (git-http/info-refs repo service)
          {:status 400 :body "smart HTTP only; specify ?service="}))

      (and (= :post (:request-method req)) (= tail "/git-upload-pack"))
      (git-http/upload-pack repo token scenario-id req)

      (and (= :post (:request-method req)) (= tail "/git-receive-pack"))
      (git-http/receive-pack repo token scenario-id req)

      :else
      {:status 404 :body "not found"})
    (json-response 404 {:error "unknown session token or scenario"})))

;; ------------------------------------------------------------ static files

(def ^:private web-dist "web/dist")

(defn- static-file [^String uri]
  (let [path (if (= uri "/") "index.html" (subs uri 1))
        f    (io/file web-dist path)]
    (when (and (.isFile f)
               ;; keep path traversal out
               (-> f .getCanonicalPath
                   (.startsWith (.getCanonicalPath (io/file web-dist)))))
      {:status  200
       :headers {"Content-Type" (or (mime/ext-mime-type path) "application/octet-stream")}
       :body    f})))

;; ----------------------------------------------------------------- routing

(defn app [req]
  (let [uri    (:uri req)
        method (:request-method req)]
    (cond
      (re-matches git-url-re uri)
      (let [[_ token scenario-id tail] (re-matches git-url-re uri)]
        (handle-git req token scenario-id tail))

      (and (= :post method) (= uri "/api/session"))
      (json-response (session-payload (:token (session/create!)) (request-host req)))

      (and (= :post method)
           (re-matches #"^/api/session/([a-z0-9]+)/reset/([a-z0-9-]+)$" uri))
      (let [[_ token scenario-id]
            (re-matches #"^/api/session/([a-z0-9]+)/reset/([a-z0-9-]+)$" uri)]
        (cond
          (nil? (session/lookup token))        (json-response 404 {:error "unknown session"})
          (nil? (scenarios/by-id scenario-id)) (json-response 404 {:error "unknown scenario"})
          :else (do (session/reset-scenario! token scenario-id)
                    (json-response (session-payload token (request-host req))))))

      (and (= :get method)
           (re-matches #"^/api/session/([a-z0-9]+)/object/([a-z0-9-]+)/([0-9a-f]{40})$" uri))
      (let [[_ token scenario-id sha]
            (re-matches #"^/api/session/([a-z0-9]+)/object/([a-z0-9-]+)/([0-9a-f]{40})$" uri)]
        (if-let [{:keys [repo]} (session/repo-for! token scenario-id)]
          (if-let [obj (graph/object-info repo sha)]
            (json-response obj)
            (json-response 404 {:error "no such object in this repository"}))
          (json-response 404 {:error "unknown session token or scenario"})))

      (= :get method)
      (if-let [[_ token sub scenario-id]
               (re-matches #"^/api/session/([a-z0-9]+)(?:/(events|graph)(?:/([a-z0-9-]+))?)?$" uri)]
        (cond
          (nil? (session/lookup token))
          (json-response 404 {:error "unknown session"})

          (nil? sub)
          (json-response (session-payload token (request-host req)))

          (= sub "events")
          (sse-events token)

          (= sub "graph")
          (if-let [{:keys [repo known]} (session/repo-for! token scenario-id)]
            (json-response (graph/repo-graph repo known (graph-opts req)))
            (json-response 404 {:error "unknown scenario"}))

          :else
          (json-response 404 {:error "not found"}))
        (or (static-file uri)
            (if (= uri "/")
              {:status  200
               :headers {"Content-Type" "text/plain"}
               :body    (str "l2git is running, but the frontend isn't built yet.\n"
                             "Run: cd web && npm install && npm run build\n"
                             "then reload. (API is live: POST /api/session)\n")}
              {:status 404 :body "not found"})))

      :else
      {:status 404 :body "not found"})))

(defn start!
  "Start the server; returns the Jetty instance (useful at the REPL)."
  ([] (start! {:join? false}))
  ([opts]
   (jetty/run-jetty #'app (merge {:port port} opts))))

(defn -main [& _]
  (println (str "l2git listening on http://localhost:" port))
  (start! {:join? true}))
