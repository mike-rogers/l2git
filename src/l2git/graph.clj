(ns l2git.graph
  "Serializes a repo's commit DAG for the live visualization: every commit
  reachable from any branch, topo-ordered newest-first, plus refs and HEAD.
  Seeded (curator-made) commits are flagged so the UI can distinguish 'was
  already here' from 'you made this'."
  (:import [org.eclipse.jgit.lib Repository Ref]
           [org.eclipse.jgit.revwalk RevWalk RevSort]))

(defn repo-graph
  [^Repository repo known]
  (let [refs         (.getRefsByPrefix (.getRefDatabase repo) "refs/heads/")
        head         (.exactRef repo "HEAD")
        known-hashes (set (vals known))]
    (with-open [rw (RevWalk. repo)]
      (.sort rw RevSort/TOPO true)
      (.sort rw RevSort/COMMIT_TIME_DESC true)
      (doseq [^Ref r refs]
        (.markStart rw (.parseCommit rw (.getObjectId r))))
      {:commits (into []
                      (map (fn [c]
                             {:id      (.name c)
                              :short   (subs (.name c) 0 7)
                              :message (.getShortMessage c)
                              :author  (.getName (.getAuthorIdent c))
                              :time    (.getCommitTime c)
                              :seeded  (contains? known-hashes (.name c))
                              :parents (mapv #(.name %) (.getParents c))}))
                      (iterator-seq (.iterator rw)))
       :refs    (mapv (fn [^Ref r]
                        {:name   (Repository/shortenRefName (.getName r))
                         :target (.name (.getObjectId r))})
                      refs)
       :head    (some-> head .getTarget .getName Repository/shortenRefName)})))
