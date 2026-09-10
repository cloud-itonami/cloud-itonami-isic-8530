(ns registrar.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean enrollment through
  intake -> jurisdiction accreditation assessment -> academic-integrity
  screening -> grade-finalization proposal (always escalates) -> human
  approval -> commit, then through degree-conferral proposal (always
  escalates) -> human approval -> commit, then shows five HARD holds (a
  jurisdiction with no spec-basis, an unsatisfied course prerequisite,
  insufficient credits for degree conferral, an unresolved academic-
  integrity flag, and a double finalization/conferral of an already-
  processed enrollment) that never reach a human at all, and prints the
  audit ledger + the draft grade-finalization and degree-conferral
  records."
  (:require [langgraph.graph :as g]
            [registrar.store :as store]
            [registrar.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :registrar :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== enrollment/intake enrollment-1 (JPN, clean; prerequisites satisfied, 130 credits) ==")
    (println (exec! actor "t1" {:op :enrollment/intake :subject "enrollment-1"
                                :patch {:id "enrollment-1" :student "Sakura Tanaka"}} operator))

    (println "== jurisdiction/assess enrollment-1 (escalates -- human approves) ==")
    (println (exec! actor "t2" {:op :jurisdiction/assess :subject "enrollment-1"} operator))
    (println (approve! actor "t2"))

    (println "== integrity/screen enrollment-1 (clean; escalates -- human approves) ==")
    (println (exec! actor "t3" {:op :integrity/screen :subject "enrollment-1"} operator))
    (println (approve! actor "t3"))

    (println "== grade/finalize enrollment-1 (always escalates -- actuation/finalize-grade) ==")
    (let [r (exec! actor "t4" {:op :grade/finalize :subject "enrollment-1"} operator)]
      (println r)
      (println "-- human registrar approves --")
      (println (approve! actor "t4")))

    (println "== degree/confer enrollment-1 (always escalates -- actuation/confer-degree) ==")
    (let [r (exec! actor "t5" {:op :degree/confer :subject "enrollment-1"} operator)]
      (println r)
      (println "-- human registrar approves --")
      (println (approve! actor "t5")))

    (println "== jurisdiction/assess enrollment-2 (no spec-basis -> HARD hold) ==")
    (println (exec! actor "t6" {:op :jurisdiction/assess :subject "enrollment-2" :no-spec? true} operator))

    (println "== jurisdiction/assess enrollment-3 (escalates -- human approves; sets up the prerequisite test) ==")
    (println (exec! actor "t7" {:op :jurisdiction/assess :subject "enrollment-3"} operator))
    (println (approve! actor "t7"))

    (println "== grade/finalize enrollment-3 (CS301 prerequisite not completed -> HARD hold) ==")
    (println (exec! actor "t8" {:op :grade/finalize :subject "enrollment-3"} operator))

    (println "== jurisdiction/assess enrollment-4 (escalates -- human approves; sets up the credits test) ==")
    (println (exec! actor "t9" {:op :jurisdiction/assess :subject "enrollment-4"} operator))
    (println (approve! actor "t9"))

    (println "== degree/confer enrollment-4 (90 credits below the 124-credit minimum -> HARD hold) ==")
    (println (exec! actor "t10" {:op :degree/confer :subject "enrollment-4"} operator))

    (println "== integrity/screen enrollment-5 (unresolved academic-integrity flag -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t11" {:op :integrity/screen :subject "enrollment-5"} operator))

    (println "== grade/finalize enrollment-1 AGAIN (double-finalization -> HARD hold) ==")
    (println (exec! actor "t12" {:op :grade/finalize :subject "enrollment-1"} operator))

    (println "== degree/confer enrollment-1 AGAIN (double-conferral -> HARD hold) ==")
    (println (exec! actor "t13" {:op :degree/confer :subject "enrollment-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft grade-finalization records ==")
    (doseq [r (store/grade-history db)] (println r))

    (println "== draft degree-conferral records ==")
    (doseq [r (store/degree-history db)] (println r))))
