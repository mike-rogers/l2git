(ns l2git.graph
  "Serializes a repo's commit DAG for the live visualization: every commit
  reachable from any branch, topo-ordered newest-first, plus refs and HEAD.
  Seeded (curator-made) commits are flagged so the UI can distinguish 'was
  already here' from 'you made this'.

  Two extensions serve the timeline scrubber and x-ray:
  - :refs-override renders the graph as of a past moment (the branch->hash
    snapshots stamped onto events) instead of the live refs;
  - :extra-tips adds commits reachable only from the given hashes, marked
    :ghost — objects that are in the store but that no branch points at
    (rejected pushes, pre-rewrite originals). Repos never GC, so they're
    always still there to draw.

  object-info is the x-ray itself: a cat-file-like view of any one object."
  (:import [org.eclipse.jgit.lib Repository Ref ObjectId Constants PersonIdent]
           [org.eclipse.jgit.revwalk RevWalk RevSort RevCommit]
           [org.eclipse.jgit.treewalk TreeWalk]))

(defn- mark-starts
  "Mark hashes as walk starts, ignoring ones that don't resolve to commits
  (snapshots can mention objects a reset discarded)."
  [^RevWalk rw hashes]
  (doseq [^String h hashes]
    (try
      (.markStart rw (.parseCommit rw (ObjectId/fromString h)))
      (catch Exception _))))

(defn- reachable-from
  "The set of commit hashes reachable from the given tip hashes."
  [^Repository repo tips]
  (with-open [rw (RevWalk. repo)]
    (mark-starts rw tips)
    (into #{} (map #(.name ^Object %)) (iterator-seq (.iterator rw)))))

(defn- live-ref-map
  "branch -> tip hash for every refs/heads/* in the repo."
  [^Repository repo]
  (into {}
        (map (fn [^Ref r]
               [(Repository/shortenRefName (.getName r))
                (.name (.getObjectId r))]))
        (.getRefsByPrefix (.getRefDatabase repo) "refs/heads/")))

(defn repo-graph
  ([repo known] (repo-graph repo known nil))
  ([^Repository repo known {:keys [refs-override extra-tips]}]
   (let [ref-map      (or refs-override (live-ref-map repo))
         head         (some-> (.exactRef repo "HEAD") .getTarget .getName
                              Repository/shortenRefName)
         known-hashes (set (vals known))
         tips         (set (vals ref-map))
         reachable    (reachable-from repo tips)]
     (with-open [rw (RevWalk. repo)]
       (.sort rw RevSort/TOPO true)
       (.sort rw RevSort/COMMIT_TIME_DESC true)
       (mark-starts rw (into tips extra-tips))
       {:commits (into []
                       (map (fn [c]
                              {:id      (.name c)
                               :short   (subs (.name c) 0 7)
                               :message (.getShortMessage c)
                               :author  (.getName (.getAuthorIdent c))
                               :time    (.getCommitTime c)
                               :seeded  (contains? known-hashes (.name c))
                               :ghost   (not (contains? reachable (.name c)))
                               :parents (mapv #(.name %) (.getParents c))}))
                       (iterator-seq (.iterator rw)))
        :refs    (mapv (fn [[n target]] {:name n :target target}) ref-map)
        :head    head}))))

(defn- tree-objects
  "Fold the tree `tid` and everything beneath it into acc (sha -> type)."
  [^Repository repo acc ^ObjectId tid]
  (if (contains? acc (.name tid))
    acc
    (with-open [tw (doto (TreeWalk. repo) (.setRecursive false))]
      (.addTree tw tid)
      (loop [acc (assoc acc (.name tid) "tree")]
        (if (.next tw)
          (let [oid (.getObjectId tw 0)]
            (recur (if (.isSubtree tw)
                     (tree-objects repo acc oid)
                     (assoc acc (.name oid) "blob"))))
          acc)))))

(defn objects-at
  "Everything in the object database reachable from the given state — the
  live refs or a snapshot override, plus extra tips (a rejected push's
  objects are *in the store* from the moment they were transmitted, which
  is the lesson). Returns [{:sha :otype}] sorted by sha, so the learner
  sees the database the way git stores it: a flat, content-addressed
  key space, not a hierarchy."
  [^Repository repo {:keys [refs-override extra-tips]}]
  (let [ref-map (or refs-override (live-ref-map repo))
        tips    (into (set (vals ref-map)) extra-tips)]
    (with-open [rw (RevWalk. repo)]
      (mark-starts rw tips)
      (let [commits (vec (iterator-seq (.iterator rw)))
            acc     (reduce (fn [acc ^RevCommit c] (assoc acc (.name c) "commit"))
                            {} commits)
            acc     (reduce (fn [acc ^RevCommit c] (tree-objects repo acc (.getTree c)))
                            acc commits)]
        (mapv (fn [[sha otype]] {:sha sha :otype otype}) (sort-by key acc))))))

(defn- ident-info [^PersonIdent p]
  {:name  (.getName p)
   :email (.getEmailAddress p)
   :when  (str (.toInstant (.getWhen p)))})

(defn object-info
  "A cat-file-like view of one object in the repo, by full sha. Returns a
  map with :type \"commit\"|\"tree\"|\"blob\", or nil if the sha doesn't
  name an object here. Unreachable objects resolve too — that's the point."
  [^Repository repo ^String sha]
  (try
    (let [id     (ObjectId/fromString sha)
          loader (.open repo id)]
      (condp = (.getType loader)
        Constants/OBJ_COMMIT
        (with-open [rw (RevWalk. repo)]
          (let [c (.parseCommit rw id)]
            {:type      "commit"
             :sha       sha
             :tree      (.name (.getTree c))
             :parents   (mapv #(.name %) (.getParents c))
             :author    (ident-info (.getAuthorIdent c))
             :committer (ident-info (.getCommitterIdent c))
             :message   (.getFullMessage c)}))

        Constants/OBJ_TREE
        (with-open [tw (doto (TreeWalk. repo)
                         (.setRecursive false))]
          (.addTree tw id)
          {:type    "tree"
           :sha     sha
           :entries (loop [entries []]
                      (if (.next tw)
                        (recur (conj entries
                                     {:name  (.getNameString tw)
                                      :mode  (format "%06o" (.getBits (.getFileMode tw 0)))
                                      :otype (if (.isSubtree tw) "tree" "blob")
                                      :sha   (.name (.getObjectId tw 0))}))
                        entries))})

        Constants/OBJ_BLOB
        (let [bytes   (.getCachedBytes loader (int 262144))
              binary? (boolean (some zero? (take 8000 bytes)))]
          {:type    "blob"
           :sha     sha
           :size    (.getSize loader)
           :binary  binary?
           :content (when-not binary? (String. ^bytes bytes "UTF-8"))})

        {:type "other" :sha sha}))
    (catch Exception _ nil)))
