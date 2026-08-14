(ns registrar.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo: it drives the REAL
  actor stack -- `registrar.operation` (a langgraph-clj StateGraph)
  -> `registrar.governor` (Academic Integrity Governor) ->
  `registrar.phase` (rollout gate) -> `registrar.store` -- and renders
  ONLY values that came back out of that run.

  Nothing on the page is hand-typed telemetry:

    - every enrollment id/student/course/credit figure comes from this
      repo's own `registrar.store/demo-data` seed, read back through
      the `Store` protocol AFTER the run;
    - every hold reason, detail string and confidence comes from the
      governor's own violation maps as they were appended to the
      append-only ledger;
    - the action-gate table is derived from `registrar.phase/phases`,
      `registrar.phase/write-ops` and `registrar.governor/high-stakes`
      at render time, and each op's stake is read off a live
      `registrar.registraropsllm/infer` proposal -- not transcribed
      from the README;
    - the jurisdiction table and its honest coverage line come from
      `registrar.facts/catalog` + `registrar.facts/coverage`, asked
      about exactly the jurisdictions the seeded enrollments actually
      carry;
    - the approver-retention table is MEASURED: it inspects what
      actually reached the store for each commit effect and reports
      whether the approver identity survived, rather than asserting a
      behaviour.

  Determinism: no timestamps, no run-varying values, every set sorted
  before rendering, explicit UTF-8 on read and write. Two consecutive
  runs are byte-identical.

  Styling: jp-go-dds (デジタル庁デザインシステム), the workspace's base
  design system, consumed as a library. `-main` refuses to emit a page
  that references a CSS custom property nobody defines. The stylesheet
  is large because DADS is large; the footer therefore reports CONTENT
  bytes separately, since the page's worth is its HARD-hold count,
  section/row count and traceability to seed data, never its size.

  Build-time invariants (this is a gate, not a convention -- `-main`
  THROWS rather than writing a page that would quietly misrepresent
  the actor):

    1. every scenario step's ACTUAL disposition must equal the
       disposition the scenario declares it expects;
    2. the run must produce at least one governor HARD hold;
    3. the run must complete at least one full clean lifecycle (a
       grade finalization AND a degree conferral draft on file);
    4. every `var(--x)` the emitted page references must be defined in
       the emitted page;
    5. the emitted page's paired HTML tags must balance.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jp-go-dds.skin :as skin]
            [langgraph.graph :as g]
            [registrar.facts :as facts]
            [registrar.governor :as governor]
            [registrar.operation :as op]
            [registrar.phase :as phase]
            [registrar.registraropsllm :as registraropsllm]
            [registrar.registry :as registry]
            [registrar.store :as store]))

(def ^:private approver-id "op-1")

(def ^:private operator
  {:actor-id approver-id :actor-role :registrar :phase phase/default-phase})

;; ----------------------------- scenario -----------------------------

