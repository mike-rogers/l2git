(ns l2git.scenarios-test
  "Two layers of safety for the content seam:
  - validation: every shipped EDN file passes, and planted authoring
    mistakes are rejected at load with a named form (not in a learner's
    terminal mid-push);
  - walkthroughs: a simulated learner (direct commits into the in-memory
    origin — repos are deterministic pure functions, so this is cheap)
    completes every scenario, and exploit-shaped detours are refused.
  The step index threads between evaluations exactly as session/advance!
  persists it."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [l2git.repo-gen :as rg]
            [l2git.scenarios :as scenarios])
  (:import [java.time Instant]
           [org.eclipse.jgit.lib ObjectId Repository]))

(def learner ["Learner" "learner@example.com"])

(defn- now [] (Instant/now))

(defn- fresh
  "Materialize a scenario's origin the way session/repo-for! does."
  [id]
  (let [sc    (scenarios/by-id id)
        repo  (rg/new-repo (str "test/" id))
        known ((:setup sc) repo (now))]
    {:sc sc :repo repo :known known}))

(defn- ff!
  "Slide `branch`'s label to `to-branch`'s tip — a fast-forward merge."
  [^Repository repo branch to-branch]
  (let [t (.getObjectId (.exactRef repo (str "refs/heads/" to-branch)))]
    (doto (.updateRef repo (str "refs/heads/" branch))
      (.setNewObjectId t)
      (.forceUpdate))))

;; ------------------------------------------------------ catalog validation

(deftest catalog-loads-and-validates
  ;; requiring the ns already ran validation over every EDN file at load;
  ;; assert the catalog is non-trivially sane so a silent [] can't pass
  (is (= 4 (count scenarios/catalog)))
  (is (every? (comp seq :steps) scenarios/catalog))
  (doseq [file (edn/read-string (slurp (io/resource "scenarios/index.edn")))]
    (let [raw (edn/read-string (slurp (io/resource (str "scenarios/" file))))]
      (is (= raw (scenarios/validate-scenario raw))
          (str file " validates")))))

(deftest validation-rejects-authoring-mistakes
  (let [base {:id    "t" :title "t" :level :basic
              :setup {:commits [{:name :seed :author :curator
                                 :when [:hours-before 1]
                                 :message "m\n" :files {"a.txt" "b\n"}}]}
              :steps [{:pass "ok" :checks [{:cond [:known :seed] :fail "no"}]}]}]
    (is (= base (scenarios/validate-scenario base)))
    (testing "typo'd condition op"
      (is (thrown-with-msg?
           Exception #"unknown condition op"
           (scenarios/validate-scenario
            (assoc-in base [:steps 0 :checks 0 :cond] [:file-exist "main" "x"])))))
    (testing "condition naming an unbound commit"
      (is (thrown-with-msg?
           Exception #"unbound commit"
           (scenarios/validate-scenario
            (assoc-in base [:steps 0 :checks 0 :cond] [:reachable :nope "main"])))))
    (testing "wrong arity"
      (is (thrown-with-msg?
           Exception #"takes"
           (scenarios/validate-scenario
            (assoc-in base [:steps 0 :checks 0 :cond] [:file-equals "main" "x"])))))
    (testing "unknown author"
      (is (thrown-with-msg?
           Exception #"unknown :author"
           (scenarios/validate-scenario
            (assoc-in base [:setup :commits 0 :author] :gene)))))
    (testing ":parent naming an unbound commit"
      (is (thrown-with-msg?
           Exception #"unbound commit"
           (scenarios/validate-scenario
            (update-in base [:setup :commits] conj
                       {:author :pat :when :now :message "m\n"
                        :files {} :parent :ghost})))))
    (testing "missing :fail message"
      (is (thrown-with-msg?
           Exception #":fail"
           (scenarios/validate-scenario
            (assoc-in base [:steps 0 :checks 0] {:cond [:known :seed]})))))))

;; -------------------------------------------------------- add-readme

(deftest add-readme-walkthrough
  (let [{:keys [sc repo known]} (fresh "add-readme")]
    (testing "a fresh origin doesn't pass"
      (is (not (:complete? (scenarios/evaluate-progress sc repo known 0)))))
    (testing "committing a non-blank README on main completes it"
      (rg/commit! repo {:branch "main" :author learner :when (now)
                        :message "Add README\n"
                        :files {"welcome.txt" "Welcome to l2git!\n"
                                "README.md"   "hello\n"}})
      (is (:complete? (scenarios/evaluate-progress sc repo known 0))))))

