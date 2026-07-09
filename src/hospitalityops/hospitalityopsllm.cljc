(ns hospitalityops.hospitalityopsllm
  "HospitalityOps-LLM client -- the *contained intelligence node* for
  the community-accommodation actor.

  It normalizes stay intake, drafts a per-jurisdiction accommodation-
  operations/guest-registration evidence checklist, drafts the check-in
  action, and drafts the check-out action. CRITICAL: it is a smart-
  but-untrusted advisor. It returns a *proposal* (with a rationale +
  the fields it cited), never a committed record or a real check-in/
  check-out. Every output is censored downstream by
  `hospitalityops.governor` before anything touches the SSoT, and
  `:stay/check-in`/`:stay/check-out` proposals NEVER auto-commit at
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
     :stake      kw|nil         ; :actuation/check-in-guest | :actuation/check-out-guest | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [hospitalityops.facts :as facts]
            [hospitalityops.registry :as registry]
            [hospitalityops.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the property, nights/rate or jurisdiction. High
  confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "宿泊記録更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :stay/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- assess-jurisdiction
  "Per-jurisdiction accommodation-operations/guest-registration
  evidence checklist draft. `:no-spec?` injects the failure mode we
  must defend against: proposing a checklist for a jurisdiction with
  NO official spec-basis in `hospitalityops.facts` -- the Hospitality
  Governor must reject this (never invent a jurisdiction's
  requirements)."
  [db {:keys [subject no-spec?]}]
  (let [s (store/stay db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction s))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "hospitalityops.facts に未登録の法域。要件を推測で作らない。"
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

(defn- propose-check-in
  "Draft the actual CHECK-IN action -- checking a real guest into a
  real room. ALWAYS `:stake :actuation/check-in-guest` -- this is a
  REAL-WORLD act (a robot/front-desk staff physically hands over room
  access), never a draft the actor may auto-run. See README
  `Actuation`: no phase ever adds this op to a phase's `:auto` set
  (`hospitalityops.phase`); the governor also always escalates on
  `:actuation/check-in-guest`. Two independent layers agree,
  deliberately."
  [db {:keys [subject]}]
  (let [s (store/stay db subject)]
    {:summary    (str subject " 向けチェックイン提案"
                      (when s (str " (property=" (:property s) ")")))
     :rationale  (if s
                   (str "guest-id-verified?=" (:guest-id-verified? s)
                        " jurisdiction=" (:jurisdiction s))
                   "stayが見つかりません")
     :cites      (if s [subject] [])
     :effect     :stay/mark-checked-in
     :value      {:stay-id subject}
     :stake      :actuation/check-in-guest
     :confidence (if (and s (:guest-id-verified? s)) 0.9 0.3)}))

(defn- propose-check-out
  "Draft the actual CHECK-OUT action -- checking a real guest out of a
  real room (triggering folio settlement). ALWAYS `:stake :actuation/
  check-out-guest` -- this is a REAL-WORLD act (real charges settle,
  guest-disclosure obligations may apply), never a draft the actor may
  auto-run. See README `Actuation`: no phase ever adds this op to a
  phase's `:auto` set (`hospitalityops.phase`); the governor also
  always escalates on `:actuation/check-out-guest`. Two independent
  layers agree, deliberately."
  [db {:keys [subject]}]
  (let [s (store/stay db subject)
        folio-ok? (and s (registry/folio-total-matches-claim? s))
        disclosure-ok? (and s (or (not (:disclosure-requested? s)) (:disclosure-authorized? s)))]
    {:summary    (str subject " 向けチェックアウト提案"
                      (when s (str " (property=" (:property s) ")")))
     :rationale  (if s
                   (str "claimed-total=" (:claimed-total s)
                        " independent-recompute=" (registry/compute-folio-total s)
                        " disclosure-ok?=" disclosure-ok?)
                   "stayが見つかりません")
     :cites      (if s [subject] [])
     :effect     :stay/mark-checked-out
     :value      {:stay-id subject}
     :stake      :actuation/check-out-guest
     :confidence (if (and folio-ok? disclosure-ok?) 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :stay/intake              (normalize-intake db request)
    :jurisdiction/assess           (assess-jurisdiction db request)
    :stay/check-in                     (propose-check-in db request)
    :stay/check-out                        (propose-check-out db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは地域宿泊業者のチェックイン・チェックアウトエージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。"
       "説明や前置きは一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:stay/upsert|:assessment/set|:stay/mark-checked-in|"
       ":stay/mark-checked-out) "
       ":stake(:actuation/check-in-guest か :actuation/check-out-guest か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"
       "宿泊者本人確認の状況や開示請求の承認状況を偽って報告してはいけません。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :jurisdiction/assess    {:stay (store/stay st subject)}
    :stay/check-in          {:stay (store/stay st subject)}
    :stay/check-out         {:stay (store/stay st subject)}
    {:stay (store/stay st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Hospitality Governor
  escalates/holds -- an LLM hiccup can never auto-check-in or auto-
  check-out a guest."
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
  {:t          :hospitalityopsllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