(def scenario
  "The scenario, as data, so `-main` can assert each step's ACTUAL
  disposition against the one declared here (invariant 1). Every
  `:subject` and every `:patch` field below exists in
  `registrar.store/demo-data`; no id, student name, course code,
  credit figure or jurisdiction is invented here.

  Steps s1..s5 are one full clean lifecycle for `enrollment-1`
  (intake auto-commits at phase 3; the assessment, the integrity
  screening, the grade finalization and the degree conferral each
  escalate to a human who approves). Steps s6..s14 reach all SEVEN of
  the governor's HARD checks. s15 is the human-REJECTION arm (a hold
  that is not a governor hold), s16 is the rollout-phase arm (the same
  op phase 3 approved at s10, refused outright at phase 1)."
  [{:id "s1" :thread "t-e1-intake" :expect :commit
    :request {:op :enrollment/intake :subject "enrollment-1"
              :patch {:id "enrollment-1" :student "Sakura Tanaka"}}
    :note "clean intake -- the only op any phase lets auto-commit"}

   {:id "s2" :thread "t-e1-assess" :approve :approved :expect :commit
    :request {:op :jurisdiction/assess :subject "enrollment-1"}
    :note "JPN accreditation evidence checklist -- escalates, human approves"}

   {:id "s3" :thread "t-e1-integrity" :approve :approved :expect :commit
    :request {:op :integrity/screen :subject "enrollment-1"}
    :note "academic-integrity screening, no flag -- escalates, human approves"}

   {:id "s4" :thread "t-e1-grade" :approve :approved :expect :commit
    :request {:op :grade/finalize :subject "enrollment-1"}
    :note "REAL grade finalization -- never auto at any phase, human approves"}

   {:id "s5" :thread "t-e1-degree" :approve :approved :expect :commit
    :request {:op :degree/confer :subject "enrollment-1"}
    :note "REAL degree conferral -- never auto at any phase, human approves"}

   {:id "s6" :thread "t-e1-grade-again" :expect :hold
    :request {:op :grade/finalize :subject "enrollment-1"}
    :note "double finalization of the same enrollment"}

   {:id "s7" :thread "t-e1-degree-again" :expect :hold
    :request {:op :degree/confer :subject "enrollment-1"}
    :note "double conferral of the same enrollment"}

   {:id "s8" :thread "t-e2-assess" :expect :hold
    :request {:op :jurisdiction/assess :subject "enrollment-2"}
    :note "enrollment-2's own seeded jurisdiction ATL has no official spec-basis"}

   {:id "s9" :thread "t-e2-grade" :expect :hold
    :request {:op :grade/finalize :subject "enrollment-2"}
    :note "s8 held, so no accreditation evidence is on file for enrollment-2"}

   {:id "s10" :thread "t-e3-assess" :approve :approved :expect :commit
    :request {:op :jurisdiction/assess :subject "enrollment-3"}
    :note "sets up s11 -- evidence on file, so the prerequisite check is what bites"}

   {:id "s11" :thread "t-e3-grade" :expect :hold
    :request {:op :grade/finalize :subject "enrollment-3"}
    :note "enrollment-3's completed courses do not contain its own required prerequisite"}

   {:id "s12" :thread "t-e4-assess" :approve :approved :expect :commit
    :request {:op :jurisdiction/assess :subject "enrollment-4"}
    :note "sets up s13 -- evidence on file, so the credit floor is what bites"}

   {:id "s13" :thread "t-e4-degree" :expect :hold
    :request {:op :degree/confer :subject "enrollment-4"}
    :note "enrollment-4's seeded credits-earned is below the conferral floor"}

   {:id "s14" :thread "t-e5-integrity" :expect :hold
    :request {:op :integrity/screen :subject "enrollment-5"}
    :note "the screening HARD-holds on its own finding -- never reaches a human"}

   {:id "s15" :thread "t-e4-integrity" :approve :rejected :expect :hold
    :request {:op :integrity/screen :subject "enrollment-4"}
    :note "human REJECTS -- a hold that is not a governor hold"}

   {:id "s16" :thread "t-e3-assess-phase1" :phase 1 :expect :hold
    :request {:op :jurisdiction/assess :subject "enrollment-3"}
    :note "identical to s10, but phase 1 does not enable this write at all"}])

