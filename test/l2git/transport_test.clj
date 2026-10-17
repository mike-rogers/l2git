(ns l2git.transport-test
  "Tier 3: the end-to-end smoke. A real git CLI talks to a real Jetty on an
  ephemeral port, and the assertions read the actual sideband text a learner
  would see. This is also where the three original transport exploits live
  as regressions — clone-then-pull, empty push, side-branch push — because
  they were bugs in receive-pack's trigger logic, not in the evaluator.

  git runs with HOME pointed at an empty temp dir (GIT_CONFIG_NOSYSTEM=1),
  so the developer's real config — signing keys, hooks, pull strategy —
  can't leak in; identity comes in via -c flags, like a fresh machine."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [l2git.core :as core]
            [l2git.session :as session])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [org.eclipse.jetty.server Server ServerConnector]))

(def ^:dynamic *base* nil)
(def ^:dynamic *home* nil)

(defn- tmpdir! [prefix]
  (.toFile (Files/createTempDirectory prefix (into-array FileAttribute []))))

(defn- delete-recursively! [f]
  (run! #(.delete ^java.io.File %) (reverse (file-seq (io/file f)))))

(defn- with-server [f]
  (let [^Server server (core/start! {:port 0 :join? false})
        port           (.getLocalPort ^ServerConnector (first (.getConnectors server)))
        home           (tmpdir! "l2git-git-home")]
    (binding [*base* (str "http://localhost:" port)
              *home* home]
      (try
        (f)
        (finally
          (.stop server)
          (delete-recursively! home))))))

(use-fixtures :once with-server)

(defn- git
  "Run git in `dir` as a fresh-machine learner. Returns {:exit :out :err}."
  [dir & args]
  (apply shell/sh "git"
         "-c" "user.name=Learner"
         "-c" "user.email=learner@example.com"
         "-c" "commit.gpgsign=false"
         (concat args
                 [:dir (str dir)
                  :env {"PATH"                (System/getenv "PATH")
                        "HOME"                (str *home*)
                        "GIT_CONFIG_NOSYSTEM" "1"
                        "GIT_TERMINAL_PROMPT" "0"}])))

(defn- ok! [{:keys [exit err] :as res}]
  (is (zero? exit) err)
  res)

(defn- pat-known? [token]
  (contains? (:known (session/repo-for! token "teammate-pull")) :pat-1))

(deftest teammate-pull-over-real-git
  (let [token (:token (session/create!))
        url   (str *base* "/r/" token "/teammate-pull.git")
        dir   (tmpdir! "l2git-clone")
        work  (io/file dir "work")]
    (try
      (testing "clone over smart HTTP"
        (ok! (git dir "clone" url "work")))

      (testing "exploit regression: clone-then-pull doesn't summon Pat"
        (ok! (git work "pull"))
        (is (not (pat-known? token))))

      (testing "exploit regression: an empty push doesn't summon Pat"
        (ok! (git work "push" "origin" "main"))
        (is (not (pat-known? token))))

      (testing "exploit regression: a side-branch push doesn't summon Pat"
        (spit (io/file work "standup.md") "- real work\n")
        (ok! (git work "add" "standup.md"))
        (ok! (git work "commit" "-m" "Add standup"))
        (ok! (git work "push" "origin" "main:sneaky"))
        (is (not (pat-known? token))))

      (testing "pushing to main springs the ambush, with the authored rejection"
        (let [{:keys [exit err]} (git work "push" "origin" "main")]
          (is (pos? exit) "the colliding push must be refused")
          (is (str/includes? err "fetch first — the remote changed while you were working"))
          (is (str/includes? err "Someone pushed to this repository while you were working"))
          (is (str/includes? err "✓ Make your move")
              "the rejection itself completes step 1, loudly")
          (is (pat-known? token))))

      (testing "pull --rebase + push completes the scenario over the wire"
        (ok! (git work "pull" "--rebase" "origin" "main"))
        (let [{:keys [err] :as res} (git work "push" "origin" "main")]
          (ok! res)
          (is (str/includes? err "✓ Integrate and push again"))
          (is (str/includes? err "★ Scenario complete"))
          (is (= :complete (:status (session/repo-for! token "teammate-pull"))))))

      (finally
        (delete-recursively! dir)))))
