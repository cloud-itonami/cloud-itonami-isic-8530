(ns registrar.registraropsllm
  "RegistrarOps-LLM client -- the *contained intelligence node* for the
  higher-education actor.

  It normalizes enrollment intake, drafts a per-jurisdiction degree-
  accreditation evidence checklist, screens enrollments for an
  academic-integrity flag, drafts the grade-finalization action, and
  drafts the degree-conferral action. CRITICAL: it is a smart-but-
  untrusted advisor. It returns a *proposal* (with a rationale + the
  fields it cited), never a committed record or a real grade
  finalization/degree conferral. Every output is censored downstream
  by `registrar.governor` before anything touches the SSoT, and
  `:grade/finalize`/`:degree/confer` proposals NEVER auto-commit at
  any phase -- see README `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation/finalize-grade | :actuation/confer-degree | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [registrar.facts :as facts]
            [registrar.registry :as registry]
            [registrar.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the student, course/prerequisites or jurisdiction.
  High confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "履修記録更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :enrollment/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- assess-jurisdiction
  "Per-jurisdiction degree-accreditation evidence checklist draft.
  `:no-spec?` injects the failure mode we must defend against:
  proposing a checklist for a jurisdiction with NO official spec-basis
  in `registrar.facts` -- the Academic Integrity Governor must reject
  this (never invent a jurisdiction's requirements)."
  [db {:keys [subject no-spec?]}]
  (let [e (store/enrollment db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction e))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "registrar.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :assessment/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要書類 "
                        (count (:required-evidence sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :assessment/set
       :value      {:jurisdiction iso3
                    :checklist (:required-evidence sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(defn- screen-integrity
  "Academic-integrity screening draft. `:academic-integrity-flag?` on
  the enrollment record injects the failure mode: the Academic
  Integrity Governor must HOLD, un-overridably, on any open flag."
  [db {:keys [subject]}]
  (let [e (store/enrollment db subject)]
    (cond
      (nil? e)
      {:summary "対象enrollmentが見つかりません" :rationale "no enrollment record"
       :cites [] :effect :integrity/set :value {:enrollment-id subject :verdict :unknown}
       :stake nil :confidence 0.0}

      (:academic-integrity-flag? e)
      {:summary    (str (:student e) ": 未解決の学業インテグリティ違反フラグを検出")
       :rationale  "スクリーニングが未解決のフラグを検出。人手確認とホールドが必須。"
       :cites      [:integrity-check]
       :effect     :integrity/set
       :value      {:enrollment-id subject :verdict :open}
       :stake      nil
       :confidence 0.95}

      :else
      {:summary    (str (:student e) ": インテグリティ違反フラグなし")
       :rationale  "インテグリティスクリーニング非該当。"
       :cites      [:integrity-check]
       :effect     :integrity/set
       :value      {:enrollment-id subject :verdict :clear}
       :stake      nil
       :confidence 0.9})))

(defn- propose-grade-finalization
  "Draft the actual GRADE-FINALIZATION action -- finalizing a real
  course grade. ALWAYS `:stake :actuation/finalize-grade` -- this is a
  REAL-WORLD act (a permanent academic record), never a draft the
  actor may auto-run. See README `Actuation`: no phase ever adds this
  op to a phase's `:auto` set (`registrar.phase`); the governor also
  always escalates on `:actuation/finalize-grade`. Two independent
  layers agree, deliberately."
  [db {:keys [subject]}]
  (let [e (store/enrollment db subject)
        satisfied? (and e (registry/prerequisites-satisfied? e))]
    {:summary    (str subject " 向け成績確定提案"
                      (when e (str " (student=" (:student e) ")")))
     :rationale  (if e
                   (str "completed-courses=" (:completed-courses e)
                        " required-prerequisites=" (:required-prerequisites e))
                   "enrollmentが見つかりません")
     :cites      (if e [subject] [])
     :effect     :enrollment/mark-graded
     :value      {:enrollment-id subject}
     :stake      :actuation/finalize-grade
     :confidence (if satisfied? 0.9 0.3)}))

(defn- propose-degree-conferral
  "Draft the actual DEGREE-CONFERRAL action -- conferring a real
  degree. ALWAYS `:stake :actuation/confer-degree` -- this is a REAL-
  WORLD act (a permanent academic credential), never a draft the actor
  may auto-run. See README `Actuation`: no phase ever adds this op to
  a phase's `:auto` set (`registrar.phase`); the governor also always
  escalates on `:actuation/confer-degree`. Two independent layers
  agree, deliberately."
  [db {:keys [subject]}]
  (let [e (store/enrollment db subject)
        sufficient? (and e (registry/credits-sufficient? e))]
    {:summary    (str subject " 向け学位授与提案"
                      (when e (str " (student=" (:student e) ")")))
     :rationale  (if e
                   (str "credits-earned=" (:credits-earned e)
                        " minimum=" registry/minimum-credits-required)
                   "enrollmentが見つかりません")
     :cites      (if e [subject] [])
     :effect     :enrollment/mark-conferred
     :value      {:enrollment-id subject}
     :stake      :actuation/confer-degree
     :confidence (if sufficient? 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :enrollment/intake      (normalize-intake db request)
    :jurisdiction/assess       (assess-jurisdiction db request)
    :integrity/screen             (screen-integrity db request)
    :grade/finalize                  (propose-grade-finalization db request)
    :degree/confer                      (propose-degree-conferral db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは高等教育機関の成績確定・学位授与エージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。説明や前置きは"
       "一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:enrollment/upsert|:assessment/set|:integrity/set|"
       ":enrollment/mark-graded|:enrollment/mark-conferred) "
       ":stake(:actuation/finalize-grade か :actuation/confer-degree か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :jurisdiction/assess  {:enrollment (store/enrollment st subject)}
    :integrity/screen     {:enrollment (store/enrollment st subject)}
    :grade/finalize       {:enrollment (store/enrollment st subject)}
    :degree/confer        {:enrollment (store/enrollment st subject)}
    {:enrollment (store/enrollment st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Academic Integrity Governor
  escalates/holds -- an LLM hiccup can never auto-finalize a grade or
  auto-confer a degree."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :registraropsllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
