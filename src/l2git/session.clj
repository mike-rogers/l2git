(ns l2git.session
  "Session state, keyed by token. No accounts — a token is the identity,
  bookmarkable like the original. Repos are materialized lazily on first
  git contact and live only in memory; everything needed to reason about
  a session (known hashes, event log, status) lives in this map."
  (:require [l2git.repo-gen :as rg]
            [l2git.scenarios :as scenarios])
  (:import [java.security SecureRandom]
           [java.time Instant]
           [java.util.concurrent BlockingQueue LinkedBlockingQueue]
           [org.eclipse.jgit.lib Repository Ref]))

(defonce sessions (atom {}))

;; Live listeners (SSE connections), kept out of the session maps so status
;; stays JSON-safe: token -> #{BlockingQueue}.
(defonce listeners (atom {}))

(defn add-listener! ^BlockingQueue [token]
  (let [q (LinkedBlockingQueue.)]
    (swap! listeners update token (fnil conj #{}) q)
    q))

(defn remove-listener! [token q]
  (swap! listeners update token disj q))

(defn- broadcast! [token event]
  (doseq [^BlockingQueue q (get @listeners token)]
    (.offer q event)))

(def ^:private ^SecureRandom rng (SecureRandom.))
(def ^:private token-alphabet "abcdefghijklmnopqrstuvwxyz0123456789")

(defn- new-token []
  (apply str (repeatedly 12 #(nth token-alphabet (.nextInt rng 36)))))

(defn create!
  "Create a session; returns its map."
  []
  (let [token   (new-token)
        session {:token      token
                 :created-at (Instant/now)
                 :scenarios  {}   ; scenario-id -> {:repo r :known {} :status :in-progress|:complete}
                 :events     []}]
    (swap! sessions assoc token session)
    session))

(defn lookup [token]
  (get @sessions token))

(defn- ref-snapshot
  "branch -> tip hash for every refs/heads/* in the repo."
  [^Repository repo]
  (into {}
        (map (fn [^Ref r] [(Repository/shortenRefName (.getName r))
                           (.name (.getObjectId r))]))
        (.getRefsByPrefix (.getRefDatabase repo) "refs/heads/")))

(defn record-event!
  "Append an event to the session log (e.g. {:type :fetch :scenario id})
  and push it to any live SSE listeners. Events about a materialized
  scenario are stamped with a :refs snapshot (branch -> hash at that
  moment) — repos never garbage-collect, so these snapshots are enough to
  replay the remote's graph at any past event (the timeline scrubber).
  Snapshots can't be recorded retroactively, hence stamping them long
  before any UI consumes them."
  [token event]
  (let [repo    (when-let [sid (:scenario event)]
                  (get-in @sessions [token :scenarios sid :repo]))
        stamped (cond-> (assoc event :at (str (Instant/now)))
                  repo (assoc :refs (ref-snapshot repo)))]
    (swap! sessions update-in [token :events] (fnil conj []) stamped)
    (broadcast! token stamped)))

(defn repo-for!
  "The scenario's origin repo for this session, materializing it on first
  contact. Returns {:repo r :known {...} :status kw} or nil if the token or
  scenario is unknown."
  [token scenario-id]
  (when-let [session (lookup token)]
    (when-let [scenario (scenarios/by-id scenario-id)]
      (or (get-in session [:scenarios scenario-id])
          (locking sessions
            (or (get-in @sessions [token :scenarios scenario-id])
                (let [repo  (rg/new-repo (str token "/" scenario-id))
                      known ((:setup scenario) repo (:created-at session))
                      entry {:repo repo :known known :status :in-progress :step-idx 0}]
                  (swap! sessions assoc-in [token :scenarios scenario-id] entry)
                  entry)))))))

(defn complete!
  "Mark a scenario complete for the session."
  [token scenario-id]
  (swap! sessions assoc-in [token :scenarios scenario-id :status] :complete))

(defn advance!
  "Run the step engine against the scenario's current repo state, persist
  progress, and record events: a :step event per newly passed step (the last
  one becomes the :evaluation pass event when the scenario completes, which
  is what the UI's banner listens for). With :quiet? true (fetches), a
  failing current step records nothing — a fetch of unfinished work earns no
  noisy failure, but progress made by pulling still advances and completes.
  Returns the engine result, or nil if there was nothing to evaluate."
  [token scenario-id {:keys [quiet?]}]
  (when-let [{:keys [repo known status step-idx]} (get-in @sessions [token :scenarios scenario-id])]
    (when (not= :complete status)
      (let [res (scenarios/evaluate-progress (scenarios/by-id scenario-id)
                                             repo known (or step-idx 0))
            step-msg (fn [{:keys [title message]}]
                       (str (when title (str title " — ")) message))
            passed   (:passed res)]
        (swap! sessions assoc-in [token :scenarios scenario-id :step-idx] (:idx res))
        (doseq [p (if (:complete? res) (butlast passed) passed)]
          (record-event! token {:type     :step
                                :scenario scenario-id
                                :message  (step-msg p)}))
        (cond
          (:complete? res)
          (do (record-event! token {:type     :evaluation
                                    :scenario scenario-id
                                    :pass?    true
                                    :message  (step-msg (last passed))})
              (swap! sessions assoc-in [token :scenarios scenario-id :status] :complete))

          ;; A fail right after passed steps is just the next step starting —
          ;; the step events already tell that story, so no failure event.
          (and (:fail res) (empty? passed) (not quiet?))
          (record-event! token {:type     :evaluation
                                :scenario scenario-id
                                :pass?    false
                                :message  (let [{:keys [title message]} (:fail res)]
                                            (str (when title (str title ": ")) message))}))
        res))))

(defn merge-known!
  "Merge new entries into a scenario's known map."
  [token scenario-id new-known]
  (swap! sessions update-in [token :scenarios scenario-id :known] merge new-known))

(defn puppeteer!
  "Run one of the scenario's puppeteering hooks (:after-fetch or
  :before-push), if present — the points where 'teammates' get to touch the
  origin in reaction to the learner. The hook is
  (fn [repo known created-at]) -> {:known {} :event str} or nil; idempotency
  is the hook's job (its :once key). Returned known entries merge into the
  session's map, and the event reaches the UI live."
  [token scenario-id hook-key]
  (when-let [hook (hook-key (scenarios/by-id scenario-id))]
    (locking sessions
      (when-let [{:keys [repo known]} (get-in @sessions [token :scenarios scenario-id])]
        (when-let [{new-known :known event :event}
                   ((if (map? hook) (:run hook) hook)
                    repo known (get-in @sessions [token :created-at]))]
          (when new-known
            (merge-known! token scenario-id new-known))
          (when event
            (record-event! token {:type     :teammate
                                  :scenario scenario-id
                                  :message  event}))
          true)))))

(defn reset-scenario!
  "Reset a scenario to its starting state by discarding its repo and known
  map; the next git contact (or graph view) rematerializes it. Because repos
  are pure functions of (scenario, token, session-created-at), the reborn
  seed commits are bit-identical to the originals. The learner's local clone
  still holds their old history, so the guidance is to re-clone."
  [token scenario-id]
  (locking sessions
    (when (get-in @sessions [token :scenarios scenario-id])
      (swap! sessions update-in [token :scenarios] dissoc scenario-id)
      (record-event! token {:type     :reset
                            :scenario scenario-id
                            :message  "Scenario reset to its starting state — delete your local clone and clone fresh."})
      true)))

(defn status
  "JSON-safe view of a session."
  [token]
  (when-let [s (lookup token)]
    {:token      (:token s)
     :created-at (str (:created-at s))
     :scenarios  (into {}
                       (map (fn [[id {:keys [status step-idx]}]]
                              [id {:status (name status) :step-idx (or step-idx 0)}]))
                       (:scenarios s))
     :events     (:events s)}))
