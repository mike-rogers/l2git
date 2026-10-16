(ns l2git.scenarios
  "The scenario DSL. Scenarios live as EDN under resources/scenarios/ (see
  index.edn for catalog order) and are compiled at load time into runtime
  hooks:

    :setup          (fn [repo created-at]) -> known
    :steps          [{:id :title :instructions :hints :pass :check}] where
                    :check is (fn [repo known]) -> nil | failure-message.
                    EDN may give :steps directly, or :evaluate
                    {:checks :pass} as single-step sugar. Progress runs
                    through `evaluate-progress`, which advances monotonically
                    and cascades (one interaction may complete several steps).
    :after-fetch    (fn [repo known created-at]) -> {:known {} :event str} | nil
    :before-push    {:branch str :run (same shape)}; fires mid-push, and only
                    when the push actually targets :branch (default main) —
                    pushes to other branches can't summon the teammate. The
                    injected commit makes the targeted ref update fail its
                    lock check, so the colliding push is rejected
    :after-evaluate (fn [repo known created-at result]) -> same | nil

  `known` maps commit names (:seed, :pat-1, …) to hash strings, bound when
  the DSL creates commits and consulted by conditions. Timestamps in :setup
  are relative to the session's creation (`[:hours-before 2]`), so repos are
  deterministic per session and always look fresh; hooks use `:now`.

  Conditions are tagged vectors:
    [:and c…] [:or c…] [:not c]      logic
    [:failed]                        after-evaluate only: the push failed
    [:known name]                    the named commit exists
    [:branch-exists branch]
    [:reachable name branch]         named commit is ancestor-or-equal of tip
    [:tip-is name branch]            branch tip IS the named commit (untouched)
    [:branch-merged src dst]         src's tip is ancestor-or-equal of dst's tip
    [:file-exists branch path]
    [:file-nonblank branch path]
    [:file-equals branch path text]

  File sets are a literal map, [:files-at branch] (tip snapshot), or
  [:merge f…]."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [l2git.repo-gen :as rg])
  (:import [org.eclipse.jgit.lib ObjectId Repository]
           [org.eclipse.jgit.revwalk RevWalk]
           [org.eclipse.jgit.treewalk TreeWalk]
           [java.time Instant]))

(def people
  {:curator ["The Curator" "curator@l2git.example"]
   :pat     ["Pat" "pat@l2git.example"]
   :you     ["Past You" "you@l2git.example"]})

;; ------------------------------------------------------- repo inspection

(defn- tip ^ObjectId [^Repository repo branch]
  (some-> (.exactRef repo (str "refs/heads/" branch)) (.getObjectId)))

(defn- blob-text
  "Content of `path` in the commit's tree, or nil if absent."
  [^Repository repo ^ObjectId commit ^String path]
  (with-open [rw (RevWalk. repo)]
    (let [tree (.getTree (.parseCommit rw commit))]
      (when-let [tw (TreeWalk/forPath repo path tree)]
        (with-open [tw tw]
          (String. (.getBytes (.open repo (.getObjectId tw 0))) "UTF-8"))))))

(defn- branch-file [repo branch path]
  (when-let [t (tip repo branch)]
    (blob-text repo t path)))

