(ns registrar.registry
  "Pure-function grade-finalization + degree-conferral record
  construction -- an append-only academic book-of-record draft.

  Like every sibling actor's registry, there is no single international
  check-digit standard for a grade-finalization or degree-conferral
  reference number -- every institution/jurisdiction assigns its own
  reference format. This namespace does NOT invent one; it builds a
  jurisdiction-scoped sequence number and validates the record's
  required fields, the same honest, non-fabricating discipline
  `registrar.facts` uses.

  `prerequisites-satisfied?` is a REAL, foundational academic-integrity
  concept (a student must have completed every prerequisite course
  before a subsequent enrollment's grade can be finalized) -- see its
  own docstring for the honest simplification it makes vs. a full
  degree-audit/curriculum-map engine. Unlike `clinic.registry/
  treatment-contraindicated?` (a SINGLE-item set-MEMBERSHIP/conflict
  test -- does one value appear in a forbidden set), this is a SET-
  CONTAINMENT/subset test -- does EVERY value in a required set appear
  in a satisfied set. This generalizes this fleet's set-based checks
  from an existential ('is this one thing a member of that set?') to a
  universal ('is every member of this set also a member of that
  set?') quantification -- the FIRST check in this fleet to be a
  subset/superset comparison.

  `credits-sufficient?`/`minimum-credits-required` reuse `marketadmin.
  registry/listing-standard-met?`'s MINIMUM-threshold pure-ground-
  truth-recompute shape for a further domain (credits earned must not
  fall below the credits required for degree conferral).

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real student-information system. It builds the RECORD an
  institution would keep, not the act of finalizing a grade or
  conferring a degree itself (that is `registrar.operation`'s `:grade/
  finalize`/`:degree/confer`, always human-gated -- see README
  `Actuation`)."
  (:require [clojure.string :as str]
            [clojure.set :as set]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is the
  registrar's/academic-dean's act, not this actor's. See README
  `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn prerequisites-satisfied?
  "Does `enrollment`'s own `:completed-courses` set contain EVERY course
  in its own `:required-prerequisites` set? A pure ground-truth check
  against the enrollment's own permanent fields -- see ns docstring for
  the honest simplification this makes vs. a full degree-audit/
  curriculum-map engine (this R0 does not model corequisites,
  substitution/waiver rules, or transfer-credit equivalency mapping --
  only whether the literal required-prerequisite course codes are all
  present in the completed-course set)."
  [{:keys [required-prerequisites completed-courses]}]
  (set/subset? (set required-prerequisites) (set completed-courses)))

(def minimum-credits-required
  "A single representative credit-hour threshold for degree conferral
  -- see ns docstring for the honest simplification this makes vs. a
  full degree-audit engine (no major/minor/general-education
  distribution requirements modeled, only a total credit-hour floor)."
  124)

(defn credits-sufficient?
  "Does `enrollment`'s own `:credits-earned` satisfy `minimum-credits-
  required`? Reuses `marketadmin.registry/listing-standard-met?`'s
  MINIMUM-threshold pure-ground-truth-recompute shape for the higher-
  education domain."
  [{:keys [credits-earned]}]
  (and (number? credits-earned) (>= credits-earned minimum-credits-required)))

(defn register-grade-finalization
  "Validate + construct the GRADE-FINALIZATION registration DRAFT --
  the institution's own legal act of finalizing a real course grade.
  Pure function -- does not touch any real student-information system;
  it builds the RECORD an institution would keep. `registrar.governor`
  independently re-verifies the enrollment's own prerequisite
  completion, and blocks a double-finalization of the same enrollment,
  before this is ever allowed to commit."
  [enrollment-id jurisdiction sequence]
  (when-not (and enrollment-id (not= enrollment-id ""))
    (throw (ex-info "grade-finalization: enrollment_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "grade-finalization: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "grade-finalization: sequence must be >= 0" {})))
  (let [grade-number (str (str/upper-case jurisdiction) "-GRD-" (zero-pad sequence 6))
        record {"record_id" grade-number
                "kind" "grade-finalization-draft"
                "enrollment_id" enrollment-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "grade_number" grade-number
     "certificate" (unsigned-certificate "GradeFinalization" grade-number grade-number)}))

(defn register-degree-conferral
  "Validate + construct the DEGREE-CONFERRAL registration DRAFT -- the
  institution's own legal act of conferring a real degree. Pure
  function -- does not touch any real student-information system; it
  builds the RECORD an institution would keep. `registrar.governor`
  independently re-verifies the enrollment's own credits-earned against
  `minimum-credits-required`, and blocks a double-conferral of the same
  enrollment, before this is ever allowed to commit."
  [enrollment-id jurisdiction sequence]
  (when-not (and enrollment-id (not= enrollment-id ""))
    (throw (ex-info "degree-conferral: enrollment_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "degree-conferral: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "degree-conferral: sequence must be >= 0" {})))
  (let [degree-number (str (str/upper-case jurisdiction) "-DEG-" (zero-pad sequence 6))
        record {"record_id" degree-number
                "kind" "degree-conferral-draft"
                "enrollment_id" enrollment-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "degree_number" degree-number
     "certificate" (unsigned-certificate "DegreeConferral" degree-number degree-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
