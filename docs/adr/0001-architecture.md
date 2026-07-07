# ADR-0001: cloud-itonami-isic-8530 -- RegistrarOps-LLM as a contained intelligence node

- Status: Accepted (2026-07-07)
- Related: `cloud-itonami-isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/
  `6530`/`6820`/`6612`/`6492`/`6920`/`6611`/`7120`/`8620` ADR-0001s
  (the pattern this ADR ports); ADR-2607071250/ADR-2607071320/
  ADR-2607071351/ADR-2607071618/ADR-2607071640/ADR-2607071654
  (`6612`/`6492`/`6920`/`6611`/`7120`/`8620`, the six verticals built
  outside ADR-2607032000's original insurance/real-estate batch -- this
  is the seventh)
- Context: Continuing the standing "pick a new ISIC blueprint vertical"
  direction past `8620`, this ADR deepens `cloud-itonami-isic-8530`
  (higher education) from `:blueprint` to `:implemented`, the fifteenth
  actor in this fleet -- the FIRST education vertical (ISIC division
  85), continuing the deliberate diversification beyond finance/
  insurance, professional/technical services and healthcare.

## Problem

A higher-education institution's academic-record workflow bundles
several distinct concerns under one governed workflow:

1. **Jurisdiction degree-accreditation correctness** -- is the required
   evidence for finalizing a grade or conferring a degree based on an
   official accreditation authority (MEXT/ED-CHEA/OfS/
   Akkreditierungsrat), or invented?
2. **Prerequisite completion** -- has a student completed every course
   prerequisite before a subsequent enrollment's grade can be
   finalized? Unlike `clinic.governor/contraindicated-violations` (a
   single-item set-MEMBERSHIP/conflict test -- does one value appear
   in a forbidden set), this is a SET-CONTAINMENT/subset test -- does
   EVERY value in a required set appear in a satisfied set. A
   universal, not existential, set quantification -- a genuinely new
   shape for this fleet's set-based checks.
3. **Credit sufficiency** -- does a student's own credits-earned
   satisfy the credits required for degree conferral? Reuses
   `marketadmin.governor/listing-standard-not-met-violations`'s
   MINIMUM-threshold pure-ground-truth-recompute shape for a further
   domain.
4. **Academic integrity** -- does an enrollment carry an undisclosed
   academic-integrity conflict (plagiarism, exam misconduct)? The
   education-specific reuse of the unconditional-evaluation screening
   discipline this fleet's `casualty.governor/sanctions-violations`
   originally established -- a FIFTH distinct grounding (after
   sanctions, market surveillance, instrument calibration, clinician
   credential).
5. **Real actuation, twice** -- finalizing a real grade and conferring
   a real degree are both irreversible acts that become permanent parts
   of a student's academic record.

An LLM has no authority or grounding for any of these. The design
problem is therefore not "run a higher-education institution with an
LLM" but "seal the LLM inside a trust boundary and layer evidence-
sufficiency, prerequisite-completion verification, credit-sufficiency
verification, academic-integrity screening, audit and human-approval
on top of it, while structurally fixing both real actuation events as
human-only."

## Decision

### 1. RegistrarOps-LLM is sealed into the bottom node; it never finalizes/confers directly

`registrar.registraropsllm` returns exactly five kinds of proposal:
intake normalization, jurisdiction accreditation checklist, academic-
integrity screening, grade-finalization draft, and degree-conferral
draft. No proposal writes the SSoT or commits a real grade
finalization/degree conferral directly.

### 2. OperationActor = langgraph-clj StateGraph, 1 run = 1 higher-education operation

`registrar.operation/build` is the SAME StateGraph shape as every
sibling actor's operation namespace, copied verbatim.

### 3. `prerequisites-satisfied?` is the FIRST set-containment/subset check in this fleet

`registrar.registry/prerequisites-satisfied?` requires `(set/subset?
required-prerequisites completed-courses)` -- a UNIVERSAL set
quantification (every required item must be present), generalizing
`clinic.registry/treatment-contraindicated?`'s EXISTENTIAL set-
membership/conflict test (does one item appear in a forbidden set) to
a containment/superset relationship between two sets. `prerequisites-
not-satisfied-violations` reuses the SAME pure-ground-truth-recompute
shape (no proposal inspection or stored-verdict lookup needed at all,
since its inputs -- `:required-prerequisites`/`:completed-courses` --
are permanent facts already on the enrollment) established by
`credit.governor`'s/`accounting.governor`'s/`marketadmin.governor`'s/
`testlab.governor`'s/`clinic.governor`'s checks, extending the
family's set-based branch a further step.

### 4. `credits-sufficient?` reuses the MINIMUM-threshold shape for a further domain

`credits-not-sufficient-violations` reuses `marketadmin.governor/
listing-standard-not-met-violations`'s MINIMUM-threshold pure-ground-
truth-recompute shape (credits-earned must not fall below `registrar.
registry/minimum-credits-required`) for the higher-education domain --
a straightforward, deliberate reuse rather than a new shape, since the
underlying real-world concept (a graduation credit-hour floor) is
genuinely the same shape as a minimum listing standard.

### 5. Academic-integrity screening reuses the unconditional-evaluation discipline for a fifth distinct grounding

`integrity-flag-unresolved-violations` reuses `casualty.governor/
sanctions-violations`'s fix (evaluated unconditionally, not scoped to
a specific op, so the screening op itself can HARD-hold on its own
finding) for BOTH `:grade/finalize` and `:degree/confer` -- the SAME
shape `marketadmin.governor/surveillance-flag-unresolved-violations`/
`testlab.governor/calibration-not-current-violations`/`clinic.
governor/credential-not-current-violations` establish for their own
domains (party-screening, market-surveillance, instrument-calibration,
clinician-credential, and now academic-integrity -- the fifth distinct
application of this exact discipline).

### 6. Dual actuation events, on the SAME entity

`registrar.governor`'s `high-stakes` set has two members
(`:actuation/finalize-grade` and `:actuation/confer-degree`), matching
`6512`'s/`6622`'s/`6520`'s/`6530`'s/`6820`'s/`6920`'s/`6611`'s dual-
actuation shape -- this domain genuinely has two distinct real-world
academic acts, both operating on the same enrollment entity (mirroring
`marketadmin.store`'s dual admission/halt-lift history design, rather
than `accounting.store`'s dual issue-opinion/submit-filing across two
DIFFERENT engagement-type tags).

### 7. Double-finalization/double-conferral guards check dedicated boolean facts, not `:status` -- deliberately sidestepping `6492`'s lifecycle trap

`already-graded-violations`/`already-conferred-violations` check
`:grade-finalized?`/`:degree-conferred?`, dedicated booleans set once
and never cleared, rather than a `:status` value that could
legitimately advance past a checked state (the exact trap `cloud-
itonami-isic-6492`'s ADR-0001 documents in detail, explicitly avoided
BY DESIGN in `6920`'s, `6611`'s, `7120`'s and `8620`'s equivalent
guards). This actor's `:status` never needs to encode "has this
actuation already happened" at all, so there is no analogous status-
lifecycle risk to fall into here -- a deliberate architectural choice
informed directly by the lesson from four prior builds, applied here
for a fifth consecutive time.

### 8. No fabricated international grade/degree-number standard

Same discipline as every sibling's registry: there is no single
international check-digit standard for a grade-finalization or degree-
conferral reference number. `registrar.registry` therefore does not
invent one; it validates required fields and assigns a jurisdiction-
scoped sequence number only.

### 9. No bespoke capability lib

Like `6920`/`7120`/`8620`, and unlike most other actors in this fleet
(each referencing its own `kotoba-lang/*` capability lib), this
vertical's academic/enrollment records are practice-specific rather
than a shared cross-operator data contract -- `registrar.*` runs on the
generic identity/forms/dmn/bpmn/audit-ledger stack only, per the
blueprint's own explicit statement.

### 10. No bug this time

Like `7120`/`8620` (and unlike `6492`'s status-lifecycle bug or
`6920`'s NullPointerException), this build's test suite, lint, and
demo-ledger verification all passed clean on the first run -- the
dedicated-boolean guard design (Decision 7) and the pure-ground-truth-
recompute shapes (Decisions 3-4) were both DELIBERATELY informed by
prior builds' lessons before writing any code. The demo (`clojure
-M:dev:run`) was still independently verified against the printed
audit ledger -- basis tags `:no-spec-basis` · `:prerequisites-not-
satisfied` · `:credits-not-sufficient` · `:integrity-flag-unresolved` ·
`:already-graded` · `:already-conferred` all appear exactly where the
sim script intends, and the grade/degree histories each contain
exactly one drafted record after their respective double-actuation
attempts are held -- the same discipline that caught every real bug in
this fleet so far, applied here and finding nothing to fix.

## Consequences

- (+) Higher education gets the same governed, auditable-actor
  treatment as the fourteen prior actors, extending the pattern to a
  genuinely different domain (education, ISIC division 85) for the
  first time.
- (+) The actuation invariant (governor + phase, two layers) is
  regression-tested by `test/registrar/phase_test.clj`'s `grade-
  finalize-never-auto-at-any-phase`/`degree-confer-never-auto-at-any-
  phase`.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/registrar/
  store_contract_test.clj`, the same `:db-api`-driven swap pattern
  every sibling actor uses.
- (+) `prerequisites-satisfied?`/`prerequisites-not-satisfied-
  violations` is a genuine new check shape for this fleet (set-
  containment/subset, a universal quantification, generalizing
  `clinic.governor`'s existential set-membership check), regression-
  tested by `test/registrar/governor_contract_test.clj`'s
  `prerequisites-not-satisfied-is-held`.
- (+) The dedicated-boolean double-actuation-guard lesson (from
  `6492`'s bug) has now been applied correctly BY DESIGN across a
  FIFTH consecutive build (`6920`, `6611`, `7120`, `8620`, `8530`),
  each explicitly citing the prior lesson rather than re-deriving it by
  shape-analogy.
- (+) Both the demo and the full test suite passed clean on the first
  run -- no bug this time, unlike `6492`/`6920`.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `registrar.facts/
  coverage` reports this honestly rather than claiming broader
  coverage.
- (-) `prerequisites-satisfied?`/`credits-sufficient?` model only a
  literal course-code containment check and a total-credit-hour floor,
  not a full degree-audit/curriculum-map engine (corequisites,
  substitution/waiver rules, transfer-credit equivalency, major/minor/
  general-education distribution requirements are out of scope -- see
  those fns' own docstrings); real student-information-system
  integration and ongoing academic-advising/degree-progress-monitoring
  are all out of scope for this OSS actor -- each operator's
  responsibility (see README's coverage table).
- 39 tests / 182 assertions, lint clean.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/ADR-2607071640/ADR-2607071654 | ❌ | All six of those ADRs' titles and scopes are explicitly `cloud-itonami-isic-6612`/`6492`/`6920`/`6611`/`7120`/`8620`; mixing a different ISIC division (85, vs. those six's 64/66/69/71/86) into any would blur scope boundaries |
| Keep `cloud-itonami-isic-8530` at `:blueprint` only | ❌ | The standing direction continues past `8620`; higher education is a natural, well-precedented next domain, continuing the deliberate diversification into education, a division this fleet had not yet touched |
| Model the dual actuation as two ops across two DIFFERENT entity-type tags (mirroring `accounting.store`'s audit/tax-filing engagement-type split) rather than two ops on the SAME entity | ❌ | Grade finalization and degree conferral are both acts on the SAME student enrollment record (not two structurally different engagement kinds) -- `marketadmin.store`'s dual admission/halt-lift-on-one-listing design is the closer structural fit |
| Model a full degree-audit/curriculum-map engine for conformance-test rigor | ❌ | Genuinely more complex real-world academic-records logic that this R0 does not claim to model correctly -- honestly scoped to literal prerequisite-course-code containment and a total-credit-hour floor instead, same as every sibling's "starting catalog, not exhaustive" posture |
| Reference a capability lib (e.g. a hypothetical `kotoba-lang/education`) for consistency with most prior actors | ❌ | The blueprint itself explicitly states this vertical's records are practice-specific, not a shared cross-operator contract -- inventing a capability lib reference where the blueprint says none exists would misrepresent the domain, the same reasoning `6920`'s/`7120`'s/`8620`'s ADRs already established |
