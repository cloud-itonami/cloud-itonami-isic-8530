(ns registrar.registry-test
  (:require [clojure.test :refer [deftest is]]
            [registrar.registry :as r]))

;; ----------------------------- prerequisites-satisfied? -----------------------------

(deftest prerequisites-satisfied-when-completed-is-a-superset
  (is (r/prerequisites-satisfied? {:required-prerequisites #{"CS301"} :completed-courses #{"CS301" "CS201"}}))
  (is (r/prerequisites-satisfied? {:required-prerequisites #{} :completed-courses #{}}))
  (is (r/prerequisites-satisfied? {:required-prerequisites #{"CS301" "CS201"} :completed-courses #{"CS301" "CS201"}})))

(deftest prerequisites-not-satisfied-when-a-required-course-is-missing
  (is (not (r/prerequisites-satisfied? {:required-prerequisites #{"CS301"} :completed-courses #{"CS201"}})))
  (is (not (r/prerequisites-satisfied? {:required-prerequisites #{"CS301" "CS201"} :completed-courses #{"CS301"}}))))

;; ----------------------------- credits-sufficient? -----------------------------

(deftest credits-sufficient-when-at-or-above-minimum
  (is (r/credits-sufficient? {:credits-earned r/minimum-credits-required}))
  (is (r/credits-sufficient? {:credits-earned (+ r/minimum-credits-required 1)}))
  (is (not (r/credits-sufficient? {:credits-earned (- r/minimum-credits-required 1)}))))

(deftest credits-not-sufficient-when-missing
  (is (not (r/credits-sufficient? {}))))

;; ----------------------------- register-grade-finalization -----------------------------

(deftest grade-finalization-is-a-draft-not-a-real-finalization
  (let [result (r/register-grade-finalization "enrollment-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest grade-finalization-assigns-grade-number
  (let [result (r/register-grade-finalization "enrollment-1" "JPN" 7)]
    (is (= (get result "grade_number") "JPN-GRD-000007"))
    (is (= (get-in result ["record" "enrollment_id"]) "enrollment-1"))
    (is (= (get-in result ["record" "kind"]) "grade-finalization-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest grade-finalization-validation-rules
  (is (thrown? Exception (r/register-grade-finalization "" "JPN" 0)))
  (is (thrown? Exception (r/register-grade-finalization "enrollment-1" "" 0)))
  (is (thrown? Exception (r/register-grade-finalization "enrollment-1" "JPN" -1))))

(deftest grade-history-is-append-only
  (let [g1 (r/register-grade-finalization "enrollment-1" "JPN" 0)
        hist (r/append [] g1)
        g2 (r/register-grade-finalization "enrollment-2" "JPN" 1)
        hist2 (r/append hist g2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-GRD-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-GRD-000001" (get-in hist2 [1 "record_id"])))))

;; ----------------------------- register-degree-conferral -----------------------------

(deftest degree-conferral-is-a-draft-not-a-real-conferral
  (let [result (r/register-degree-conferral "enrollment-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest degree-conferral-assigns-degree-number
  (let [result (r/register-degree-conferral "enrollment-1" "JPN" 7)]
    (is (= (get result "degree_number") "JPN-DEG-000007"))
    (is (= (get-in result ["record" "enrollment_id"]) "enrollment-1"))
    (is (= (get-in result ["record" "kind"]) "degree-conferral-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest degree-conferral-validation-rules
  (is (thrown? Exception (r/register-degree-conferral "" "JPN" 0)))
  (is (thrown? Exception (r/register-degree-conferral "enrollment-1" "" 0)))
  (is (thrown? Exception (r/register-degree-conferral "enrollment-1" "JPN" -1))))

(deftest degree-history-is-append-only
  (let [d1 (r/register-degree-conferral "enrollment-1" "JPN" 0)
        hist (r/append [] d1)
        d2 (r/register-degree-conferral "enrollment-2" "JPN" 1)
        hist2 (r/append hist d2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-DEG-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-DEG-000001" (get-in hist2 [1 "record_id"])))))
