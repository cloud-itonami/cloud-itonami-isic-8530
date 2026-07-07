(ns registrar.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a configuration
  change, not a rewrite -- see `cloud-itonami-isic-6511`'s
  `underwriting.store-contract-test` for the same pattern on the sibling
  actor."
  (:require [clojure.test :refer [deftest is testing]]
            [registrar.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "Sakura Tanaka" (:student (store/enrollment s "enrollment-1"))))
      (is (= "JPN" (:jurisdiction (store/enrollment s "enrollment-1"))))
      (is (= #{"CS301"} (:required-prerequisites (store/enrollment s "enrollment-1"))))
      (is (= #{"CS301" "CS201"} (:completed-courses (store/enrollment s "enrollment-1"))))
      (is (= 130 (:credits-earned (store/enrollment s "enrollment-1"))))
      (is (false? (:academic-integrity-flag? (store/enrollment s "enrollment-1"))))
      (is (true? (:academic-integrity-flag? (store/enrollment s "enrollment-5"))))
      (is (false? (:grade-finalized? (store/enrollment s "enrollment-1"))))
      (is (false? (:degree-conferred? (store/enrollment s "enrollment-1"))))
      (is (= ["enrollment-1" "enrollment-2" "enrollment-3" "enrollment-4" "enrollment-5"]
             (mapv :id (store/all-enrollments s))))
      (is (nil? (store/integrity-of s "enrollment-1")))
      (is (nil? (store/assessment-of s "enrollment-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/grade-history s)))
      (is (= [] (store/degree-history s)))
      (is (zero? (store/next-grade-sequence s "JPN")))
      (is (zero? (store/next-degree-sequence s "JPN")))
      (is (false? (store/enrollment-already-graded? s "enrollment-1")))
      (is (false? (store/enrollment-already-conferred? s "enrollment-1"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :enrollment/upsert
                                 :value {:id "enrollment-1" :student "Sakura Tanaka"}})
        (is (= "Sakura Tanaka" (:student (store/enrollment s "enrollment-1"))))
        (is (= 130 (:credits-earned (store/enrollment s "enrollment-1"))) "credits-earned preserved"))
      (testing "assessment / integrity payloads commit and read back"
        (store/commit-record! s {:effect :assessment/set :path ["enrollment-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/assessment-of s "enrollment-1")))
        (store/commit-record! s {:effect :integrity/set :path ["enrollment-1"]
                                 :payload {:enrollment-id "enrollment-1" :verdict :clear}})
        (is (= {:enrollment-id "enrollment-1" :verdict :clear} (store/integrity-of s "enrollment-1"))))
      (testing "grade finalization drafts a grade record and advances the grade sequence"
        (store/commit-record! s {:effect :enrollment/mark-graded :path ["enrollment-1"]})
        (is (= "JPN-GRD-000000" (get (first (store/grade-history s)) "record_id")))
        (is (= "grade-finalization-draft" (get (first (store/grade-history s)) "kind")))
        (is (true? (:grade-finalized? (store/enrollment s "enrollment-1"))))
        (is (= 1 (count (store/grade-history s))))
        (is (= 1 (store/next-grade-sequence s "JPN")))
        (is (true? (store/enrollment-already-graded? s "enrollment-1")))
        (is (false? (store/enrollment-already-graded? s "enrollment-2"))))
      (testing "degree conferral drafts a degree record and advances the degree sequence"
        (store/commit-record! s {:effect :enrollment/mark-conferred :path ["enrollment-1"]})
        (is (= "JPN-DEG-000000" (get (first (store/degree-history s)) "record_id")))
        (is (= "degree-conferral-draft" (get (first (store/degree-history s)) "kind")))
        (is (true? (:degree-conferred? (store/enrollment s "enrollment-1"))))
        (is (= 1 (count (store/degree-history s))))
        (is (= 1 (store/next-degree-sequence s "JPN")))
        (is (true? (store/enrollment-already-conferred? s "enrollment-1")))
        (is (false? (store/enrollment-already-conferred? s "enrollment-2"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/enrollment s "nope")))
    (is (= [] (store/all-enrollments s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/grade-history s)))
    (is (= [] (store/degree-history s)))
    (is (zero? (store/next-grade-sequence s "JPN")))
    (is (zero? (store/next-degree-sequence s "JPN")))
    (store/with-enrollments s {"x" {:id "x" :student "s" :course "CS401"
                                    :required-prerequisites #{} :completed-courses #{}
                                    :credits-earned 130 :academic-integrity-flag? false
                                    :grade-finalized? false :degree-conferred? false
                                    :jurisdiction "JPN" :status :intake}})
    (is (= "s" (:student (store/enrollment s "x"))))))
