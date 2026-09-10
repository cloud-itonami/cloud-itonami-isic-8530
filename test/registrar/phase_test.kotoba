(ns registrar.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:grade/finalize`/`:degree/confer` must NEVER be a
  member of any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [registrar.phase :as phase]))

(deftest grade-finalize-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a real grade finalization"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :grade/finalize))
          (str "phase " n " must not auto-commit :grade/finalize")))))

(deftest degree-confer-never-auto-at-any-phase
  (testing "structural invariant: no phase auto-confers a real degree"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :degree/confer))
          (str "phase " n " must not auto-commit :degree/confer")))))

(deftest integrity-screen-never-auto-at-any-phase
  (testing "screening carries no direct academic risk, but is still never auto-eligible, matching every sibling KYC/conflict/independence/surveillance/calibration/credential screen"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :integrity/screen))
          (str "phase " n " must not auto-commit :integrity/screen")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-capital-risk-ops
  (testing ":enrollment/intake carries no direct academic risk -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:enrollment/intake} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :enrollment/intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :grade/finalize} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :degree/confer} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :enrollment/intake} :commit)))))