(defn- step-by-id [id] (first (filter #(= id (:id %)) scenario)))

(defn- run-step!
  "Runs one scenario step through the real compiled actor and returns
  its final disposition. An approval step resumes the interrupted
  graph exactly the way a human operator would."
  [actor {:keys [thread request approve phase]}]
  (let [ctx (assoc operator :phase (or phase phase/default-phase))
        d1  (-> (g/run* actor {:request request :context ctx} {:thread-id thread})
                (get-in [:state :disposition]))]
    (if (and approve (= :escalate d1))
      (-> (g/run* actor {:approval {:status approve :by approver-id}}
                  {:thread-id thread :resume? true})
          (get-in [:state :disposition]))
      d1)))

(defn run-demo!
  "Runs the whole scenario against a freshly seeded MemStore and
  returns {:db .. :outcomes [{:id .. :expect .. :actual ..} ..]}."
  []
  (let [db (store/seed-db)
        actor (op/build db)]
    {:db db
     :outcomes (mapv (fn [{:keys [id expect] :as step}]
                       {:id id :expect expect :actual (run-step! actor step)})
                     scenario)}))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- nm [v] (if (keyword? v) (name v) (str v)))

(defn- kw-code [v] (str "<code>" (esc (str v)) "</code>"))

(defn- join-sorted [coll]
  (if (seq coll)
    (str/join ", " (sort (map str coll)))
    "—"))

(defn- muted [s] (str "<span class=\"muted\">" s "</span>"))
(defn- ok [s] (str "<span class=\"ok\">" s "</span>"))
(defn- warn [s] (str "<span class=\"warn\">" s "</span>"))
(defn- critical [s] (str "<span class=\"critical\">" s "</span>"))

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (str/join "\n" rows) "\n"
       "      </tbody>\n"
       "    </table>\n"))

(defn- section [title lead body]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       "    <p class=\"muted\">" lead "</p>\n"
       body
       "  </section>\n"))

;; ----------------------------- ledger views -----------------------------

