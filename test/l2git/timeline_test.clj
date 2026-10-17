(ns l2git.timeline-test
  "The timeline is pure replay over the event log — these tests pin its
  semantics: which events become points, how ghost candidates accumulate,
  reset truncation, and ?at= state resolution."
  (:require [clojure.test :refer [deftest is testing]]
            [l2git.timeline :as timeline]))

(def ^:private events
  [{:type :fetch :scenario "s" :at "2026-10-17T10:00:00Z" :refs {"main" "aaa"}}
   {:type :teammate :scenario "s" :at "2026-10-17T10:01:00Z"
    :message "Pat pushed" :refs {"main" "bbb"}}
   {:type :push :scenario "s" :at "2026-10-17T10:01:01Z" :refs {"main" "bbb"}
    :commands [{:ref "main" :old "aaa" :new "ccc" :result "REJECTED_OTHER_REASON"}]}
   {:type :step :scenario "s" :at "2026-10-17T10:01:01Z" :refs {"main" "bbb"}
    :message "coaching, not remote history"}
   {:type :fetch :scenario "s" :at "2026-10-17T10:02:00Z" :refs {"main" "bbb"}}
   {:type :push :scenario "other" :at "2026-10-17T10:02:30Z" :refs {"main" "zzz"}
    :commands [{:ref "main" :old "x" :new "zzz" :result "OK"}]}
   {:type :push :scenario "s" :at "2026-10-17T10:03:00Z" :refs {"main" "ddd"}
    :commands [{:ref "main" :old "bbb" :new "ddd" :result "OK"}]}])

(deftest points-tell-the-story
  (let [pts (timeline/points events "s")]
    (is (= ["you cloned" "Pat pushed" "your push was rejected" "you fetched" "you pushed"]
           (mapv :label pts)))
    (is (= ["⇣" "⚡" "✗" "⇣" "⇡"] (mapv :icon pts)))
    (testing "coaching events and other scenarios produce no points"
      (is (= 5 (count pts))))))

(deftest ghosts-accumulate
  (let [pts (timeline/points events "s")]
    (testing "a rejected push's attempted commit becomes a ghost candidate"
      (is (some #{"ccc"} (:ghosts (nth pts 2)))))
    (testing "every snapshot tip is offered (force-push clobber coverage)"
      (is (= ["aaa" "bbb" "ccc" "ddd"] (:ghosts (peek pts)))))
    (testing "candidates only grow"
      (is (every? (fn [[a b]] (<= (count (:ghosts a)) (count (:ghosts b))))
                  (partition 2 1 pts))))))

(deftest reset-truncates
  (let [evs (conj events
                  {:type :reset :scenario "s" :at "2026-10-17T11:00:00Z"
                   :message "reset"}
                  {:type :fetch :scenario "s" :at "2026-10-17T11:01:00Z"
                   :refs {"main" "eee"}})
        pts (timeline/points evs "s")]
    (is (= 1 (count pts)) "pre-reset snapshots name discarded objects")
    (is (= "you cloned" (:label (first pts))) "the story restarts")
    (is (= ["eee"] (:ghosts (first pts))))))

(deftest client-points-shape
  (let [pts (timeline/client-points events "s")]
    (is (= [0 1 2 3 4 nil] (mapv :idx pts)))
    (testing "the trailing live point"
      (let [live (peek pts)]
        (is (:live live))
        (is (nil? (:at live)))))
    (testing "refs and ghosts stay server-side"
      (is (not-any? :refs pts))
      (is (not-any? :ghosts pts)))))

(deftest state-at-resolves
  (testing "a point index freezes refs and carries its ghosts"
    (let [{:keys [refs-override extra-tips]} (timeline/state-at events "s" 2)]
      (is (= {"main" "bbb"} refs-override))
      (is (some #{"ccc"} extra-tips))))
  (testing "nil means live: current refs, all accumulated candidates"
    (let [{:keys [refs-override extra-tips]} (timeline/state-at events "s" nil)]
      (is (nil? refs-override))
      (is (= ["aaa" "bbb" "ccc" "ddd"] extra-tips))))
  (testing "out of range falls back to live"
    (is (nil? (:refs-override (timeline/state-at events "s" 99)))))
  (testing "no events at all"
    (is (= {:extra-tips nil} (timeline/state-at [] "s" nil)))))
