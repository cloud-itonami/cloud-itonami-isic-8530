(ns registrar.store
  "SSoT for the higher-education actor, behind a `Store` protocol so
  the backend is a swap, not a rewrite -- the same seam every prior
  `cloud-itonami-isic-*` actor in this fleet uses:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/registrar/store_contract_test.clj), which is the whole point:
  the actor, the Academic Integrity Governor and the audit ledger
  never know which SSoT they run on.

  Like `marketadmin.store`'s dual admission/halt-lift history, this
  actor has TWO actuation events (grade finalization, degree
  conferral) acting on the SAME entity (an enrollment), each with its
  OWN history collection, sequence counter and dedicated double-
  actuation-guard boolean (`:grade-finalized?`/`:degree-conferred?`,
  never a `:status` value) -- the same discipline `accounting.
  governor`'s/`marketadmin.governor`'s/`testlab.governor`'s/`clinic.
  governor`'s guards establish.

  The ledger stays append-only on every backend: 'which enrollment was
  screened for an academic-integrity flag, which grade was finalized,
  which degree was conferred, on what jurisdictional basis, approved
  by whom' is always a query over an immutable log -- the audit trail
  a student trusting an institution needs, and the evidence an
  operator needs if a grade or a degree is later disputed."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [registrar.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (enrollment [s id])
  (all-enrollments [s])
  (integrity-of [s enrollment-id] "committed academic-integrity screening verdict for an enrollment, or nil")
  (assessment-of [s enrollment-id] "committed jurisdiction accreditation assessment, or nil")
  (ledger [s])
  (grade-history [s] "the append-only grade-finalization history (registrar.registry drafts)")
  (degree-history [s] "the append-only degree-conferral history (registrar.registry drafts)")
  (next-grade-sequence [s jurisdiction] "next grade-finalization-number sequence for a jurisdiction")
  (next-degree-sequence [s jurisdiction] "next degree-conferral-number sequence for a jurisdiction")
  (enrollment-already-graded? [s enrollment-id] "has this enrollment's grade already been finalized?")
  (enrollment-already-conferred? [s enrollment-id] "has this enrollment's degree already been conferred?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-enrollments [s enrollments] "replace/seed the enrollment directory (map id->enrollment)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained enrollment set covering both actuation
  lifecycles (grade finalization, degree conferral) so the actor +
  tests run offline."
  []
  {:enrollments
   {"enrollment-1" {:id "enrollment-1" :student "Sakura Tanaka" :course "CS401"
                     :required-prerequisites #{"CS301"} :completed-courses #{"CS301" "CS201"}
                     :credits-earned 130 :academic-integrity-flag? false
                     :grade-finalized? false :degree-conferred? false :jurisdiction "JPN" :status :intake}
    "enrollment-2" {:id "enrollment-2" :student "Atlantis Doe" :course "CS401"
                     :required-prerequisites #{} :completed-courses #{}
                     :credits-earned 130 :academic-integrity-flag? false
                     :grade-finalized? false :degree-conferred? false :jurisdiction "ATL" :status :intake}
    "enrollment-3" {:id "enrollment-3" :student "鈴木一郎" :course "CS401"
                     :required-prerequisites #{"CS301"} :completed-courses #{"CS201"}
                     :credits-earned 130 :academic-integrity-flag? false
                     :grade-finalized? false :degree-conferred? false :jurisdiction "JPN" :status :intake}
    "enrollment-4" {:id "enrollment-4" :student "田中花子" :course "CS401"
                     :required-prerequisites #{} :completed-courses #{}
                     :credits-earned 90 :academic-integrity-flag? false
                     :grade-finalized? false :degree-conferred? false :jurisdiction "JPN" :status :intake}
    "enrollment-5" {:id "enrollment-5" :student "佐藤次郎" :course "CS401"
                     :required-prerequisites #{} :completed-courses #{}
                     :credits-earned 130 :academic-integrity-flag? true
                     :grade-finalized? false :degree-conferred? false :jurisdiction "JPN" :status :intake}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- finalize-grade!
  "Backend-agnostic `:enrollment/mark-graded` -- looks up the enrollment
  via the protocol and drafts the grade-finalization record, and
  returns {:result .. :enrollment-patch ..} for the caller to persist."
  [s enrollment-id]
  (let [e (enrollment s enrollment-id)
        seq-n (next-grade-sequence s (:jurisdiction e))
        result (registry/register-grade-finalization enrollment-id (:jurisdiction e) seq-n)]
    {:result result
     :enrollment-patch {:grade-finalized? true
                        :grade-number (get result "grade_number")}}))

(defn- confer-degree!
  "Backend-agnostic `:enrollment/mark-conferred` -- looks up the
  enrollment via the protocol and drafts the degree-conferral record,
  and returns {:result .. :enrollment-patch ..} for the caller to
  persist."
  [s enrollment-id]
  (let [e (enrollment s enrollment-id)
        seq-n (next-degree-sequence s (:jurisdiction e))
        result (registry/register-degree-conferral enrollment-id (:jurisdiction e) seq-n)]
    {:result result
     :enrollment-patch {:degree-conferred? true
                        :degree-number (get result "degree_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (enrollment [_ id] (get-in @a [:enrollments id]))
  (all-enrollments [_] (sort-by :id (vals (:enrollments @a))))
  (integrity-of [_ id] (get-in @a [:integrity id]))
  (assessment-of [_ enrollment-id] (get-in @a [:assessments enrollment-id]))
  (ledger [_] (:ledger @a))
  (grade-history [_] (:grades @a))
  (degree-history [_] (:degrees @a))
  (next-grade-sequence [_ jurisdiction] (get-in @a [:grade-sequences jurisdiction] 0))
  (next-degree-sequence [_ jurisdiction] (get-in @a [:degree-sequences jurisdiction] 0))
  (enrollment-already-graded? [_ enrollment-id] (boolean (get-in @a [:enrollments enrollment-id :grade-finalized?])))
  (enrollment-already-conferred? [_ enrollment-id] (boolean (get-in @a [:enrollments enrollment-id :degree-conferred?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :enrollment/upsert
      (swap! a update-in [:enrollments (:id value)] merge value)

      :assessment/set
      (swap! a assoc-in [:assessments (first path)] payload)

      :integrity/set
      (swap! a assoc-in [:integrity (first path)] payload)

      :enrollment/mark-graded
      (let [enrollment-id (first path)
            {:keys [result enrollment-patch]} (finalize-grade! s enrollment-id)
            jurisdiction (:jurisdiction (enrollment s enrollment-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:grade-sequences jurisdiction] (fnil inc 0))
                       (update-in [:enrollments enrollment-id] merge enrollment-patch)
                       (update :grades registry/append result))))
        result)

      :enrollment/mark-conferred
      (let [enrollment-id (first path)
            {:keys [result enrollment-patch]} (confer-degree! s enrollment-id)
            jurisdiction (:jurisdiction (enrollment s enrollment-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:degree-sequences jurisdiction] (fnil inc 0))
                       (update-in [:enrollments enrollment-id] merge enrollment-patch)
                       (update :degrees registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-enrollments [s enrollments] (when (seq enrollments) (swap! a assoc :enrollments enrollments)) s))

(defn seed-db
  "A MemStore seeded with the demo enrollment set. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :assessments {} :integrity {} :ledger [] :grade-sequences {}
                           :grades [] :degree-sequences {} :degrees []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (assessment/integrity payloads, ledger facts,
  grade/degree records, prerequisite/completed-course sets) are stored
  as EDN strings so `langchain.db` doesn't expand them into sub-
  entities -- the same convention every sibling actor's store uses."
  {:enrollment/id                {:db/unique :db.unique/identity}
   :assessment/enrollment-id      {:db/unique :db.unique/identity}
   :integrity/enrollment-id       {:db/unique :db.unique/identity}
   :ledger/seq                    {:db/unique :db.unique/identity}
   :grade/seq                      {:db/unique :db.unique/identity}
   :degree/seq                      {:db/unique :db.unique/identity}
   :grade-sequence/jurisdiction      {:db/unique :db.unique/identity}
   :degree-sequence/jurisdiction      {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- enrollment->tx [{:keys [id student course required-prerequisites completed-courses credits-earned
                               academic-integrity-flag? grade-finalized? degree-conferred?
                               jurisdiction status grade-number degree-number]}]
  (cond-> {:enrollment/id id}
    student                        (assoc :enrollment/student student)
    course                          (assoc :enrollment/course course)
    required-prerequisites           (assoc :enrollment/required-prerequisites (enc required-prerequisites))
    completed-courses                 (assoc :enrollment/completed-courses (enc completed-courses))
    credits-earned                     (assoc :enrollment/credits-earned credits-earned)
    (some? academic-integrity-flag?)    (assoc :enrollment/academic-integrity-flag? academic-integrity-flag?)
    (some? grade-finalized?)             (assoc :enrollment/grade-finalized? grade-finalized?)
    (some? degree-conferred?)             (assoc :enrollment/degree-conferred? degree-conferred?)
    jurisdiction                           (assoc :enrollment/jurisdiction jurisdiction)
    status                                  (assoc :enrollment/status status)
    grade-number                             (assoc :enrollment/grade-number grade-number)
    degree-number                             (assoc :enrollment/degree-number degree-number)))

(def ^:private enrollment-pull
  [:enrollment/id :enrollment/student :enrollment/course :enrollment/required-prerequisites
   :enrollment/completed-courses :enrollment/credits-earned :enrollment/academic-integrity-flag?
   :enrollment/grade-finalized? :enrollment/degree-conferred? :enrollment/jurisdiction
   :enrollment/status :enrollment/grade-number :enrollment/degree-number])

(defn- pull->enrollment [m]
  (when (:enrollment/id m)
    {:id (:enrollment/id m) :student (:enrollment/student m) :course (:enrollment/course m)
     :required-prerequisites (or (dec* (:enrollment/required-prerequisites m)) #{})
     :completed-courses (or (dec* (:enrollment/completed-courses m)) #{})
     :credits-earned (:enrollment/credits-earned m)
     :academic-integrity-flag? (boolean (:enrollment/academic-integrity-flag? m))
     :grade-finalized? (boolean (:enrollment/grade-finalized? m))
     :degree-conferred? (boolean (:enrollment/degree-conferred? m))
     :jurisdiction (:enrollment/jurisdiction m) :status (:enrollment/status m)
     :grade-number (:enrollment/grade-number m) :degree-number (:enrollment/degree-number m)}))

(defrecord DatomicStore [conn]
  Store
  (enrollment [_ id]
    (pull->enrollment (d/pull (d/db conn) enrollment-pull [:enrollment/id id])))
  (all-enrollments [_]
    (->> (d/q '[:find [?id ...] :where [?e :enrollment/id ?id]] (d/db conn))
         (map #(pull->enrollment (d/pull (d/db conn) enrollment-pull [:enrollment/id %])))
         (sort-by :id)))
  (integrity-of [_ id]
    (dec* (d/q '[:find ?p . :in $ ?eid
                :where [?k :integrity/enrollment-id ?eid] [?k :integrity/payload ?p]]
              (d/db conn) id)))
  (assessment-of [_ enrollment-id]
    (dec* (d/q '[:find ?p . :in $ ?eid
                :where [?a :assessment/enrollment-id ?eid] [?a :assessment/payload ?p]]
              (d/db conn) enrollment-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (grade-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :grade/seq ?s] [?e :grade/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (degree-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :degree/seq ?s] [?e :degree/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-grade-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :grade-sequence/jurisdiction ?j] [?e :grade-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (next-degree-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :degree-sequence/jurisdiction ?j] [?e :degree-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (enrollment-already-graded? [s enrollment-id]
    (boolean (:grade-finalized? (enrollment s enrollment-id))))
  (enrollment-already-conferred? [s enrollment-id]
    (boolean (:degree-conferred? (enrollment s enrollment-id))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :enrollment/upsert
      (d/transact! conn [(enrollment->tx value)])

      :assessment/set
      (d/transact! conn [{:assessment/enrollment-id (first path) :assessment/payload (enc payload)}])

      :integrity/set
      (d/transact! conn [{:integrity/enrollment-id (first path) :integrity/payload (enc payload)}])

      :enrollment/mark-graded
      (let [enrollment-id (first path)
            {:keys [result enrollment-patch]} (finalize-grade! s enrollment-id)
            jurisdiction (:jurisdiction (enrollment s enrollment-id))
            next-n (inc (next-grade-sequence s jurisdiction))]
        (d/transact! conn
                     [(enrollment->tx (assoc enrollment-patch :id enrollment-id))
                      {:grade-sequence/jurisdiction jurisdiction :grade-sequence/next next-n}
                      {:grade/seq (count (grade-history s)) :grade/record (enc (get result "record"))}])
        result)

      :enrollment/mark-conferred
      (let [enrollment-id (first path)
            {:keys [result enrollment-patch]} (confer-degree! s enrollment-id)
            jurisdiction (:jurisdiction (enrollment s enrollment-id))
            next-n (inc (next-degree-sequence s jurisdiction))]
        (d/transact! conn
                     [(enrollment->tx (assoc enrollment-patch :id enrollment-id))
                      {:degree-sequence/jurisdiction jurisdiction :degree-sequence/next next-n}
                      {:degree/seq (count (degree-history s)) :degree/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-enrollments [s enrollments]
    (when (seq enrollments) (d/transact! conn (mapv enrollment->tx (vals enrollments)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:enrollments ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [enrollments]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-enrollments s enrollments))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo enrollment set -- the Datomic-
  backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
