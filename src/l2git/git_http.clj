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
            PreReceiveHook PacketLineOut ReceiveCommand ReceiveCommand$Result
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
    (session/puppeteer! token scenario-id :after-fetch)
    (session/advance! token scenario-id {:quiet? true})
    (pkt-response out "application/x-git-upload-pack-result")))

(defn- evaluating-hook
  "PostReceiveHook that runs the step engine and talks back to the user's
  terminal via 'remote:' sideband messages. Evaluates when a ref actually
  moved, and also when this push triggered a :before-push ambush — the
  rejection itself can be a step's completion (teammate-pull's collision)."
  ^PostReceiveHook [token scenario-id ambushed?]
  (reify PostReceiveHook
    (onPostReceive [_ rp commands]
      (let [ok? (some #(= ReceiveCommand$Result/OK (.getResult ^ReceiveCommand %))
                      commands)]
        (when (or ok? @ambushed?)
          (when-let [res (session/advance! token scenario-id {:quiet? false})]
            (let [send! #(.sendMessage ^ReceivePack rp ^String %)]
              (send! "")
              (doseq [{:keys [title message]} (:passed res)]
                (send! (str "✓ " (when title (str title " — ")) message)))
              (when-let [{:keys [step title message]} (:fail res)]
                (if (seq (:passed res))
                  ;; Steps were just passed, so this 'failure' is only the
                  ;; next step beginning — announce it instead of scolding.
                  (send! (str "→ next · step " step "/" (:total res)
                              (when title (str " · " title))))
                  (send! (str "✗ "
                              (when title (str "(step " step "/" (:total res) " · " title ") "))
                              message))))
              (when (:complete? res)
                (send! "★ Scenario complete — see you at the next one."))
              (send! ""))
            ;; Give the scenario a chance to react to the outcome (e.g. a
            ;; teammate restoring work the learner force-pushed away).
            (let [scenario (scenarios/by-id scenario-id)]
              (when-let [hook (:after-evaluate scenario)]
                (when-let [{new-known :known event :event}
                           (hook (.getRepository ^ReceivePack rp)
                                 (:known (session/repo-for! token scenario-id))
                                 (:created-at (session/lookup token))
                                 {:pass? (:complete? res)})]
                  (when new-known
                    (session/merge-known! token scenario-id new-known))
                  (when event
                    (session/record-event! token {:type     :teammate
                                                  :scenario scenario-id
                                                  :message  event})))))))))))

(defn receive-pack
  "Accept a push, then evaluate it. The :before-push puppeteering hook fires
  from the pre-receive hook, and only when the push actually targets the
  hook's branch — pushes elsewhere can't summon the teammate, and a POST
  only happens when the client has real updates, so empty pushes can't
  either. The injected commit makes the targeted ref update fail its lock
  check, so the colliding push is rejected; the hook narrates why."
  [^Repository repo token scenario-id req]
  (let [ambushed? (atom false)
        out (ByteArrayOutputStream.)
        rp  (doto (ReceivePack. repo)
              (.setBiDirectionalPipe false)
              (.setAtomic true)
              (.setPreReceiveHook
               (reify PreReceiveHook
                 (onPreReceive [_ rp commands]
                   (when-let [{:keys [branch]} (:before-push (scenarios/by-id scenario-id))]
                     (let [target (str "refs/heads/" branch)]
                       (when (and (some #(= target (.getRefName ^ReceiveCommand %)) commands)
                                  (session/puppeteer! token scenario-id :before-push))
                         (reset! ambushed? true)
                         (.sendMessage ^ReceivePack rp "")
                         (.sendMessage ^ReceivePack rp
                                       "⚡ Someone pushed to this repository while you were working, so your push is based on a stale view of it. Pull their commit and push again.")
                         (.sendMessage ^ReceivePack rp "")))))))
              (.setPostReceiveHook (evaluating-hook token scenario-id ambushed?)))]
    (.receive rp (request-body req) out nil)
    (pkt-response out "application/x-git-receive-pack-result")))
