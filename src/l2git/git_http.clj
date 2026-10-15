(ns l2git.git-http
  "Git smart-HTTP transport, hand-rolled as plain Ring handlers around
  JGit's UploadPack/ReceivePack. Three endpoints per repo:

    GET  …/<scenario>.git/info/refs?service=git-upload-pack|git-receive-pack
    POST …/<scenario>.git/git-upload-pack      (clone/fetch)
    POST …/<scenario>.git/git-receive-pack     (push)

  Owning the request cycle (instead of using JGit's GitServlet) is what
  gives us fetch observation, lazy repo materialization, and in-process
  evaluation with feedback streamed back to the user's terminal as
  'remote:' lines."
  (:require [l2git.scenarios :as scenarios]
            [l2git.session :as session])
  (:import [org.eclipse.jgit.lib Repository]
           [org.eclipse.jgit.transport UploadPack ReceivePack PostReceiveHook
            PacketLineOut ReceiveCommand ReceiveCommand$Result
            RefAdvertiser$PacketLineOutRefAdvertiser]
           [java.io ByteArrayInputStream ByteArrayOutputStream InputStream]
           [java.util.zip GZIPInputStream]))

(defn- request-body
  "The request body, transparently gunzipped — git clients gzip POST bodies."
  ^InputStream [req]
  (let [^InputStream body (:body req)]
    (if (= "gzip" (get-in req [:headers "content-encoding"]))
      (GZIPInputStream. body)
      body)))

(defn- pkt-response [^ByteArrayOutputStream out content-type]
  {:status  200
   :headers {"Content-Type"  content-type
             "Cache-Control" "no-cache, max-age=0, must-revalidate"}
   :body    (ByteArrayInputStream. (.toByteArray out))})

(defn info-refs
  "Smart ref advertisement. `service` is git-upload-pack or git-receive-pack."
  [^Repository repo ^String service]
  (let [out (ByteArrayOutputStream.)
        pck (PacketLineOut. out)]
    (.writeString pck (str "# service=" service "\n"))
    (.end pck)
    (let [adv (RefAdvertiser$PacketLineOutRefAdvertiser. pck)]
      (case service
        "git-upload-pack"  (doto (UploadPack. repo)
                             (.sendAdvertisedRefs adv))
        "git-receive-pack" (doto (ReceivePack. repo)
                             (.sendAdvertisedRefs adv))))
    (pkt-response out (str "application/x-" service "-advertisement"))))

(defn upload-pack
  "Serve a clone/fetch, and record that the session actually fetched —
  observation plain git-http-backend can't give you."
  [^Repository repo token scenario-id req]
  (let [out (ByteArrayOutputStream.)
        up  (doto (UploadPack. repo)
              (.setBiDirectionalPipe false))]
    (.upload up (request-body req) out nil)
    (session/record-event! token {:type :fetch :scenario scenario-id})
    (session/after-fetch! token scenario-id)
    (session/evaluate-quietly! token scenario-id)
    (pkt-response out "application/x-git-upload-pack-result")))

(defn- evaluating-hook
  "PostReceiveHook that runs the scenario's evaluator and talks back to the
  user's terminal via 'remote:' sideband messages."
  ^PostReceiveHook [token scenario-id known]
  (reify PostReceiveHook
    (onPostReceive [_ rp commands]
      ;; Only evaluate when at least one ref actually moved — a fully
      ;; rejected push shouldn't earn confusing evaluator feedback on top
      ;; of git's own rejection.
      (when (some #(= ReceiveCommand$Result/OK (.getResult ^ReceiveCommand %))
                  commands)
        (let [scenario (scenarios/by-id scenario-id)
              repo     (.getRepository ^ReceivePack rp)
              ;; re-read known: an :after-fetch hook may have added to it
              known    (or (:known (session/repo-for! token scenario-id)) known)
              {:keys [pass? message]} ((:evaluate scenario) repo known)]
          (session/record-event! token {:type     :evaluation
                                        :scenario scenario-id
                                        :pass?    pass?
                                        :message  message})
          (when pass?
            (session/complete! token scenario-id))
          (.sendMessage ^ReceivePack rp "")
          (.sendMessage ^ReceivePack rp (str (if pass? "✓ " "✗ ") message))
          (.sendMessage ^ReceivePack rp "")
          ;; Give the scenario a chance to react to the outcome (e.g. a
          ;; teammate restoring work the learner force-pushed away).
          (when-let [hook (:after-evaluate scenario)]
            (when-let [{:keys [known event]}
                       (hook repo known {:pass? pass? :message message})]
              (when known
                (session/merge-known! token scenario-id known))
              (when event
                (session/record-event! token (assoc event :scenario scenario-id))))))))))

(defn receive-pack
  "Accept a push, then evaluate it."
  [^Repository repo token scenario-id req]
  (let [{:keys [known]} (session/repo-for! token scenario-id)
        out (ByteArrayOutputStream.)
        rp  (doto (ReceivePack. repo)
              (.setBiDirectionalPipe false)
              (.setAtomic true)
              (.setPostReceiveHook (evaluating-hook token scenario-id known)))]
    (.receive rp (request-body req) out nil)
    (pkt-response out "application/x-git-receive-pack-result")))