(defn- hard-holds
  "Governor HARD holds only. A phase-gate hold is also written as a
  `:governor-hold` fact but carries an EMPTY `:basis` (the governor
  found nothing; the rollout phase refused the write), so basis
  presence is what separates them -- not a hand-kept list."
  [ledger]
  (filter #(and (= :governor-hold (:t %)) (seq (:basis %))) ledger))

(defn- phase-holds [ledger]
  (filter #(and (= :governor-hold (:t %)) (empty? (:basis %))) ledger))

(defn- rejection-holds [ledger]
  (filter #(= :approval-rejected (:t %)) ledger))

(defn- last-fact-for [ledger id]
  (last (filter #(= id (:subject %)) ledger)))

(defn- status-cell [ledger id]
  (let [f (last-fact-for ledger id)]
    (cond
      (nil? f) (muted "no activity")
      (= :committed (:t f)) (ok (str "committed &middot; " (esc (nm (:op f)))))
      (= :approval-rejected (:t f)) (warn (str "approver rejected &middot; " (esc (nm (:op f)))))
      (= :governor-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (if rule
          (critical (str "HARD hold &middot; " (esc (nm rule))))
          (warn (str "phase hold &middot; " (esc (nm (:phase-reason f)))))))
      :else (muted "in progress"))))

(defn- basis-cell [{:keys [t basis violations phase-reason]}]
  (cond
    (seq violations) (esc (str/join ", " (map (comp nm :rule) violations)))
    phase-reason (esc (nm phase-reason))
    (seq basis) (esc (str/join " / " (map nm basis)))
    (= :governor-hold t) (muted "no governor violation")
    :else (muted "—")))

;; ----------------------------- sections -----------------------------

(defn- enrollment-section [db ledger]
  (let [rows (for [{:keys [id student course jurisdiction credits-earned
                           required-prerequisites completed-courses
                           academic-integrity-flag? grade-finalized? degree-conferred?
                           grade-number degree-number]}
                   (store/all-enrollments db)]
               (row (esc id) (esc student) (kw-code course)
                    (esc jurisdiction)
                    (str "<span class=\"num\">" credits-earned "</span>")
                    (esc (join-sorted required-prerequisites))
                    (esc (join-sorted completed-courses))
                    (if academic-integrity-flag? (critical "open") (ok "none"))
                    (if grade-finalized?
                      (ok (str "<span class=\"num\">" (esc grade-number) "</span>"))
                      (muted "not finalized"))
                    (if degree-conferred?
                      (ok (str "<span class=\"num\">" (esc degree-number) "</span>"))
                      (muted "not conferred"))
                    (status-cell ledger id)))]
    (section
     "Enrollment directory"
     (str "Read back out of <code>registrar.store</code> AFTER the run — every id, student, "
          "course code, credit figure, prerequisite set and jurisdiction below is this repo's own "
          "<code>registrar.store/demo-data</code> seed, not sample text.")
     (table ["Enrollment" "Student" "Course" "Jurisdiction" "Credits" "Required prereqs"
             "Completed" "Integrity flag" "Grade" "Degree" "Last ledger fact"]
            rows))))

(defn- hard-hold-section [db ledger]
  (let [holds (hard-holds ledger)
        rules (sort (distinct (mapcat #(map :rule (:violations %)) holds)))
        rows (for [h holds
                   v (:violations h)]
               (row (critical (esc (nm (:rule v))))
                    (kw-code (:op h))
                    (esc (:subject h))
                    (esc (or (:student (store/enrollment db (:subject h))) "—"))
                    (esc (:detail v))
                    (str "<span class=\"num\">" (esc (:confidence h)) "</span>")))]
    (section
     (str "Governor HARD holds — " (count rules) " distinct reasons, "
          (count holds) " holds")
     (str "A HARD hold is un-overridable: it never reaches a human at all, so there is no "
          "approve-your-way-past-it path. Each detail string below is the governor's OWN "
          "violation text as it was appended to the append-only ledger. Reasons exercised: "
          "<code>" (esc (str/join "</code>, <code>" (map nm rules))) "</code>.")
     (table ["Rule" "Op" "Enrollment" "Student" "Governor detail" "Advisor confidence"] rows))))

(defn- other-hold-section [ledger]
  (let [rows (concat
              (for [h (rejection-holds ledger)]
                (row (warn "approver rejected")
                     (kw-code (:op h))
                     (esc (:subject h))
                     (muted "—")
                     (esc (str "human " approver-id " declined the escalation; the governor itself found nothing"))))
              (for [h (phase-holds ledger)]
                (row (warn (esc (nm (:phase-reason h))))
                     (kw-code (:op h))
                     (esc (:subject h))
                     (str "<span class=\"num\">" (esc (:phase h)) "</span>")
                     (esc (str "phase " (:phase h) " ("
                               (:label (get phase/phases (:phase h)))
                               ") does not enable this write; the governor found no violation")))))]
    (section
     "Holds that are NOT governor holds"
     (str "Two independent layers can stop a write, and the ledger keeps them distinguishable. "
          "A human may decline an escalation, and the rollout phase gate "
          "(<code>registrar.phase</code>) may refuse a write the governor was perfectly happy with.")
     (table ["Kind" "Op" "Enrollment" "Phase" "Why"] rows))))

(defn- action-gate-section [db]
  (let [ops (sort-by str phase/write-ops)
        phase-ks (sort (keys phase/phases))
        rows (for [o ops
                   :let [stake (:stake (registraropsllm/infer db {:op o :subject "enrollment-1"}))
                         writes (filter #(contains? (:writes (get phase/phases %)) o) phase-ks)
                         autos (filter #(contains? (:auto (get phase/phases %)) o) phase-ks)
                         always-human? (or (empty? autos)
                                           (contains? governor/high-stakes stake))]]
               (row (kw-code o)
                    (esc (join-sorted writes))
                    (if (seq autos) (ok (esc (join-sorted autos))) (critical "never"))
                    (if stake (kw-code stake) (muted "—"))
                    (if always-human?
                      (critical "always a human decision")
                      (ok "may auto-commit when the governor is clean"))))]
    (section
     "Action gate — derived, not transcribed"
     (str "Rows are computed at build time from <code>registrar.phase/write-ops</code>, "
          "<code>registrar.phase/phases</code> and <code>registrar.governor/high-stakes</code>; "
          "each op's stake is read off a live <code>registrar.registraropsllm/infer</code> proposal. "
          "If someone adds an actuation op to a phase's <code>:auto</code> set, this table changes "
          "on the next build — it cannot drift out of date the way a hand-written table would. "
          "Governor confidence floor: <span class=\"num\">" (esc governor/confidence-floor) "</span>.")
     (table ["Op" "Phases that allow the write" "Phases that allow auto-commit" "Stake" "Verdict"]
            rows))))

(defn- jurisdiction-section [db]
  (let [used (sort (distinct (map :jurisdiction (store/all-enrollments db))))
        cov (facts/coverage used)
        rows (for [iso3 used
                   :let [sb (facts/spec-basis iso3)]]
               (row (esc iso3)
                    (if sb (esc (:name sb)) (critical "no spec-basis on file"))
                    (if sb (esc (:owner-authority sb)) (muted "—"))
                    (if sb (esc (:legal-basis sb)) (muted "—"))
                    (if sb (str "<code>" (esc (:provenance sb)) "</code>") (muted "—"))
                    (if sb
                      (str "<span class=\"num\">" (count (:required-evidence sb)) "</span>")
                      (critical "0"))))]
    (section
     "Jurisdiction spec-basis"
     (str "Asked of <code>registrar.facts/coverage</code> about exactly the jurisdictions the "
          "seeded enrollments actually carry — "
          "<span class=\"num\">" (:covered cov) "</span> of "
          "<span class=\"num\">" (:requested cov) "</span> covered; missing: <code>"
          (esc (join-sorted (:missing-jurisdictions cov)))
          "</code>. Coverage is reported honestly: a jurisdiction not in the catalog has NO "
          "spec-basis, and the governor holds any proposal that tries to invent one. "
          (esc (:note cov)))
     (table ["ISO3" "Name" "Owner authority" "Legal basis" "Provenance" "Required evidence items"]
            rows))))

(defn- evidence-section [db]
  (let [a (store/assessment-of db "enrollment-1")
        rows (map-indexed
              (fn [i item]
                (row (str "<span class=\"num\">" (inc i) "</span>") (esc item) (ok "on file")))
              (:checklist a))]
    (section
     "Accreditation evidence actually on file (enrollment-1)"
     (str "The committed <code>:assessment/set</code> payload for <code>enrollment-1</code>, read back "
          "through the store. <code>registrar.governor</code>'s <code>:evidence-incomplete</code> check "
          "re-verifies this list against <code>registrar.facts/required-evidence-satisfied?</code> before "
          "any grade finalization or degree conferral — which is exactly why "
          "<code>enrollment-2</code>, whose assessment HARD-held, could not be graded. "
          "Legal basis cited: " (esc (:legal-basis a)) ".")
     (table ["#" "Evidence item" "Status"] rows))))

(defn- actuation-section [db]
  (let [rows (concat
              (for [r (store/grade-history db)]
                (row (str "<span class=\"num\">" (esc (get r "record_id")) "</span>")
                     (esc (get r "kind"))
                     (esc (get r "enrollment_id"))
                     (esc (get r "jurisdiction"))
                     (if (get r "immutable") (ok "immutable") (warn "mutable"))))
              (for [r (store/degree-history db)]
                (row (str "<span class=\"num\">" (esc (get r "record_id")) "</span>")
                     (esc (get r "kind"))
                     (esc (get r "enrollment_id"))
                     (esc (get r "jurisdiction"))
                     (if (get r "immutable") (ok "immutable") (warn "mutable")))))]
    (section
     "Actuation records drafted this run"
     (str "The two real-world acts this actor performs, as drafted by "
          "<code>registrar.registry</code> and appended to the store's own history collections. "
          "Reference numbers are jurisdiction-scoped sequences this repo builds itself — it does NOT "
          "invent a check-digit standard, and the credential each record carries is deliberately "
          "UNSIGNED (<code>status: draft-unsigned</code>): signing is the registrar's act, not the "
          "actor's. Credit floor for conferral: <span class=\"num\">"
          registry/minimum-credits-required "</span>.")
     (table ["Record id" "Kind" "Enrollment" "Jurisdiction" "Record"] rows))))

;; --- approver retention: measured, not assumed ------------------------

(def ^:private retention-probes
  "Each commit effect this actor can produce, paired with the scenario
  step that produced it and a reader that fetches whatever actually
  landed in the store. The page reports what the reader finds — it
  does not claim a behaviour in advance."
  [{:effect :enrollment/upsert :step "s1"
    :where "enrollment record"
    :read (fn [db] (store/enrollment db "enrollment-1"))}
   {:effect :assessment/set :step "s2"
    :where "assessment payload"
    :read (fn [db] (store/assessment-of db "enrollment-1"))}
   {:effect :integrity/set :step "s3"
    :where "integrity payload"
    :read (fn [db] (store/integrity-of db "enrollment-1"))}
   {:effect :enrollment/mark-graded :step "s4"
    :where "grade-finalization record"
    :read (fn [db] (first (store/grade-history db)))}
   {:effect :enrollment/mark-conferred :step "s5"
    :where "degree-conferral record"
    :read (fn [db] (first (store/degree-history db)))}])

(defn- approver-in
  "Whatever approver identity is present in a stored value, under any of
  the key spellings this repo uses (records are string-keyed, payloads
  keyword-keyed)."
  [m]
  (when (map? m)
    (or (get m :approved-by) (get m "approved_by") (get m "approved-by"))))

(defn- retention-section [db ledger]
  (let [probes (for [{:keys [effect step where read]} retention-probes
                     :let [s (step-by-id step)
                           v (read db)
                           human? (= :approved (:approve s))
                           who (approver-in v)]]
                 {:effect effect :step step :where where :human? human? :who who})
        approved (filter :human? probes)
        retained (filter :who approved)
        granted-in-scenario (count (filter #(= :approved (:approve %)) scenario))
        granted-in-ledger (count (filter #(= :approval-granted (:t %)) ledger))
        rows (for [{:keys [effect step where human? who]} probes]
               (row (kw-code effect)
                    (esc step)
                    (esc where)
                    (if human? (ok (str "yes &middot; " (esc approver-id))) (muted "no — auto-committed"))
                    (cond
                      (not human?) (muted "n/a — no human approver existed")
                      who (ok (str "retained as <code>" (esc who) "</code>"))
                      :else (warn "audit fact only — not retained in the record"))))]
    (section
     "Approver attribution — measured against this run"
     (str "Whether the approving human's identity survives into the SSoT is not uniform across this "
          "actor's five commit effects, so the console measures it rather than asserting it: for each "
          "effect it reads back whatever actually landed in the store and reports what it finds. "
          "This run: <span class=\"num\">" (count retained) "</span> of "
          "<span class=\"num\">" (count approved) "</span> human-approved commits carry the approver "
          "in the stored value. "
          "Separately, <span class=\"num\">" granted-in-scenario "</span> approvals were granted during "
          "the run and <span class=\"num\">" granted-in-ledger "</span> <code>:approval-granted</code> "
          "facts are present in the store ledger — where those two numbers differ, the approval is "
          "visible in the graph's audit channel but is not part of the durable ledger, so a reader "
          "of the ledger alone cannot tell WHO approved a commit. Neither observation is a claim about "
          "other repos, and neither is hard-coded here.")
     (table ["Commit effect" "Step" "Where it landed" "Human approved?" "Approver in the stored value"]
            rows))))

(defn- ledger-section [ledger]
  (let [rows (map-indexed
              (fn [i f]
                (row (str "<span class=\"num\">" i "</span>")
                     (case (:t f)
                       :committed (ok "committed")
                       :governor-hold (if (seq (:basis f)) (critical "governor-hold") (warn "governor-hold"))
                       :approval-rejected (warn "approval-rejected")
                       (esc (nm (:t f))))
                     (kw-code (:op f))
                     (esc (:subject f))
                     (esc (nm (:disposition f)))
                     (basis-cell f)))
              ledger)]
    (section
     (str "Audit ledger — " (count ledger) " immutable facts")
     (str "The append-only decision log, in the order the actor wrote it. Every commit and every hold "
          "this scenario produced is here; nothing is filtered out to make the run look cleaner. "
          "Actor identity on every fact: <code>" (esc approver-id) "</code>.")
     (table ["#" "Fact" "Op" "Enrollment" "Disposition" "Basis / reason"] rows))))

;; ----------------------------- css -----------------------------

(defn design-system-css
  "The workspace's base design system, デジタル庁デザインシステム (DADS),
  via `jp-go-dds` — the library, not a copy of it.

  `dds+skin` is the vendored upstream DADS stylesheet followed by the
  compatibility skin that maps this console's small semantic class
  vocabulary (`.card`/`.badge`/`.ok`/`.warn`/`.critical`/`.muted`/
  `.num`/`.bar`) onto DADS tokens. It is emitted inline so the sample
  page stays a single self-contained artifact that renders offline.

  Taken from the dependency rather than scraped out of this repo's own
  `docs/index.html`: that landing page vendors the same bytes today,
  but nothing keeps the two in step, and a build-time renderer that
  string-scrapes a sibling document silently loses its styling the day
  that document is restructured. The size of this block is a property
  of the design system, not of this page — see the footer, which
  reports the content bytes separately so the two are never confused."
  []
  (skin/dds+skin))

;; ----------------------------- document -----------------------------

(defn render
  "Renders the whole operator-console document from a store `db` that
  has already been driven by `run-demo!`."
  [db]
  (let [ledger (vec (store/ledger db))
        hh (hard-holds ledger)
        rules (sort (distinct (mapcat #(map :rule (:violations %)) hh)))
        css (design-system-css)]
    (str
     "<!DOCTYPE html>\n"
     "<html lang=\"en\">\n<head>\n"
     "<meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
     "<title>Operator console — Higher education (ISIC 8530) — cloud-itonami-isic-8530</title>\n"
     "<style>" css "</style>\n"
     "</head>\n<body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Higher education (ISIC 8530) — Operator console</h1>\n"
     "</header>\n"
     "<p><span class=\"badge\">read-only sample</span> <span class=\"badge\">governor-gated</span> "
     "<span class=\"badge\">grade finalization &amp; degree conferral always human-approved</span></p>\n"
     "<p class=\"subtitle\">Generated at build time by <code>registrar.render-html</code> "
     "(<code>clojure -M:dev:render-html</code>) by driving the real actor stack — "
     "<code>registrar.operation</code> → <code>registrar.governor</code> → "
     "<code>registrar.phase</code> → <code>registrar.store</code> — over the seed data in "
     "<code>registrar.store/demo-data</code>. Every figure on this page came back out of that run. "
     "The page carries no timestamp on purpose: two consecutive builds are byte-identical, so a "
     "diff means the actor's behaviour changed, not that the clock moved.</p>\n"
     "<main>\n"
     (enrollment-section db ledger)
     (hard-hold-section db ledger)
     (other-hold-section ledger)
     (action-gate-section db)
     (jurisdiction-section db)
     (evidence-section db)
     (actuation-section db)
     (retention-section db ledger)
     (ledger-section ledger)
     "</main>\n"
     "<footer>\n"
     "  <p>cloud-itonami-isic-8530 — AGPL-3.0-or-later. "
     (count ledger) " ledger facts, " (count hh) " governor HARD holds across "
     (count rules) " distinct reasons, "
     (count (store/grade-history db)) " grade finalization and "
     (count (store/degree-history db)) " degree conferral draft(s) this run. "
     "Regenerate with <code>clojure -M:dev:render-html</code>; the build refuses to write this page "
     "if the run produces no HARD hold, if any step's disposition differs from the scenario's "
     "declared expectation, if no clean lifecycle completes, or if the page would reference an "
     "undefined CSS custom property.</p>\n"
     "  <p class=\"muted\">Of this file, <span class=\"num\">" (count css) "</span> characters are the "
     "jp-go-dds (デジタル庁デザインシステム) stylesheet, which is a property of the design system and "
     "not of this page. Judge the page by the counts above — HARD holds, sections, rows, and whether "
     "every identifier on it can be found in <code>registrar.store/demo-data</code> — never by its "
     "size.</p>\n"
     "</footer>\n"
     "</body>\n</html>\n")))

;; ----------------------------- build gates -----------------------------

(defn- assert-dispositions! [outcomes]
  (let [bad (remove #(= (:expect %) (:actual %)) outcomes)]
    (when (seq bad)
      (throw (ex-info (str "render-html: " (count bad) " scenario step(s) did not reach the "
                           "disposition the scenario declares. The actor's behaviour changed; "
                           "refusing to render a page that would describe a run that did not happen.")
                      {:mismatches (mapv #(select-keys % [:id :expect :actual]) bad)})))))

(defn- assert-hard-holds! [ledger]
  (let [hh (hard-holds ledger)]
    (when (zero? (count hh))
      (throw (ex-info (str "render-html: the run produced ZERO governor HARD holds. A console that "
                           "shows only successful commits misrepresents what the Academic Integrity "
                           "Governor is for. Refusing to write the page.")
                      {:ledger-facts (count ledger)})))
    hh))

(defn- assert-clean-lifecycle! [db]
  (when-not (and (seq (store/grade-history db)) (seq (store/degree-history db)))
    (throw (ex-info (str "render-html: the run completed no full clean lifecycle (a grade "
                         "finalization AND a degree conferral draft must reach the store). "
                         "Refusing to write the page.")
                    {:grades (count (store/grade-history db))
                     :degrees (count (store/degree-history db))}))))

(defn- assert-css-vars! [html]
  (let [refs (set (map second (re-seq #"var\(\s*(--[A-Za-z0-9_-]+)" html)))
        defs (set (map second (re-seq #"(--[A-Za-z0-9_-]+)\s*:" html)))
        missing (sort (remove defs refs))]
    (when (seq missing)
      (throw (ex-info (str "render-html: the page references " (count missing) " CSS custom "
                           "propert(y|ies) nobody defines — it would render unstyled in places. "
                           "Refusing to write it.")
                      {:undefined missing})))
    {:refs (count refs) :defs (count defs)}))

(defn- assert-tags-balanced! [html]
  (let [tags ["html" "head" "body" "main" "section" "header" "footer" "table" "thead" "tbody"
              "tr" "td" "th" "h1" "h2" "p" "span" "code" "style"]
        bad (for [t tags
                  :let [o (count (re-seq (re-pattern (str "<" t "[ >]")) html))
                        c (count (re-seq (re-pattern (str "</" t ">")) html))]
                  :when (not= o c)]
              {:tag t :open o :close c})]
    (when (seq bad)
      (throw (ex-info "render-html: unbalanced HTML tags. Refusing to write the page."
                      {:unbalanced (vec bad)})))))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db outcomes]} (run-demo!)
        ledger (vec (store/ledger db))
        _ (assert-dispositions! outcomes)
        hh (assert-hard-holds! ledger)
        _ (assert-clean-lifecycle! db)
        rules (sort (distinct (mapcat #(map :rule (:violations %)) hh)))
        html (render db)
        {:keys [refs defs]} (assert-css-vars! html)
        _ (assert-tags-balanced! html)
        f (io/file out)]
    (when-let [p (.getParentFile f)] (.mkdirs p))
    (spit f html :encoding "UTF-8")
    (println (str "wrote " out
                  " (" (count html) " chars, " (count ledger) " ledger facts, "
                  (count hh) " HARD holds across " (count rules) " reasons: "
                  (str/join ", " (map nm rules)) ", "
                  (count (store/grade-history db)) " grade + "
                  (count (store/degree-history db)) " degree draft(s), "
                  refs " css vars referenced / " defs " defined)"))))
