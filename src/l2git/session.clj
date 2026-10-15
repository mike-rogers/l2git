(ns l2git.session
  "Session state, keyed by token. No accounts — a token is the identity,
  bookmarkable like the original. Repos are materialized lazily on first
  git contact and live only in memory; everything needed to reason about
  a session (known hashes, event log, status) lives in this map."
  (:require [l2git.repo-gen :as rg]
            [l2git.scenarios :as scenarios])
  (:import [java.security SecureRandom]
           [java.time Instant]
           [java.util.concurrent BlockingQueue LinkedBlockingQueue]))

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

(defn record-event!
  "Append an event to the session log (e.g. {:type :fetch :scenario id})
  and push it to any live SSE listeners."
  [token event]
  (let [stamped (assoc event :at (str (Instant/now)))]
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
                      entry {:repo repo :known known :status :in-progress}]
                  (swap! sessions assoc-in [token :scenarios scenario-id] entry)
                  entry)))))))

(defn complete!
  "Mark a scenario complete for the session."
  [token scenario-id]
  (swap! sessions assoc-in [token :scenarios scenario-id :status] :complete))

(defn evaluate-quietly!
  "Evaluate the scenario's current repo state outside a push (e.g. after a
  fetch). Records and completes only on a pass — a fetch of unfinished work
  earns no noisy failure. Covers flows where the goal state is reached by
  pulling rather than pushing (e.g. a teammate restoring clobbered work)."
  [token scenario-id]
  (when-let [{:keys [repo known status]} (get-in @sessions [token :scenarios scenario-id])]
    (when (not= :complete status)
      (let [{:keys [pass? message]} ((:evaluate (scenarios/by-id scenario-id)) repo known)]
        (when pass?
          (record-event! token {:type     :evaluation
                                :scenario scenario-id
                                :pass?    true
                                :message  message})
          (swap! sessions assoc-in [token :scenarios scenario-id :status] :complete))))))

(defn merge-known!
  "Merge new entries into a scenario's known map."
  [token scenario-id new-known]
  (swap! sessions update-in [token :scenarios scenario-id :known] merge new-known))

(defn after-fetch!
  "Run the scenario's :after-fetch hook, if any — the puppeteering point
  where 'teammates' get to touch the origin once the learner has fetched.
  The hook is (fn [repo known]) -> map of new known entries, or nil for
  no-op; idempotency is the hook's job (check its own known key). Returned
  entries merge into the session's known map. Emits an event when the hook
  acted, so the UI sees the teammate move live."
  [token scenario-id]
  (when-let [hook (:after-fetch (scenarios/by-id scenario-id))]
    (locking sessions
      (when-let [{:keys [repo known]} (get-in @sessions [token :scenarios scenario-id])]
        (when-let [new-known (hook repo known)]
          (merge-known! token scenario-id new-known)
          (record-event! token {:type     :teammate
                                :scenario scenario-id
                                :message  "Pat pushed a commit to main"}))))))

(defn status
  "JSON-safe view of a session."
  [token]
  (when-let [s (lookup token)]
    {:token      (:token s)
     :created-at (str (:created-at s))
     :scenarios  (into {}
                       (map (fn [[id {:keys [status]}]] [id {:status (name status)}]))
                       (:scenarios s))
     :events     (:events s)}))
