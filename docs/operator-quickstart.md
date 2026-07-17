# Operator Quickstart

## Who this is for

IT directors and registrars at accredited colleges, universities, and educational consortia who want to run their own enrollment intake, course assessment, grade finalization, and degree-conferral processes—with transparent governance, independent academic integrity checks, and full audit trails.

## Prerequisites

- **Clojure CLI** (1.11+): Install from [clojure.org](https://clojure.org/guides/getting_started)
- **Java** (11+): Required by Clojure
- **For offline workspace development**: The monorepo siblings `kotoba-lang/langgraph` and `kotoba-lang/langchain` are referenced via `:local/root` in `deps.edn`. A standalone fork should override these with Git coordinates (see `deps.edn` comments for details).

## Run the demo

The demo walks through two clean lifecycles (grade finalization and degree conferral) plus five HARD-hold cases:

```bash
clojure -M:dev:run
```

Output prints the complete audit ledger, showing every intake, assessment, screening, finalization, and conferral decision, with HARD-hold basis tags (`:no-spec-basis`, `:prerequisites-not-satisfied`, `:credits-not-sufficient`, `:integrity-flag-unresolved`, `:already-graded`, `:already-conferred`) exactly where intended.

## Run tests

Full test suite covers governor contract, phase invariants, store parity, registry conformance, and facts coverage:

```bash
clojure -M:dev:test
```

Run a specific test file:

```bash
clojure -M:dev:test test/registrar/governor_contract_test.clj
```

## Linting

Static analysis with clj-kondo (errors fail CI):

```bash
clojure -M:lint
```

## Academic Integrity Governor

The independent governor that gates all high-stakes operations (grade finalization, degree conferral) lives at:

```
src/registrar/governor.cljc
```

### Key entry points

- **`registrar.governor/high-stakes`** – Set of high-stakes operations (`:actuation/finalize-grade`, `:actuation/confer-degree`) that require human sign-off
- **`registrar.governor/violations`** – Evaluates all five HARD checks:
  - `:spec-basis` – Jurisdiction accreditation citation is official
  - `:evidence-incomplete` – Required accreditation evidence is provided
  - `:prerequisites-not-satisfied` – All required prerequisites appear in completed courses (set-containment check, a universal quantification)
  - `:credits-not-sufficient` – Total credits-earned meet the minimum threshold
  - `:integrity-flag-unresolved` – Academic-integrity conflicts are resolved
- **Double-finalization/double-conferral guards** – Check `:grade-finalized?` and `:degree-conferred?` dedicated booleans; never use `:status` values

### How it works

1. RegistrarOps-LLM proposes an operation (intake, assessment, screening, grade finalization, or degree conferral)
2. Academic Integrity Governor independently re-verifies all HARD checks against the enrollment facts
3. Soft check (confidence gate) also applies
4. **Hard violations** (fabricated jurisdiction, incomplete evidence, unsatisfied prerequisites, insufficient credits, unresolved integrity flag, double-actuation) force a **hold** that cannot be approved past
5. **Clean proposals still always route to human approval** before any real-world act

## Store layer

The immutable audit ledger and store protocol live in:

```
src/registrar/store.cljc
```

Two implementations (swappable via `:db-api` in tests):
- `MemStore` – In-memory, for demos and testing
- `DatomicStore` – Datomic backend for production

## Facts and registry

- **`src/registrar/facts.cljc`** – Per-jurisdiction degree-accreditation catalog with spec-basis citations (currently seeded for JPN, USA, GBR, DEU)
- **`src/registrar/registry.cljc`** – Grade-finalization and degree-conferral draft records, plus `prerequisites-satisfied?` and `credits-sufficient?` checks

## Project layout

| File | Role |
|---|---|
| `src/registrar/store.cljc` | Store protocol, MemStore ‖ DatomicStore, audit ledger |
| `src/registrar/registry.cljc` | Draft records, prerequisites-satisfied?, credits-sufficient? |
| `src/registrar/facts.cljc` | Per-jurisdiction degree-accreditation catalog |
| `src/registrar/registraropsllm.cljc` | RegistrarOps-LLM Advisor (mock or LLM-backed) |
| `src/registrar/governor.cljc` | Academic Integrity Governor (independent verification) |
| `src/registrar/phase.cljc` | Phase 0→3 state machine; actuation always human |
| `src/registrar/operation.cljc` | OperationActor – langgraph-clj StateGraph |
| `src/registrar/sim.cljc` | Demo driver |
| `test/registrar/*_test.clj` | Governor contract, phase invariants, store parity, registry conformance, facts coverage |

## For production

See [`business-model.md`](business-model.md) for revenue model, [`operator-guide.md`](operator-guide.md) for first-deployment checklist, and [`adr/0001-architecture.md`](adr/0001-architecture.md) for the full architecture and design decisions.
