(ns l2git.core
  "Routing and server bootstrap.

  Git URLs carry the session token and scenario id:
      git clone http://localhost:8070/r/<token>/<scenario>.git
  API — session resources:
      POST /api/session                  -> new session payload
      GET  /api/session/<t>              -> session + scenario list
      GET  /api/session/<t>/events       -> SSE stream of live events
  API — scenario-scoped resources (all under /api/session/<t>/scenario/<id>):
      GET  .../graph           ?at=<n>   -> commit DAG (live, or as of a
                                            timeline point; ghosts included)
      GET  .../timeline                  -> the scrubber's points; each real
                                            point's :idx is what ?at= names
      GET  .../objects         ?at=<n>   -> every object in the database at
                                            that state, sorted by sha
      GET  .../objects/<sha>             -> one object, cat-file style
      POST .../reset                     -> restore the scenario's origin
  Everything else is served from web/dist (the built frontend)."
  (:require [l2git.git-http :as git-http]
            [l2git.graph :as graph]
            [l2git.scenarios :as scenarios]
            [l2git.session :as session]
            [l2git.timeline :as timeline]
            [l2git.web :as web]
            [ring.adapter.jetty :as jetty])
  (:gen-class))

(def port 8070)

;; ---------------------------------------------------------------- payloads

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

(defn- host [req] (web/request-host req (str "localhost:" port)))

;; ---------------------------------------------------------------- handlers

(defn- with-scenario
  "404s for unknown token/scenario; otherwise calls (f entry events)."
  [token scenario-id f]
  (cond
    (nil? (session/lookup token))
    (web/json-response 404 {:error "unknown session"})

    (nil? (scenarios/by-id scenario-id))
    (web/json-response 404 {:error "unknown scenario"})

    :else
    (f (session/repo-for! token scenario-id)
       (:events (session/lookup token)))))

(defn- state-opts
  "Which moment the request asks about: ?at=<idx> names a timeline point;
  absent means live (with every accumulated ghost candidate included)."
  [req scenario-id events]
  (timeline/state-at events scenario-id
                     (some-> (get (web/query-params req) "at") parse-long)))

(defn- h-create-session [req _]
  (web/json-response (session-payload (:token (session/create!)) (host req))))

(defn- h-session [req [token]]
  (if-let [payload (session-payload token (host req))]
    (web/json-response payload)
    (web/json-response 404 {:error "unknown session"})))

(defn- h-events [_ [token]]
  (if (session/lookup token)
    (web/sse-events token)
    (web/json-response 404 {:error "unknown session"})))

(defn- h-graph [req [token scenario-id]]
  (with-scenario token scenario-id
    (fn [{:keys [repo known]} events]
      (web/json-response
       (graph/repo-graph repo known (state-opts req scenario-id events))))))

(defn- h-timeline [_ [token scenario-id]]
  (with-scenario token scenario-id
    (fn [_ events]
      (web/json-response {:points (timeline/client-points events scenario-id)}))))

(defn- h-objects [req [token scenario-id]]
  (with-scenario token scenario-id
    (fn [{:keys [repo]} events]
      (web/json-response
       {:objects (graph/objects-at repo (state-opts req scenario-id events))}))))

(defn- h-object [_ [token scenario-id sha]]
  (with-scenario token scenario-id
    (fn [{:keys [repo]} _]
      (if-let [obj (graph/object-info repo sha)]
        (web/json-response obj)
        (web/json-response 404 {:error "no such object in this repository"})))))

(defn- h-reset [req [token scenario-id]]
  (with-scenario token scenario-id
    (fn [_ _]
      (session/reset-scenario! token scenario-id)
      (web/json-response (session-payload token (host req))))))

;; ----------------------------------------------------------------- routing

(def ^:private scenario-path "/api/session/([a-z0-9]+)/scenario/([a-z0-9-]+)")

(def ^:private api-routes
  [[:post #"^/api/session$"                                  h-create-session]
   [:get  #"^/api/session/([a-z0-9]+)$"                      h-session]
   [:get  #"^/api/session/([a-z0-9]+)/events$"               h-events]
   [:get  (re-pattern (str "^" scenario-path "/graph$"))     h-graph]
   [:get  (re-pattern (str "^" scenario-path "/timeline$"))  h-timeline]
   [:get  (re-pattern (str "^" scenario-path "/objects$"))   h-objects]
   [:get  (re-pattern (str "^" scenario-path "/objects/([0-9a-f]{40})$")) h-object]
   [:post (re-pattern (str "^" scenario-path "/reset$"))     h-reset]])

(defn- api [req]
  (some (fn [[method pattern handler]]
          (when (= method (:request-method req))
            (when-let [m (re-matches pattern (:uri req))]
              (handler req (rest m)))))
        api-routes))

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
    (web/json-response 404 {:error "unknown session token or scenario"})))

(defn app [req]
  (or (when-let [[_ token scenario-id tail] (re-matches git-url-re (:uri req))]
        (handle-git req token scenario-id tail))
      (api req)
      (when (= :get (:request-method req))
        (or (web/static-file (:uri req))
            (web/frontend-missing-hint (:uri req))))
      {:status 404 :body "not found"}))

;; --------------------------------------------------------------- bootstrap

(defn start!
  "Start the server; returns the Jetty instance (useful at the REPL)."
  ([] (start! {:join? false}))
  ([opts]
   (jetty/run-jetty #'app (merge {:port port} opts))))

(defn -main [& _]
  (println (str "l2git listening on http://localhost:" port))
  (start! {:join? true}))
