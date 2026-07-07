(ns registrar.governor-contract-test
  "The governor contract as executable tests -- the higher-education
  analog of `cloud-itonami-isic-6512`'s `casualty.governor-contract-
  test`. The single invariant under test:

    RegistrarOps-LLM never finalizes a grade or confers a degree the
    Academic Integrity Governor would reject, `:grade/finalize`/
    `:degree/confer` NEVER auto-commit at any phase, `:enrollment/
    intake` (no direct academic risk) MAY auto-commit when clean, and
    every decision (commit OR hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [registrar.store :as store]
            [registrar.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :registrar :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- assess!
  "Walks `subject` through assess -> approve, leaving an assessment on
  file. Uses distinct thread-ids per call site by suffixing
  `tid-prefix`."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-assess") {:op :jurisdiction/assess :subject subject} operator)
  (approve! actor (str tid-prefix "-assess")))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :enrollment/intake :subject "enrollment-1"
                   :patch {:id "enrollment-1" :student "Sakura Tanaka"}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "Sakura Tanaka" (:student (store/enrollment db "enrollment-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest jurisdiction-assess-always-needs-approval
  (testing "assess is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :jurisdiction/assess :subject "enrollment-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/assessment-of db "enrollment-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a jurisdiction/assess proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :jurisdiction/assess :subject "enrollment-1" :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/assessment-of db "enrollment-1")) "no assessment written"))))

(deftest grade-finalize-without-assessment-is-held
  (testing "grade/finalize before any jurisdiction assessment -> HOLD (evidence incomplete)"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :grade/finalize :subject "enrollment-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:evidence-incomplete} (-> (store/ledger db) first :basis))))))

(deftest prerequisites-not-satisfied-is-held
  (testing "a grade/finalize attempt against an unsatisfied course prerequisite -> HOLD"
    (let [[db actor] (fresh)
          _ (assess! actor "t5pre" "enrollment-3")
          res (exec-op actor "t5" {:op :grade/finalize :subject "enrollment-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:prerequisites-not-satisfied} (-> (store/ledger db) last :basis)))
      (is (empty? (store/grade-history db))))))

(deftest credits-not-sufficient-is-held
  (testing "a degree/confer attempt against insufficient credits -> HOLD"
    (let [[db actor] (fresh)
          _ (assess! actor "t6pre" "enrollment-4")
          res (exec-op actor "t6" {:op :degree/confer :subject "enrollment-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:credits-not-sufficient} (-> (store/ledger db) last :basis)))
      (is (empty? (store/degree-history db))))))

(deftest integrity-flag-is-held-and-unoverridable
  (testing "an unresolved academic-integrity flag on an enrollment -> HOLD, and never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t7" {:op :integrity/screen :subject "enrollment-5"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:integrity-flag-unresolved} (-> (store/ledger db) first :basis)))
      (is (nil? (store/integrity-of db "enrollment-5")) "no clearance written"))))

(deftest grade-finalize-always-escalates-then-human-decides
  (testing "a clean, fully-assessed, prerequisite-satisfied enrollment still ALWAYS interrupts for human approval -- actuation/finalize-grade is never auto"
    (let [[db actor] (fresh)
          _ (assess! actor "t8pre" "enrollment-1")
          r1 (exec-op actor "t8" {:op :grade/finalize :subject "enrollment-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, grade record drafted"
        (let [r2 (approve! actor "t8")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:grade-finalized? (store/enrollment db "enrollment-1"))))
          (is (= 1 (count (store/grade-history db))) "one draft grade record"))))))

(deftest degree-confer-always-escalates-then-human-decides
  (testing "a clean, fully-assessed, sufficient-credits enrollment still ALWAYS interrupts for human approval -- actuation/confer-degree is never auto"
    (let [[db actor] (fresh)
          _ (assess! actor "t9pre" "enrollment-1")
          r1 (exec-op actor "t9" {:op :degree/confer :subject "enrollment-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, degree record drafted"
        (let [r2 (approve! actor "t9")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:degree-conferred? (store/enrollment db "enrollment-1"))))
          (is (= 1 (count (store/degree-history db))) "one draft degree record"))))))

(deftest grade-finalize-double-finalization-is-held
  (testing "finalizing the same enrollment's grade twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (assess! actor "t10pre" "enrollment-1")
          _ (exec-op actor "t10a" {:op :grade/finalize :subject "enrollment-1"} operator)
          _ (approve! actor "t10a")
          res (exec-op actor "t10" {:op :grade/finalize :subject "enrollment-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-graded} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/grade-history db))) "still only the one earlier finalization"))))

(deftest degree-confer-double-conferral-is-held
  (testing "conferring the same enrollment's degree twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (assess! actor "t11pre" "enrollment-1")
          _ (exec-op actor "t11a" {:op :degree/confer :subject "enrollment-1"} operator)
          _ (approve! actor "t11a")
          res (exec-op actor "t11" {:op :degree/confer :subject "enrollment-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-conferred} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/degree-history db))) "still only the one earlier conferral"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :enrollment/intake :subject "enrollment-1"
                          :patch {:id "enrollment-1" :student "Sakura Tanaka"}} operator)
      (exec-op actor "b" {:op :jurisdiction/assess :subject "enrollment-1" :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