(deftest add-readme-rejects-clobbering-the-seed
  (let [{:keys [sc repo known]} (fresh "add-readme")]
    ;; "push" a README that rewrites history (no parent = orphan main)
    (rg/commit! repo {:branch "main" :author learner :when (now)
                      :message "Add README\n"
                      :parents []
                      :files {"welcome.txt" "Welcome to l2git!\n"
                              "README.md"   "hello\n"}})
    (let [r (scenarios/evaluate-progress sc repo known 0)]
      (is (not (:complete? r)))
      (is (re-find #"rewrite" (get-in r [:fail :message]))))))

;; ----------------------------------------------------- branch-basics

(deftest branch-basics-walkthrough
  (let [{:keys [sc repo known]} (fresh "branch-basics")]
    ;; step 1: branch off the seed, commit the greeting, "push" the branch
    (rg/commit! repo {:branch "feature/greeting" :author learner :when (now)
                      :message "Add greeting\n"
                      :parents [(ObjectId/fromString (:seed known))]
                      :files {"welcome.txt"  "Welcome to l2git!\n"
                              "greeting.txt" "hi there\n"}})
    (let [r (scenarios/evaluate-progress sc repo known 0)]
      (is (= 1 (:idx r)))
      (is (not (:complete? r)))
      (is (= 2 (get-in r [:fail :step]))))
    ;; step 2: fast-forward main onto the branch (what git merge does here)
    (ff! repo "main" "feature/greeting")
    (is (:complete? (scenarios/evaluate-progress sc repo known 1)))))

(deftest branch-basics-rejects-committing-straight-to-main
  (let [{:keys [sc repo known]} (fresh "branch-basics")]
    (rg/commit! repo {:branch "main" :author learner :when (now)
                      :message "Add greeting\n"
                      :files {"welcome.txt"  "Welcome to l2git!\n"
                              "greeting.txt" "hi there\n"}})
    (let [r (scenarios/evaluate-progress sc repo known 0)]
      (is (not (:complete? r)))
      (is (= 1 (get-in r [:fail :step]))))))

;; ----------------------------------------------------- teammate-pull

(def ^:private pat-files
  {"welcome.txt"  "Welcome to l2git!\n"
   "pat-notes.md" "- fix the flaky login test\n- review Sam's PR\n"})

(deftest teammate-pull-walkthrough
  (let [{:keys [sc repo known]} (fresh "teammate-pull")
        ambush! (get-in sc [:before-push :run])]
    (is (not (:complete? (scenarios/evaluate-progress sc repo known 0))))
    ;; the pre-receive ambush: Pat lands mid-push
    (let [{pat :known} (ambush! repo known (now))
          known        (merge known pat)
          r1           (scenarios/evaluate-progress sc repo known 0)]
      (is (= 1 (:idx r1)) "the collision itself completes step 1")
      (testing ":once makes the ambush idempotent (empty-push exploit family)"
        (is (nil? (ambush! repo known (now)))))
      ;; integrate: learner's work on top of Pat's, both files survive
      (rg/commit! repo {:branch "main" :author learner :when (now)
                        :message "Add standup\n"
                        :files (assoc pat-files "standup.md" "- ship the tests\n")})
      (is (:complete? (scenarios/evaluate-progress sc repo known 1))))))

(deftest teammate-pull-force-push-recovery
  ;; exploit-log regression: clobbering Pat must fail the step AND trigger
  ;; the after-evaluate hook that restores his work
  (let [{:keys [sc repo known]} (fresh "teammate-pull")
        ambush! (get-in sc [:before-push :run])
        {pat :known} (ambush! repo known (now))
        known (merge known pat)]
    ;; force-push: learner's commit replaces Pat's (parent = seed)
    (rg/commit! repo {:branch "main" :author learner :when (now)
                      :message "Add standup\n"
                      :parents [(ObjectId/fromString (:seed known))]
                      :files {"welcome.txt" "Welcome to l2git!\n"
                              "standup.md"  "- mine now\n"}})
    (let [r (scenarios/evaluate-progress sc repo known 1)]
      (is (not (:complete? r)))
      (is (re-find #"force-push" (get-in r [:fail :message])))
      (let [{restored :known event :event} ((:after-evaluate sc) repo known (now) {:pass? false})
            known (merge known restored)]
        (is (some? event) "Pat notices and restores")
        (is (not= (:pat-1 pat) (:pat-1 restored)) "restored under a new hash")
        ;; the learner pulls Pat's restore and keeps their own work
        (rg/commit! repo {:branch "main" :author learner :when (now)
                          :message "Merge Pat back\n"
                          :files (assoc pat-files "standup.md" "- mine now\n")})
        (is (:complete? (scenarios/evaluate-progress sc repo known 1)))))))

;; --------------------------------------------- rebase-before-merge

(def ^:private roadmap-files
  {"welcome.txt" "Welcome to l2git!\n"
   "roadmap.md"  "# Roadmap\n\n- Q1: login\n- Q2: billing\n"})

(def ^:private login-md
  "# Login\n\nA form with a username and a password field.\nReject empty passwords before submitting.\n")

(deftest rebase-before-merge-walkthrough
  (let [{:keys [sc repo known]} (fresh "rebase-before-merge")]
    ;; "rebase": replay the branch as NEW commits on top of Pat's tip,
    ;; then (implicitly) force-push — the originals become unreachable
    (rg/commit! repo {:branch "feature/login" :author learner :when (now)
                      :message "Add login form\n"
                      :parents [(ObjectId/fromString (:pat-2 known))]
                      :files (assoc roadmap-files "login.md" "# Login\n")})
    (rg/commit! repo {:branch "feature/login" :author learner :when (now)
                      :message "Add login validation notes\n"
                      :files (assoc roadmap-files "login.md" login-md)})
    (let [r (scenarios/evaluate-progress sc repo known 0)]
      (is (= 1 (:idx r)) "rebase verified by hash absence of the originals"))
    (ff! repo "main" "feature/login")
    (is (:complete? (scenarios/evaluate-progress sc repo known 1)))))

(deftest rebase-before-merge-rejects-merge-instead-of-rebase
  (let [{:keys [sc repo known]} (fresh "rebase-before-merge")]
    ;; merging main INTO the branch keeps the original commits reachable
    (rg/commit! repo {:branch "feature/login" :author learner :when (now)
                      :message "Merge main into feature/login\n"
                      :parents [(ObjectId/fromString (:feat-2 known))
                                (ObjectId/fromString (:pat-2 known))]
                      :files (assoc roadmap-files "login.md" login-md)})
    (let [r (scenarios/evaluate-progress sc repo known 0)]
      (is (not (:complete? r)))
      (is (= 1 (get-in r [:fail :step])))
      (is (re-find #"rebase" (get-in r [:fail :message]))))))
