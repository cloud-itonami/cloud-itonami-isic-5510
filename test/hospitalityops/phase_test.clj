(ns hospitalityops.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:stay/check-in`/`:stay/check-out` must NEVER be a
  member of any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [hospitalityops.phase :as phase]))

(deftest stay-check-in-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a real check-in"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :stay/check-in))
          (str "phase " n " must not auto-commit :stay/check-in")))))

(deftest stay-check-out-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a real check-out"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :stay/check-out))
          (str "phase " n " must not auto-commit :stay/check-out")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-guest-facing-risk-ops
  (testing ":stay/intake carries no direct guest-facing risk -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:stay/intake} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :stay/intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :stay/check-in} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :stay/check-out} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :stay/intake} :commit)))))
