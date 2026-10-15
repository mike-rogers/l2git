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
(def pat ["Pat" "pat@l2git.example"])

(def welcome-text "Welcome to l2git!\n")
(def pat-notes-text "- fix the flaky login test\n- review Sam's PR\n")

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

(defn- reachable?
  "Is the commit with this hash an ancestor of (or equal to) the branch tip?"
  [^Repository repo ^String hash branch]
  (boolean
   (when-let [t (tip repo branch)]
     (with-open [rw (RevWalk. repo)]
       (.isMergedInto rw
                      (.parseCommit rw (ObjectId/fromString hash))
                      (.parseCommit rw t))))))

(defn- files-at
  "Flat {path content} snapshot of a commit's tree."
  [^Repository repo ^ObjectId commit]
  (with-open [rw (RevWalk. repo)
              tw (TreeWalk. repo)]
    (.addTree tw (.getTree (.parseCommit rw commit)))
    (.setRecursive tw true)
    (loop [m {}]
      (if (.next tw)
        (recur (assoc m (.getPathString tw)
                      (String. (.getBytes (.open repo (.getObjectId tw 0))) "UTF-8")))
        m))))

(def catalog
  [{:id "add-readme"
    :title "Your first push"
    :level :basic
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
                 :message "README.md received. That's a clone, a commit, and a push — the whole loop. Well done."}))))))}

   {:id "teammate-pull"
    :title "A teammate pushed first"
    :level :basic
    :instructions
    (str "Clone this repository — but you're not alone in it. Your teammate "
         "Pat is about to push a commit. Create standup.md with a line about "
         "what you're working on, commit it on main, and push. If git refuses "
         "your push, that's the lesson: the remote moved while you worked. "
         "Fetch Pat's commit and integrate it (git pull, or fetch then "
         "merge/rebase), then push again. Whatever you do, don't force-push "
         "over Pat.")
    :setup
    (fn [repo ^Instant created-at]
      (let [c1 (rg/commit! repo {:files   {"welcome.txt" welcome-text}
                                 :message "Set up the project\n"
                                 :author  curator
                                 :when    (.minusSeconds created-at 7200)})]
        {:seed-commit (ObjectId/toString c1)}))
    :after-fetch
    (fn [repo known]
      ;; Pat strikes the moment the learner first fetches. Idempotent: only
      ;; once per session.
      (when-not (:teammate-commit known)
        (let [c (rg/commit! repo {:files   {"welcome.txt"  welcome-text
                                            "pat-notes.md" pat-notes-text}
                                  :message "Add my sprint notes\n"
                                  :author  pat
                                  :when    (Instant/now)})]
          {:teammate-commit (ObjectId/toString c)})))
    :after-evaluate
    ;; If the learner force-pushed Pat's commit out of main, it's no longer
    ;; reachable from any ref — a plain fetch can't rescue it, so the lesson
    ;; would dead-end. Instead, Pat notices and pushes his notes again on
    ;; top of whatever the learner left, and the lesson resumes.
    (fn [repo known {:keys [pass?]}]
      (when (and (not pass?)
                 (:teammate-commit known)
                 (tip repo "main")
                 (not (reachable? repo (:teammate-commit known) "main")))
        (let [files (assoc (files-at repo (tip repo "main"))
                           "pat-notes.md" pat-notes-text)
              c     (rg/commit! repo {:files   files
                                      :message "Restore my sprint notes\n"
                                      :author  pat
                                      :when    (Instant/now)})]
          {:known {:teammate-commit (ObjectId/toString c)}
           :event {:type    :teammate
                   :message "Pat noticed his commit vanished and pushed his notes again — pull before continuing"}})))
    :evaluate
    (fn [^Repository repo known]
      (let [main (tip repo "main")]
        (if-not main
          {:pass? false :message "I don't see a main branch yet."}
          (with-open [rw (RevWalk. repo)]
            (let [tip-c   (.parseCommit rw main)
                  reach?  (fn [hash]
                            (and hash
                                 (.isMergedInto rw (.parseCommit rw (ObjectId/fromString hash)) tip-c)))
                  standup (blob-text repo main "standup.md")
                  pat-md  (blob-text repo main "pat-notes.md")]
              (cond
                (not (reach? (:teammate-commit known)))
                {:pass? false
                 :message "Pat's commit is missing from main — did you force-push over it? That's exactly how teammates lose work. Pat is pushing his notes again: pull, then push without force."}

                (nil? standup)
                {:pass? false
                 :message "Pat's work made it, but I don't see your standup.md yet."}

                (clojure.string/blank? standup)
                {:pass? false
                 :message "standup.md is there but empty — write a line about what you're working on."}

                (nil? pat-md)
                {:pass? false
                 :message "Pat's file (pat-notes.md) went missing during your integration. Merge or rebase again, keeping Pat's changes."}

                :else
                {:pass? true
                 :message "Both your commit and Pat's live on main. You just survived your first diverged history — merge or rebase, either was right."}))))))}])

(def by-id (into {} (map (juxt :id identity)) catalog))
