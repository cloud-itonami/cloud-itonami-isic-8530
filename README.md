# cloud-itonami-isic-8530

Open Business Blueprint for **ISIC Rev.5 8530**: Higher education.
This repository publishes a higher-education actor -- enrollment
intake, jurisdiction accreditation assessment, academic-integrity
screening, grade finalization and degree conferral -- as an OSS
business that any qualified, accredited institution can fork, deploy,
run, improve and sell.

Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
every prior actor in this fleet
([`cloud-itonami-isic-6511`](https://github.com/cloud-itonami/cloud-itonami-isic-6511),
[`6512`](https://github.com/cloud-itonami/cloud-itonami-isic-6512),
[`6621`](https://github.com/cloud-itonami/cloud-itonami-isic-6621),
[`6622`](https://github.com/cloud-itonami/cloud-itonami-isic-6622),
[`6629`](https://github.com/cloud-itonami/cloud-itonami-isic-6629),
[`6520`](https://github.com/cloud-itonami/cloud-itonami-isic-6520),
[`6530`](https://github.com/cloud-itonami/cloud-itonami-isic-6530),
[`6820`](https://github.com/cloud-itonami/cloud-itonami-isic-6820),
[`6612`](https://github.com/cloud-itonami/cloud-itonami-isic-6612),
[`6492`](https://github.com/cloud-itonami/cloud-itonami-isic-6492),
[`6920`](https://github.com/cloud-itonami/cloud-itonami-isic-6920),
[`6611`](https://github.com/cloud-itonami/cloud-itonami-isic-6611),
[`7120`](https://github.com/cloud-itonami/cloud-itonami-isic-7120),
[`8620`](https://github.com/cloud-itonami/cloud-itonami-isic-8620)) --
the first education vertical (ISIC division 85) in this fleet. Here it
is **RegistrarOps-LLM ⊣ Academic Integrity Governor**.

> **Why an actor layer at all?** An LLM is great at drafting an
> enrollment summary, normalizing intake, and checking whether a
> student's own completed-course record actually satisfies a course's
> prerequisites -- but it has **no notion of which jurisdiction's
> degree-accreditation requirements are official, no license to
> finalize a real grade or confer a real degree, and no way to know on
> its own whether an enrollment carries an undisclosed academic-
> integrity conflict**. Letting it finalize a grade or confer a degree
> directly invites fabricated jurisdiction citations, a grade finalized
> against an unsatisfied prerequisite, a degree conferred against
> insufficient credits, and an unresolved academic-integrity concern
> being quietly waved through -- and liability for whoever runs it.
> This project seals the RegistrarOps-LLM into a single node and wraps
> it with an independent **Academic Integrity Governor**, a human
> **approval workflow**, and an immutable **audit ledger**.

## Scope: what this actor does and does not do

This actor covers enrollment intake through jurisdiction accreditation
assessment, academic-integrity screening, grade finalization and
degree conferral. It does **not**, by itself, hold an accreditation to
operate an institution in any jurisdiction, and it does not claim to.
It also does **not** model a full degree-audit/curriculum-map engine
-- no corequisites, no substitution/waiver rules, no transfer-credit
equivalency mapping, no major/minor/general-education distribution
requirements (see `registrar.registry/prerequisites-satisfied?`'s and
`minimum-credits-required`'s own docstrings for the honest
simplifications these make). Whoever deploys and operates a live
instance (an accredited institution) supplies the jurisdiction-
specific accreditation, the real academic judgment and the real
student-information-system integrations, and bears that jurisdiction's
liability -- the software supplies the governed, spec-cited, audited
execution scaffold so that operator does not have to build the
compliance layer from scratch for every new market.

### Actuation

**Finalizing a real grade and conferring a real degree are never
autonomous, at any phase, by construction.** Two independent layers
enforce this (`registrar.governor`'s `:actuation/finalize-grade`/
`:actuation/confer-degree` high-stakes gate and `registrar.phase`'s
phase table, which never puts `:grade/finalize`/`:degree/confer` in
any phase's `:auto` set) -- see `registrar.phase`'s docstring and
`test/registrar/phase_test.clj`'s `grade-finalize-never-auto-at-any-
phase`/`degree-confer-never-auto-at-any-phase`. The actor may draft,
check and recommend; a human registrar/academic dean is always the one
who actually finalizes a grade or confers a degree. Like
`6512`/`6622`/`6520`/`6530`/`6820`/`6920`/`6611`, this actor has TWO
actuation events.

## The core contract

```
enrollment intake + jurisdiction facts (registrar.facts, spec-cited)
        |
        v
   ┌──────────────┐   proposal      ┌───────────────────────┐
   │ RegistrarOps-│ ─────────────▶ │ Academic Integrity          │  (independent system)
   │ LLM (sealed) │  + citations    │ Governor: spec-basis ·      │
   └──────────────┘                 │ evidence-incomplete ·        │
                             commit ◀────┼──────────▶ hold │ prerequisites-not-satisfied
                                 │             │           │ (set-CONTAINMENT, not
                           record + ledger  escalate ─▶ human   single-item membership) ·
                                             (ALWAYS for         credits-not-sufficient ·
                                              :grade/finalize /   integrity-flag-unresolved ·
                                              :degree/confer)     already-graded/-conferred
```

**The RegistrarOps-LLM never finalizes a grade or confers a degree the
Academic Integrity Governor would reject, and never does so without a
human sign-off.** Hard violations (fabricated jurisdiction
requirements; unsupported accreditation evidence; a grade finalized
against an unsatisfied prerequisite; a degree conferred against
insufficient credits; an unresolved academic-integrity flag; a double
finalization or conferral) force **hold** and *cannot* be approved
past; a clean grade/degree proposal still always routes to a human.

## Run

```bash
clojure -M:dev:run     # walk two clean lifecycles (grade finalization, degree conferral) + five HARD-hold cases through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance · facts coverage
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a lab-safety monitoring robot
supports physical supervision in practical/lab courses, under the
actor, gated by the independent **Academic Integrity Governor**. The
governor never dispatches hardware itself; `:high`/`:safety-critical`
actions require human sign-off.

## Open business

This repository is not only source code. It is a public, forkable
business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, Academic Integrity Governor, grade-finalization + degree-conferral draft records, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an
open business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`8530`). Like `6920`/`7120`/`8620`, this vertical's academic/enrollment
records are practice-specific rather than a shared cross-operator data
contract, so `registrar.*` runs on the generic identity/forms/dmn/bpmn/
audit-ledger stack only -- no bespoke domain capability lib to
reference at all.

## Layout

| File | Role |
|---|---|
| `src/registrar/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + separate grade-finalization/degree-conferral history. No dynamically-filed sub-record -- both actuation ops act directly on a pre-seeded enrollment, and the double-finalization/double-conferral guards check dedicated `:grade-finalized?`/`:degree-conferred?` booleans rather than a `:status` value |
| `src/registrar/registry.cljc` | Grade-finalization + degree-conferral draft records, plus `prerequisites-satisfied?` -- the FIRST check in this fleet to be a SET-CONTAINMENT/subset test (does every required prerequisite appear in the completed-course set) rather than a single-item set-membership/conflict test -- and `credits-sufficient?`, reusing the MINIMUM-threshold shape for a further domain |
| `src/registrar/facts.cljc` | Per-jurisdiction degree-accreditation catalog with an official spec-basis citation per entry, honest coverage reporting |
| `src/registrar/registraropsllm.cljc` | **RegistrarOps-LLM Advisor** -- `mock-advisor` ‖ `llm-advisor`; intake/assessment/integrity-screening/grade-finalization/degree-conferral proposals |
| `src/registrar/governor.cljc` | **Academic Integrity Governor** -- 5 HARD checks (spec-basis · evidence-incomplete · prerequisites-not-satisfied, pure ground-truth subset recompute · credits-not-sufficient, pure ground-truth minimum-threshold recompute · integrity-flag-unresolved, unconditional evaluation) + already-graded/already-conferred guards + 1 soft (confidence/actuation gate) |
| `src/registrar/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted assess → supervised (grade/degree actuation always human; enrollment intake is the ONLY auto-eligible op, no direct academic risk) |
| `src/registrar/operation.cljc` | **OperationActor** -- langgraph-clj StateGraph |
| `src/registrar/sim.cljc` | demo driver |
| `test/registrar/*_test.clj` | governor contract · phase invariants · store parity · registry conformance · facts coverage |

## Business-process coverage (honest)

This actor covers enrollment intake through jurisdiction accreditation
assessment, academic-integrity screening, grade finalization and
degree conferral -- the core governed lifecycle this blueprint's own
`docs/business-model.md` names as its Offer:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Enrollment intake + per-jurisdiction degree-accreditation checklisting, HARD-gated on an official spec-basis citation (`:enrollment/intake`/`:jurisdiction/assess`) | A full degree-audit/curriculum-map engine (corequisites, substitution/waiver rules, transfer-credit equivalency, major/minor/general-education distribution requirements -- see `prerequisites-satisfied?`'s and `minimum-credits-required`'s docstrings) |
| Academic-integrity screening, evaluated unconditionally so the screening op itself can HARD-hold on its own finding (`:integrity/screen`) | Real student-information-system integration, financial-aid/tuition reporting |
| Grade finalization, HARD-gated on every required prerequisite appearing in the student's completed-course set and a double-finalization guard (`:grade/finalize`) | Ongoing academic-advising/degree-progress-monitoring itself |
| Degree conferral, HARD-gated on credits-earned satisfying the minimum credit threshold and a double-conferral guard (`:degree/confer`) | |
| Immutable audit ledger for every intake/assessment/screening/finalization/conferral decision | |

Extending coverage is additive: add the next gate (e.g. a
transfer-credit-equivalency check) as its own governed op with its own
HARD checks and tests, following the SAME "an independent governor
re-verifies against the actor's own records before any real-world act"
pattern this repo's flagship op already establishes.

## Jurisdiction coverage (honest)

`registrar.facts/coverage` reports how many requested jurisdictions
actually have an official spec-basis in `registrar.facts/catalog` --
currently 4 seeded (JPN, USA, GBR, DEU) out of ~194 jurisdictions
worldwide. This is a starting catalog to prove the governor contract
end-to-end, not a claim of global coverage. Adding a jurisdiction is
additive: one map entry in `registrar.facts/catalog`, citing a real
official source -- never fabricate a jurisdiction's requirements to make
coverage look bigger.

## Maturity

`:implemented` -- `RegistrarOps-LLM` + `Academic Integrity Governor`
run as real, tested code (see `Run` above), promoted from the
originally-published `:blueprint`-tier scaffold, modeled closely on
the fourteen prior actors' architecture. See `docs/adr/0001-
architecture.md` for the history and design.

## License

Code and implementation templates are AGPL-3.0-or-later.
