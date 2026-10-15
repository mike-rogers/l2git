(ns l2git.repo-gen
  "Builds scenario repositories in memory. A repo is a pure function of
  (scenario, session) — nothing touches disk, and commit timestamps are
  computed relative to the session's creation time so repos always look
  fresh, whether it's 2026 or 2035."
  (:import [org.eclipse.jgit.internal.storage.dfs DfsRepositoryDescription InMemoryRepository]
           [org.eclipse.jgit.lib CommitBuilder Constants FileMode ObjectId
            ObjectInserter PersonIdent Repository TreeFormatter]
           [java.time Instant ZoneId]))

(def ^ZoneId tz (ZoneId/of "UTC"))

(defn new-repo
  "A fresh, empty, bare in-memory repository with HEAD pointing at main."
  ^InMemoryRepository [^String name]
  (let [repo (InMemoryRepository. (DfsRepositoryDescription. name))]
    (-> (.updateRef repo Constants/HEAD)
        (.link "refs/heads/main"))
    repo))

(defn- insert-tree
  "Insert a flat tree of {path content} entries; returns the tree ObjectId.
  Git requires canonical (sorted) entry order."
  [^ObjectInserter ins files]
  (let [tf (TreeFormatter.)]
    (doseq [[^String path ^String content] (sort-by key files)]
      (let [blob-id (.insert ins Constants/OBJ_BLOB (.getBytes content "UTF-8"))]
        (.append tf path FileMode/REGULAR_FILE blob-id)))
    (.insert ins tf)))

(defn commit!
  "Create a commit in `repo` and point `branch` at it. Returns the commit's
  ObjectId. Options:
    :files   {path content} — full tree snapshot (flat, v1)
    :message commit message
    :author  [name email]
    :when    java.time.Instant for author+committer time
    :parents seq of ObjectId (default: current tip of branch, if any)
    :branch  branch name without refs/heads/ prefix (default \"main\")"
  ^ObjectId [^Repository repo {:keys [files message author parents branch]
                               at    :when
                               :or   {branch "main"}}]
  (with-open [ins (.newObjectInserter repo)]
    (let [ref-name  (str "refs/heads/" branch)
          parents   (or parents
                        (when-let [r (.exactRef repo ref-name)]
                          [(.getObjectId r)]))
          tree-id   (insert-tree ins files)
          [a-name a-email] author
          ident     (PersonIdent. ^String a-name ^String a-email ^Instant at tz)
          cb        (doto (CommitBuilder.)
                      (.setTreeId tree-id)
                      (.setAuthor ident)
                      (.setCommitter ident)
                      (.setMessage message))]
      (when (seq parents)
        (.setParentIds cb ^java.util.List (vec parents)))
      (let [commit-id (.insert ins cb)]
        (.flush ins)
        (doto (.updateRef repo ref-name)
          (.setNewObjectId commit-id)
          (.forceUpdate))
        commit-id))))
