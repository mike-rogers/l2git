(ns l2git.web
  "HTTP plumbing that isn't routing: JSON responses, query-string parsing,
  the SSE event stream, and static-file serving for the built frontend.
  l2git.core owns which URLs exist; this namespace owns how bytes move."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [l2git.session :as session]
            [ring.core.protocols :as ring-proto]
            [ring.util.mime-type :as mime])
  (:import [java.util.concurrent BlockingQueue TimeUnit]))

(defn json-response
  ([body] (json-response 200 body))
  ([status body]
   {:status  status
    :headers {"Content-Type" "application/json"}
    :body    (json/write-str body)}))

(defn request-host
  "The host:port the client used to reach us — clone URLs must be built from
  it, or containers/port-mappings/LAN access would hand out broken commands."
  [req & [default]]
  (get-in req [:headers "host"] default))

(defn query-params
  "Tiny query-string parser: 'a=1&b=2' -> {\"a\" \"1\", \"b\" \"2\"}.
  Values are split on the first '=' only and URL-decoded afterwards —
  browsers (URLSearchParams) percent-encode ','/'=' inside values, curl
  doesn't; both must parse the same."
  [req]
  (let [decode #(java.net.URLDecoder/decode ^String % "UTF-8")]
    (into {}
          (keep (fn [pair]
                  (let [i (string/index-of pair "=")]
                    (when (and i (pos? i))
                      [(decode (subs pair 0 i)) (decode (subs pair (inc i)))]))))
          (string/split (or (:query-string req) "") #"&"))))

;; --------------------------------------------------------------------- SSE

(defn sse-events
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

;; ------------------------------------------------------------ static files

(def ^:private web-dist "web/dist")

(defn static-file [^String uri]
  (let [path (if (= uri "/") "index.html" (subs uri 1))
        f    (io/file web-dist path)]
    (when (and (.isFile f)
               ;; keep path traversal out
               (-> f .getCanonicalPath
                   (.startsWith (.getCanonicalPath (io/file web-dist)))))
      {:status  200
       :headers {"Content-Type" (or (mime/ext-mime-type path) "application/octet-stream")}
       :body    f})))

(defn frontend-missing-hint [uri]
  (when (= uri "/")
    {:status  200
     :headers {"Content-Type" "text/plain"}
     :body    (str "l2git is running, but the frontend isn't built yet.\n"
                   "Run: cd web && npm install && npm run build\n"
                   "then reload. (API is live: POST /api/session)\n")}))