(defn- reachable? [^Repository repo ^String hash branch]
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

;; --------------------------------------------------------- interpreters

(defn- resolve-when ^Instant [spec ^Instant created-at]
  (cond
    (= spec :now) (Instant/now)
    (vector? spec)
    (let [[op n] spec]
      (case op
        :hours-before   (.minusSeconds created-at (long (* n 3600)))
        :minutes-before (.minusSeconds created-at (long (* n 60)))
        (throw (ex-info "unknown :when op" {:spec spec}))))
    :else (throw (ex-info "unknown :when spec" {:spec spec}))))

(defn- resolve-files [spec repo]
  (cond
    (map? spec) spec
    (vector? spec)
    (case (first spec)
      :files-at (if-let [t (tip repo (second spec))] (files-at repo t) {})
      :merge    (apply merge (map #(resolve-files % repo) (rest spec)))
      (throw (ex-info "unknown :files op" {:spec spec})))
    :else (throw (ex-info "unknown :files spec" {:spec spec}))))

(defn- apply-commits!
  "Create the spec'd commits; returns {name hash-string} for the named ones."
  [repo created-at commits]
  (reduce (fn [known spec]
            (let [c (rg/commit! repo {:branch  (:branch spec "main")
                                      :message (:message spec)
                                      :author  (or (people (:author spec))
                                                   (throw (ex-info "unknown author" {:spec spec})))
                                      :when    (resolve-when (:when spec) created-at)
                                      :files   (resolve-files (:files spec) repo)
                                      ;; :parent names an earlier commit, for
                                      ;; starting a branch off it while its
                                      ;; own branch advances
                                      :parents (when-let [p (:parent spec)]
                                                 [(ObjectId/fromString
                                                   (or (get known p)
                                                       (throw (ex-info "unknown :parent" {:spec spec}))))])})]
              (if-let [n (:name spec)]
                (assoc known n (ObjectId/toString c))
                known)))
          {}
          commits))

(defn- check
  "Evaluate a condition form against the repo. `ctx` carries hook-specific
  facts (currently :result for after-evaluate)."
  [repo known ctx form]
  (let [ev #(check repo known ctx %)]
    (case (first form)
      :and           (every? ev (rest form))
      :or            (boolean (some ev (rest form)))
      :not           (not (ev (second form)))
      :failed        (false? (get-in ctx [:result :pass?]))
      :known         (contains? known (second form))
      :branch-exists (some? (tip repo (second form)))
      :reachable     (let [[_ n branch] form]
                       (boolean (when-let [h (get known n)]
                                  (reachable? repo h branch))))
      :tip-is        (let [[_ n branch] form]
                       (boolean (when-let [h (get known n)]
                                  (= h (some-> (tip repo branch) (ObjectId/toString))))))
      :branch-merged (let [[_ src dst] form]
                       (boolean
                        (when-let [s (tip repo src)]
                          (when-let [d (tip repo dst)]
                            (with-open [rw (RevWalk. repo)]
                              (.isMergedInto rw (.parseCommit rw s) (.parseCommit rw d)))))))
      :file-exists   (let [[_ branch path] form]
                       (some? (branch-file repo branch path)))
      :file-nonblank (let [[_ branch path] form]
                       (let [s (branch-file repo branch path)]
                         (and (some? s) (not (str/blank? s)))))
      :file-equals   (let [[_ branch path text] form]
                       (= text (branch-file repo branch path)))
      (throw (ex-info "unknown condition op" {:form form})))))

;; ------------------------------------------------------------ compilers

(defn- compile-setup [{:keys [commits]}]
  (fn [repo created-at]
    (apply-commits! repo created-at commits)))

(defn- compile-step [i step]
  {:id           (or (:id step) (keyword (str "step-" (inc i))))
   :title        (:title step)
   :instructions (:instructions step)
   :hints        (vec (:hints step))
   :pass         (:pass step)
   :check        (let [checks (:checks step)]
                   (fn [repo known]
                     (some (fn [{c :cond f :fail}]
                             (when-not (check repo known {} c) f))
                           checks)))})

(defn- normalize-steps [m]
  (or (:steps m)
      [(:evaluate m)]))

(defn evaluate-progress
  "Run the step engine from `step-idx` against current repo state. Steps
  advance monotonically and cascade — one interaction may complete several.
  Returns {:idx new-idx :total n :passed [{:title :message}] :complete? bool
  :fail {:step 1-based :title :message} | nil}."
  [scenario repo known step-idx]
  (let [steps (:steps scenario)
        total (count steps)]
    (loop [i step-idx, passed []]
      (if (>= i total)
        {:idx i :total total :passed passed :complete? true}
        (let [step (nth steps i)]
          (if-let [fail-msg ((:check step) repo known)]
            {:idx i :total total :passed passed :complete? false
             :fail {:step (inc i) :title (:title step) :message fail-msg}}
            (recur (inc i)
                   (conj passed {:title (:title step) :message (:pass step)}))))))))

(defn- compile-puppeteer [{:keys [once commits event]}]
  (fn [repo known created-at]
    (when-not (and once (contains? known once))
      {:known (apply-commits! repo created-at commits)
       :event event})))

(defn- compile-after-evaluate [{condition :when :keys [commits event]}]
  (fn [repo known created-at result]
    (when (check repo known {:result result} condition)
      {:known (apply-commits! repo created-at commits)
       :event event})))

(defn- compile-scenario [m]
  (-> m
      (assoc :setup (compile-setup (:setup m))
             :steps (vec (map-indexed compile-step (normalize-steps m))))
      (dissoc :evaluate)
      (cond->
        (:after-fetch m)    (assoc :after-fetch (compile-puppeteer (:after-fetch m)))
        (:before-push m)    (assoc :before-push {:branch (get-in m [:before-push :branch] "main")
                                                 :run    (compile-puppeteer (:before-push m))})
        (:after-evaluate m) (assoc :after-evaluate (compile-after-evaluate (:after-evaluate m))))))

;; -------------------------------------------------------------- catalog

(defn- read-resource [path]
  (if-let [r (io/resource path)]
    (edn/read-string (slurp r))
    (throw (ex-info "missing scenario resource" {:path path}))))

(def catalog
  (mapv #(compile-scenario (read-resource (str "scenarios/" %)))
        (read-resource "scenarios/index.edn")))

(def by-id (into {} (map (juxt :id identity)) catalog))
