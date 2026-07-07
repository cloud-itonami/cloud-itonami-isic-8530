(ns registrar.facts
  "Per-jurisdiction higher-education accreditation regulatory catalog --
  the G2-style spec-basis table the Academic Integrity Governor checks
  every jurisdiction/assess proposal against ('did the advisor cite an
  OFFICIAL public source for this jurisdiction's degree-program
  accreditation requirements, or did it invent one?').

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  every sibling actor's `facts` namespace uses: a jurisdiction not in
  this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries.

  Seed values are drawn from each jurisdiction's official accreditation
  authority (see `:provenance`); they are a STARTING catalog, not a
  from-scratch survey of all ~194 jurisdictions. Extending coverage is
  additive: add one map to `catalog`, cite a real source, done --
  never invent a jurisdiction's requirements to make coverage look
  bigger.

  Like `credit.facts`'s USA entry, the USA entry here cites a single
  national aggregating reference (the U.S. Department of Education /
  Council for Higher Education Accreditation) rather than all
  individual regional/national accreditors -- an honest single
  representative citation, not an accreditor-by-accreditor survey, the
  same simplification every prior catalog makes when a jurisdiction's
  real regulatory structure is itself federated.")

(def catalog
  "iso3 -> requirement map. `:required-evidence` mirrors the generic
  student-enrollment-record/transcript-citation/prerequisite-
  verification/degree-program-accreditation evidence set submitted in
  some form; `:legal-basis` / `:owner-authority` / `:provenance` are
  the G2 citation the governor requires before any :jurisdiction/
  assess proposal can commit."
  {"JPN" {:name "Japan"
          :owner-authority "文部科学省 (Ministry of Education, Culture, Sports, Science and Technology, MEXT)"
          :legal-basis "学校教育法 (School Education Act) + 大学設置基準 (University Establishment Standards)"
          :national-spec "大学設置・学校法人審議会による設置認可制度"
          :provenance "https://www.mext.go.jp/"
          :required-evidence ["学生在籍記録 (student enrollment record)"
                              "成績証明書引用 (transcript citation)"
                              "履修前提条件確認記録 (prerequisite-verification record)"
                              "学位課程認可証明書 (degree-program accreditation certificate)"]}
   "USA" {:name "United States"
          :owner-authority "U.S. Department of Education (ED) / Council for Higher Education Accreditation (CHEA)"
          :legal-basis "Higher Education Act of 1965 (accreditor recognition via ED/CHEA)"
          :national-spec "ED-recognized/CHEA-recognized accreditor standards"
          :provenance "https://www.ed.gov/ https://www.chea.org/"
          :required-evidence ["Student enrollment record"
                              "Transcript citation"
                              "Prerequisite-verification record"
                              "Degree-program accreditation certificate"]}
   "GBR" {:name "United Kingdom"
          :owner-authority "Office for Students (OfS)"
          :legal-basis "Higher Education and Research Act 2017"
          :national-spec "OfS Register conditions of registration"
          :provenance "https://www.officeforstudents.org.uk/"
          :required-evidence ["Student enrollment record"
                              "Transcript citation"
                              "Prerequisite-verification record"
                              "Degree-program accreditation certificate"]}
   "DEU" {:name "Germany"
          :owner-authority "Akkreditierungsrat (German Accreditation Council)"
          :legal-basis "Studienakkreditierungsstaatsvertrag (Interstate Study Accreditation Treaty)"
          :national-spec "Akkreditierungsrat Studiengangsakkreditierung"
          :provenance "https://www.akkreditierungsrat.de/"
          :required-evidence ["Immatrikulationsnachweis (student enrollment record)"
                              "Notenübersicht (transcript citation)"
                              "Voraussetzungsprüfungsprotokoll (prerequisite-verification record)"
                              "Studiengangsakkreditierungsurkunde (degree-program accreditation certificate)"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to finalize a
  grade or confer a degree on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-8530 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `registrar.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))
