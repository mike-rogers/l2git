(ns l2git.scenarios
  "The scenario catalog. Each scenario is data plus two functions:
    :setup    (fn [repo session-created-at]) -> known   — builds the origin
              repo and returns a map of known values (e.g. seeded commit
              hashes, stringified) stored in the session for evaluation.
    :evaluate (fn [repo known]) -> {:pass? bool :message str}
              — runs after every push to the scenario's origin."
  (:require [l2git.repo-gen :as rg])
  (:import [org.eclipse.jgit.lib ObjectId Repository]
           [org.eclipse.jgit.revwalk RevWalk]
           [org.eclipse.jgit.treewalk TreeWalk]
           [java.time Instant]))

(def curator ["The Curator" "curator@l2git.example"])

(defn- blob-text
  "Content of `path` in the commit's tree, or nil if absent."
  [^Repository repo ^ObjectId commit ^String path]
  (with-open [rw (RevWalk. repo)]
    (let [tree (.getTree (.parseCommit rw commit))]
      (when-let [tw (TreeWalk/forPath repo path tree)]
        (with-open [tw tw]
          (String. (.getBytes (.open repo (.getObjectId tw 0))) "UTF-8"))))))

(defn- tip
  "The ObjectId at refs/heads/<branch>, or nil."
  [^Repository repo branch]
  (some-> (.exactRef repo (str "refs/heads/" branch)) (.getObjectId)))

(def catalog
  [{:id "add-readme"
    :title "Your first push"
    :instructions
    (str "Clone this repository, create a README.md with something in it, "
         "commit it on main, and push. Leave welcome.txt alone — it was "
         "here before you.")
    :setup
    (fn [repo ^Instant created-at]
      (let [c1 (rg/commit! repo {:files   {"welcome.txt" "Welcome to l2git!\n"}
                                 :message "Set up the workshop\n"
                                 :author  curator
                                 :when    (.minusSeconds created-at 3600)})]
        {:seed-commit (ObjectId/toString c1)}))
    :evaluate
    (fn [^Repository repo known]
      (let [main (tip repo "main")]
        (if-not main
          {:pass? false :message "I don't see a main branch yet."}
          (with-open [rw (RevWalk. repo)]
            (let [tip     (.parseCommit rw main)
                  seed    (.parseCommit rw (ObjectId/fromString (:seed-commit known)))
                  seed-ok (.isMergedInto rw seed tip)
                  readme  (blob-text repo main "README.md")
                  welcome (blob-text repo main "welcome.txt")]
              (cond
                (not seed-ok)
                {:pass? false
                 :message "Your commits should build on the one that was already here — don't rewrite it away."}

                (nil? readme)
                {:pass? false
                 :message "The pushed commit doesn't contain a README.md."}

                (clojure.string/blank? readme)
                {:pass? false
                 :message "README.md is there, but it's empty — put something in it."}

                (not= welcome "Welcome to l2git!\n")
                {:pass? false
                 :message "welcome.txt was changed or removed — it should survive untouched."}

                :else
                {:pass? true
                 :message "README.md received. That's a clone, a commit, and a push — the whole loop. Well done."}))))))}])

(def by-id (into {} (map (juxt :id identity)) catalog))
