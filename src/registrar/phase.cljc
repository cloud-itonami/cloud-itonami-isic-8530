(ns registrar.phase
  "Phase 0->3 staged rollout -- the higher-education analog of
  `cloud-itonami-isic-6512`'s `casualty.phase`.

    Phase 0  read-only        -- no writes, still governor-gated.
    Phase 1  assisted-intake  -- enrollment intake allowed, every write
                                 needs human approval.
    Phase 2  assisted-assess  -- adds jurisdiction assessment +
                                 integrity screening writes, still
                                 approval.
    Phase 3  supervised auto  -- governor-clean, high-confidence
                                 `:enrollment/intake` (no capital risk
                                 yet) may auto-commit. `:grade/
                                 finalize`/`:degree/confer` NEVER auto-
                                 commit, at any phase.

  `:grade/finalize`/`:degree/confer` are deliberately ABSENT from every
  phase's `:auto` set, including phase 3 -- a permanent structural
  fact, not a rollout milestone still to come. Finalizing a real grade
  and conferring a real degree are the two real-world legal acts this
  actor performs; both are always a human registrar's/academic dean's
  call. `registrar.governor`'s `:actuation/finalize-grade`/`:actuation/
  confer-degree` high-stakes gate enforces the same invariant
  independently -- two layers, not one, agree on this. `:integrity/
  screen` is likewise never auto-eligible, at any phase -- the same
  posture every sibling's KYC/conflict/independence/surveillance/
  calibration/credential screening op has. Like `credit.phase`/
  `accounting.phase`/`marketadmin.phase`/`testlab.phase`/`clinic.
  phase`, phase 3's `:auto` set here has only ONE member (`:enrollment/
  intake`) -- this domain has no separate no-capital-risk 'file'
  lifecycle distinct from the enrollment itself.")

(def read-ops  #{})
(def write-ops #{:enrollment/intake :jurisdiction/assess :integrity/screen
                 :grade/finalize :degree/confer})

;; NOTE the invariant: `:grade/finalize`/`:degree/confer` are members
;; of `write-ops` (governor-gated like any write) but are NEVER
;; members of any phase's `:auto` set below. Do not add them there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"       :writes #{}                                                              :auto #{}}
   1 {:label "assisted-intake" :writes #{:enrollment/intake}                                             :auto #{}}
   2 {:label "assisted-assess" :writes #{:enrollment/intake :jurisdiction/assess :integrity/screen}       :auto #{}}
   3 {:label "supervised-auto" :writes write-ops
      :auto #{:enrollment/intake}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:grade/finalize`/`:degree/confer` are never auto-eligible at any
    phase, so they always escalate once the governor clears them (or
    hold if the governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map an Academic Integrity Governor verdict to a base disposition
  before the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
