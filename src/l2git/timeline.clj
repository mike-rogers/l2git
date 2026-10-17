(ns l2git.timeline
  "The scrubber's view of a session: the ordered moments the remote learned
  something, derived from the event log. This is the single source of truth
  for timeline semantics — the client renders points and asks for states by
  index (?at=), never reassembling refs or ghost lists itself.

  Event schema (what session/record-event! and friends produce):
    {:type     :fetch | :teammate | :push | :step | :evaluation | :reset
     :scenario scenario-id string
     :at       ISO-8601 instant string
     :refs     {branch-name tip-hash}      ; stamped when the repo exists
     ;; :push only
     :commands [{:ref branch :old sha :new sha :result s :message s?}]
     ;; :teammate / :step / :evaluation / :reset
     :message  human text
     :pass?    boolean (:evaluation only)}

  Only :fetch, :teammate and :push become timeline points — they are remote
  history; :step/:evaluation events are coaching. Points before the latest
  :reset are dropped: a reset discards the repo, so earlier snapshots name
  objects that no longer exist.

  Ghost candidates accumulate along the way: every snapshot's branch tips
  (so a force-push-clobbered commit still renders, dashed) plus rejected
  pushes' attempted ids (whose objects landed in the store before the ref
  update was refused). The graph marks reachable ones normal, so
  over-offering is harmless.")

(defn- scenario-events [events scenario-id]
  (let [mine       (filterv #(= scenario-id (:scenario %)) events)
        last-reset (last (keep-indexed (fn [i e] (when (= :reset (:type e)) i)) mine))]
    (if last-reset (subvec mine (inc last-reset)) mine)))

(defn- offer [ghosts shas]
  (into ghosts (remove (set ghosts)) shas))

(defn points
  "The full points for a scenario, oldest first:
  {:icon :label :note? :at :refs :ghosts}."
  [events scenario-id]
  (loop [evs (seq (scenario-events events scenario-id))
         ghosts []
         fetched? false
         acc []]
    (if-not evs
      acc
      (let [e    (first evs)
            refs (:refs e)]
        (if-not refs
          (recur (next evs) ghosts fetched? acc)
          (let [ghosts (offer ghosts (vals refs))
                [point ghosts fetched?]
                (case (:type e)
                  :fetch
                  [{:icon  "⇣"
                    :label (if fetched? "you fetched" "you cloned")
                    :note  "From here the remote can't see you: your commits, your stage, your working directory all live on your machine until you push."}
                   ghosts true]

                  :teammate
                  [{:icon "⚡" :label (or (:message e) "a teammate acted")}
                   ghosts fetched?]

                  :push
                  (let [rejected (filterv #(not= "OK" (:result %)) (:commands e))
                        ghosts   (offer ghosts (map :new rejected))]
                    [(if (seq rejected)
                       {:icon  "✗"
                        :label "your push was rejected"
                        :note  "Notice the branches didn't move — that's what a rejection means. But your commit did arrive: it's in the object store with no label pointing at it, shown dashed."}
                       {:icon "⇡" :label "you pushed"})
                     ghosts fetched?])

                  [nil ghosts fetched?])]
            (recur (next evs)
                   ghosts
                   fetched?
                   (if point
                     (conj acc (assoc point :at (:at e) :refs refs :ghosts ghosts))
                     acc))))))))

(defn client-points
  "Points as the API serves them: :refs/:ghosts stay server-side; each real
  point carries the stable :idx that ?at= refers to, and a trailing live
  point (:at nil, :live true) stands for the remote as it is now."
  [events scenario-id]
  (conj (vec (map-indexed (fn [i p] (-> p (dissoc :refs :ghosts) (assoc :idx i)))
                          (points events scenario-id)))
        {:icon "●" :label "now" :at nil :live true}))

(defn state-at
  "Graph/objects options for the moment ?at=<idx> names, or for live (all
  accumulated ghost candidates, current refs) when idx is nil/out of range."
  [events scenario-id idx]
  (let [pts (points events scenario-id)]
    (if (and idx (<= 0 idx) (< idx (count pts)))
      (let [p (nth pts idx)]
        {:refs-override (:refs p) :extra-tips (:ghosts p)})
      {:extra-tips (:ghosts (peek pts))})))
