(ns registrar.governor
  "Academic Integrity Governor -- the independent compliance layer that
  earns the RegistrarOps-LLM the right to commit. The LLM has no
  notion of jurisdictional degree-accreditation law, whether a
  student's own completed-course record actually satisfies a course's
  prerequisites, whether a student's own credits-earned actually
  satisfies the credits required for degree conferral, whether an
  enrollment carries an undisclosed academic-integrity conflict, or
  when an act stops being a draft and becomes a real-world grade
  finalization or degree conferral, so this MUST be a separate system
  able to *reject* a proposal and fall back to HOLD -- the higher-
  education analog of `cloud-itonami-isic-6512`'s CasualtyGovernor.

  Six checks, in priority order, ALL HARD violations: a human approver
  CANNOT override them (you don't get to approve your way past a
  fabricated jurisdiction spec-basis, incomplete accreditation
  evidence, a grade finalized against an unsatisfied prerequisite, a
  degree conferred against insufficient credits, an unresolved
  academic-integrity flag, or a double finalization/conferral). The
  confidence/actuation gate is SOFT: it asks a human to look (low
  confidence / actuation), and the human may approve -- but see
  `registrar.phase`: for `:stake :actuation/finalize-grade`/
  `:actuation/confer-degree` (a real grade finalization or degree
  conferral) NO phase ever allows auto-commit either. Two independent
  layers agree that actuation is always a human call.

    1. Spec-basis                  -- did the jurisdiction proposal cite
                                       an OFFICIAL source (`registrar.
                                       facts`), or invent one? Like
                                       `credit.governor`'s/`marketadmin.
                                       governor`'s/`testlab.governor`'s/
                                       `clinic.governor`'s actuation
                                       ops, `:grade/finalize`/`:degree/
                                       confer` act directly on a pre-
                                       seeded enrollment (see
                                       `registrar.store`'s own
                                       docstring) -- there is no
                                       'enrollment is missing' failure
                                       mode to guard against here.
    2. Evidence incomplete         -- for `:grade/finalize`/`:degree/
                                       confer`, has the jurisdiction
                                       actually been assessed with a
                                       full accreditation evidence
                                       checklist on file?
    3. Prerequisites not satisfied -- for `:grade/finalize`,
                                       INDEPENDENTLY recompute whether
                                       the enrollment's own `:completed-
                                       courses` set contains EVERY
                                       course in its own `:required-
                                       prerequisites` set
                                       (`registrar.registry/
                                       prerequisites-satisfied?`) --
                                       needs no proposal inspection or
                                       stored-verdict lookup at all,
                                       the SAME pure-ground-truth-
                                       recompute shape `credit.
                                       governor`'s/`accounting.
                                       governor`'s/`marketadmin.
                                       governor`'s/`testlab.governor`'s/
                                       `clinic.governor`'s checks
                                       establish -- but the FIRST check
                                       in this fleet to be a SET-
                                       CONTAINMENT/subset test (does
                                       EVERY required item appear in
                                       the satisfied set) rather than
                                       `clinic.governor/contraindicated-
                                       violations`'s single-item set-
                                       MEMBERSHIP/conflict test.
    4. Credits not sufficient      -- for `:degree/confer`,
                                       INDEPENDENTLY recompute whether
                                       the enrollment's own `:credits-
                                       earned` satisfies `registrar.
                                       registry/minimum-credits-
                                       required` (`registrar.registry/
                                       credits-sufficient?`) -- reuses
                                       `marketadmin.governor/listing-
                                       standard-not-met-violations`'s
                                       MINIMUM-threshold pure-ground-
                                       truth-recompute shape for a
                                       further domain.
    5. Integrity flag unresolved   -- reported by THIS proposal itself
                                       (a `:integrity/screen` that just
                                       found one), or already on file
                                       for the enrollment (`:integrity/
                                       screen`/`:grade/finalize`/
                                       `:degree/confer`). Evaluated
                                       UNCONDITIONALLY (not scoped to a
                                       specific op), the SAME discipline
                                       `casualty.governor/sanctions-
                                       violations`/`marketadmin.
                                       governor/surveillance-flag-
                                       unresolved-violations`/`testlab.
                                       governor/calibration-not-current-
                                       violations`/`clinic.governor/
                                       credential-not-current-
                                       violations` established -- an
                                       unresolved academic-integrity
                                       flag blocks BOTH real-world acts
                                       this actor performs, even if the
                                       screening op itself never
                                       (re)ran in this session.
    6. Confidence floor / actuation
       gate                          -- LLM confidence below threshold,
                                       OR the op is `:grade/finalize`/
                                       `:degree/confer` (REAL academic
                                       acts) -> escalate.

  Two more guards, double-finalization/double-conferral prevention, are
  enforced but NOT listed as numbered HARD checks above because they
  need no upstream comparison at all -- `already-graded-violations`/
  `already-conferred-violations` refuse to finalize/confer the SAME
  enrollment twice, off dedicated `:grade-finalized?`/`:degree-
  conferred?` facts (never a `:status` value) -- the SAME 'check a
  dedicated boolean, not status' discipline `accounting.governor`'s/
  `marketadmin.governor`'s/`testlab.governor`'s/`clinic.governor`'s
  guards establish, informed by `cloud-itonami-isic-6492`'s status-
  lifecycle bug (ADR-2607071320)."
  (:require [registrar.facts :as facts]
            [registrar.registry :as registry]
            [registrar.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Finalizing a real grade and conferring a real degree are the two
  real-world actuation events this actor performs -- a two-member set,
  matching `cloud-itonami-isic-6512`'s/`6622`'s/`6520`'s/`6530`'s/
  `6820`'s/`6920`'s/`6611`'s dual-actuation shape."
  #{:actuation/finalize-grade :actuation/confer-degree})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:jurisdiction/assess` (or `:grade/finalize`/`:degree/confer`)
  proposal with no spec-basis citation is a HARD violation -- never
  invent a jurisdiction's degree-accreditation requirements."
  [{:keys [op]} proposal]
  (when (contains? #{:jurisdiction/assess :grade/finalize :degree/confer} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:grade/finalize`/`:degree/confer`, the jurisdiction's required
  student-enrollment/transcript/prerequisite-verification/
  accreditation evidence must actually be satisfied -- do not trust
  the advisor's self-reported confidence alone."
  [{:keys [op subject]} st]
  (when (contains? #{:grade/finalize :degree/confer} op)
    (let [e (store/enrollment st subject)
          assessment (store/assessment-of st subject)]
      (when-not (and assessment
                     (facts/required-evidence-satisfied?
                      (:jurisdiction e) (:checklist assessment)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(学生在籍記録/成績証明書引用/認可証明書等)が充足していない状態での確定提案"}]))))

(defn- prerequisites-not-satisfied-violations
  "For `:grade/finalize`, INDEPENDENTLY recompute whether the
  enrollment's own completed-course set satisfies its own required-
  prerequisite set via `registrar.registry/prerequisites-satisfied?`
  -- needs no proposal inspection or stored-verdict lookup at all,
  since its inputs are permanent ground-truth fields already on the
  enrollment. The FIRST check in this fleet to be a set-containment/
  subset test rather than a single-item set-membership/conflict test."
  [{:keys [op subject]} st]
  (when (= op :grade/finalize)
    (let [e (store/enrollment st subject)]
      (when-not (registry/prerequisites-satisfied? e)
        [{:rule :prerequisites-not-satisfied
          :detail (str subject " の履修済科目" (:completed-courses e)
                      "が前提条件" (:required-prerequisites e) "を満たしていない")}]))))

(defn- credits-not-sufficient-violations
  "For `:degree/confer`, INDEPENDENTLY recompute whether the
  enrollment's own credits-earned satisfies `registrar.registry/
  minimum-credits-required` via `registrar.registry/credits-
  sufficient?` -- needs no proposal inspection or stored-verdict
  lookup at all, reusing `marketadmin.governor/listing-standard-not-
  met-violations`'s MINIMUM-threshold shape for a further domain."
  [{:keys [op subject]} st]
  (when (= op :degree/confer)
    (let [e (store/enrollment st subject)]
      (when-not (registry/credits-sufficient? e)
        [{:rule :credits-not-sufficient
          :detail (str subject " の取得単位数(" (:credits-earned e)
                      ")が卒業要件(" registry/minimum-credits-required ")を下回っている")}]))))

(defn- integrity-flag-unresolved-violations
  "An unresolved academic-integrity flag -- reported by THIS proposal
  (e.g. an `:integrity/screen` that itself just found one), or already
  on file in the store for the enrollment (`:integrity/screen`/
  `:grade/finalize`/`:degree/confer`) -- is a HARD, un-overridable
  hold. Evaluated UNCONDITIONALLY (not scoped to a specific op) so the
  screening op itself can HARD-hold on its own finding."
  [{:keys [op subject]} proposal st]
  (let [hit-in-proposal? (= :open (get-in proposal [:value :verdict]))
        enrollment-id (when (contains? #{:integrity/screen :grade/finalize :degree/confer} op) subject)
        hit-on-file? (and enrollment-id (= :open (:verdict (store/integrity-of st enrollment-id))))]
    (when (or hit-in-proposal? hit-on-file?)
      [{:rule :integrity-flag-unresolved
        :detail "未解決の学業インテグリティ違反フラグのある履修記録は進められない"}])))

(defn- already-graded-violations
  "For `:grade/finalize`, refuses to finalize a grade for the SAME
  enrollment twice, off a dedicated `:grade-finalized?` fact (never a
  `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :grade/finalize)
    (when (store/enrollment-already-graded? st subject)
      [{:rule :already-graded
        :detail (str subject " は既に成績確定済み")}])))

(defn- already-conferred-violations
  "For `:degree/confer`, refuses to confer a degree for the SAME
  enrollment twice, off a dedicated `:degree-conferred?` fact (never a
  `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :degree/confer)
    (when (store/enrollment-already-conferred? st subject)
      [{:rule :already-conferred
        :detail (str subject " は既に学位授与済み")}])))

(defn check
  "Censors a RegistrarOps-LLM proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (prerequisites-not-satisfied-violations request st)
                           (credits-not-sufficient-violations request st)
                           (integrity-flag-unresolved-violations request proposal st)
                           (already-graded-violations request st)
                           (already-conferred-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
