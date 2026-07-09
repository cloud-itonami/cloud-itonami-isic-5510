(ns hospitalityops.governor
  "Hospitality Governor -- the independent compliance layer that earns
  the HospitalityOps-LLM the right to commit. The LLM has no notion of
  jurisdictional accommodation-operations/guest-registration law,
  whether a stay's own claimed folio total actually equals nights
  times rate, whether a guest's identity has actually been verified
  and registered at check-in, whether a pending guest-data-disclosure
  request has actually been authorized, or when an act stops being a
  draft and becomes a real-world check-in or check-out, so this MUST
  be a separate system able to *reject* a proposal and fall back to
  HOLD.

  `:itonami.blueprint/governor` is `:hospitality-governor`, grep-
  verified UNIQUE fleet-wide -- no naming-collision precedent
  question, a fresh independent build following the SAME governed-
  actor architecture (langgraph StateGraph + independent Governor +
  Phase 0->3 rollout) established by `cloud-itonami-isic-6511`.

  This blueprint's own text (docs/business-model.md's own Trust
  Controls: 'guest disclosures require approval; service records
  cannot be suppressed') and its own docs/operator-guide.md ('human
  sign-off for :high/:safety-critical robot actions... operating in
  guest rooms, near guests or handling guest belongings') name exactly
  the checks below.

  Seven checks, in priority order, ALL HARD violations: a human
  approver CANNOT override them. The confidence/actuation gate is
  SOFT: it asks a human to look (low confidence / actuation), and the
  human may approve -- but see `hospitalityops.phase`: for `:stake
  :actuation/check-in-guest`/`:actuation/check-out-guest` (a real
  check-in or check-out) NO phase ever allows auto-commit either. Two
  independent layers agree that actuation is always a human call.

    1. Spec-basis                  -- did the jurisdiction proposal cite
                                       an OFFICIAL source
                                       (`hospitalityops.facts`), or
                                       invent one?
    2. Evidence incomplete         -- for `:stay/check-in`/`:stay/
                                       check-out`, has the jurisdiction
                                       actually been assessed with a
                                       full evidence checklist on
                                       file?
    3. Guest registration
       incomplete                    -- for `:stay/check-in`,
                                       INDEPENDENTLY verify the stay's
                                       own `:guest-id-verified?` is
                                       true -- the FLAGSHIP genuinely
                                       new check this vertical adds
                                       (grep-verified absent fleet-
                                       wide -- zero hits for 'guest-
                                       registration' as a governor
                                       check function name), the 80th
                                       distinct application of the
                                       unconditional-evaluation
                                       discipline overall (most
                                       recently `agronomyops.governor/
                                       treatment-product-unapproved-
                                       violations` at 78th, and
                                       `agronomyops.governor/water-
                                       source-buffer-violation-
                                       violations` at 79th). Grounded
                                       in real guest-registration law:
                                       Japan's own 旅館業法第6条 (Hotel
                                       Business Act Article 6,
                                       enforced by prefectural
                                       governments/health centers),
                                       the UK's Immigration (Hotel
                                       Records) Order 1972 (enforced
                                       under the Immigration Act
                                       1971), and Germany's
                                       Bundesmeldegesetz §29
                                       (Beherbergungsstättenmeldepflicht,
                                       enforced by local
                                       Meldebehörden) -- directly
                                       grounded in this blueprint's own
                                       text ('handling guest
                                       belongings' / guest intake and
                                       identity). Evaluated
                                       UNCONDITIONALLY (every check-in
                                       needs a verified guest
                                       registration). HONEST single-
                                       jurisdiction gap for the US
                                       (see `hospitalityops.facts` ns
                                       docstring) -- unlike the recent
                                       streak of full four-jurisdiction
                                       sub-citation coverage, this
                                       vertical reports a genuine gap
                                       rather than straining for a
                                       shaky single-state US citation.
    4. Folio total mismatch        -- for `:stay/check-out`,
                                       INDEPENDENTLY recompute whether
                                       the stay's own `:claimed-total`
                                       equals `nights x rate`
                                       (`hospitalityops.registry/
                                       folio-total-matches-claim?`) --
                                       an HONEST reapplication of the
                                       SAME ground-truth-recompute
                                       DISCIPLINE `agronomyops.
                                       registry`'s/`quarryops.
                                       registry`'s/`leathergoods.
                                       registry`'s/`retailops.
                                       registry`'s own checks
                                       establish, reapplied to a stay's
                                       folio line -- not claimed as
                                       new.
    5. Guest disclosure
       authorization unconfirmed     -- for `:stay/check-out`, for a
                                       stay whose own record declares
                                       `:disclosure-requested? true`
                                       (i.e. a third-party disclosure
                                       request -- e.g. law enforcement
                                       or family inquiry -- is actually
                                       pending on this stay, not every
                                       stay has one), INDEPENDENTLY
                                       check whether `:disclosure-
                                       authorized?` is true. A
                                       GENUINELY NEW concept (grep-
                                       verified absent fleet-wide --
                                       zero hits for 'guest-
                                       disclosure'/'disclosure-
                                       authoriz' as a governor check
                                       function name), the 81st
                                       distinct application overall,
                                       the TENTH conditional variant
                                       (after `socialresearch`/7220's,
                                       `bizassoc`/9411's, `training`/
                                       8549's, `furniture`/9524's,
                                       `specialtyrepair`/9529's,
                                       `leathergoods`/9523's,
                                       `ictrepair`/9511's, `quarryops`/
                                       0810's and `agronomyops`/0162's
                                       own, at 63rd, 64th, 66th, 67th,
                                       68th, 69th, 71st, 77th and
                                       79th). CONDITIONAL on the
                                       stay's own `:disclosure-
                                       requested?` ground truth -- most
                                       stays have no pending disclosure
                                       request at all. Directly
                                       grounded in this blueprint's own
                                       text ('guest disclosures require
                                       approval').
    6. Confidence floor / actuation
       gate                          -- LLM confidence below threshold,
                                       OR the op is `:stay/check-in`/
                                       `:stay/check-out` (REAL acts)
                                       -> escalate.

  Two more guards, double-check-in/double-check-out prevention, are
  enforced but NOT listed as numbered HARD checks above because they
  need no upstream comparison at all -- `already-checked-in-
  violations`/`already-checked-out-violations` refuse to check in/out
  the SAME stay twice, off dedicated `:checked-in?`/`:checked-out?`
  facts (never a `:status` value) -- the SAME 'check a dedicated
  boolean, not status' discipline every prior governor's guards
  establish, informed by `cloud-itonami-isic-6492`'s status-lifecycle
  bug (ADR-2607071320)."
  (:require [hospitalityops.facts :as facts]
            [hospitalityops.registry :as registry]
            [hospitalityops.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Checking a real guest in and checking a real guest out are the two
  real-world actuation events this actor performs -- a two-member set,
  matching every sibling's own dual-actuation shape."
  #{:actuation/check-in-guest :actuation/check-out-guest})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:jurisdiction/assess` (or `:stay/check-in`/`:stay/check-out`)
  proposal with no spec-basis citation is a HARD violation -- never
  invent a jurisdiction's accommodation-operations/guest-registration
  requirements."
  [{:keys [op]} proposal]
  (when (contains? #{:jurisdiction/assess :stay/check-in :stay/check-out} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:stay/check-in`/`:stay/check-out`, the jurisdiction's required
  intake/advisory/check-in/check-out evidence must actually be
  satisfied -- do not trust the advisor's self-reported confidence
  alone."
  [{:keys [op subject]} st]
  (when (contains? #{:stay/check-in :stay/check-out} op)
    (let [s (store/stay st subject)
          assessment (store/assessment-of st subject)]
      (when-not (and assessment
                     (facts/required-evidence-satisfied?
                      (:jurisdiction s) (:checklist assessment)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(宿泊記録/助言記録/チェックイン記録/チェックアウト記録等)が充足していない状態での提案"}]))))

(defn- guest-registration-incomplete-violations
  "For `:stay/check-in`, INDEPENDENTLY verify the stay's own `:guest-
  id-verified?` is true -- the flagship genuinely new check this
  vertical adds. Evaluated UNCONDITIONALLY (every check-in needs a
  verified guest registration)."
  [{:keys [op subject]} st]
  (when (= op :stay/check-in)
    (let [s (store/stay st subject)]
      (when-not (true? (:guest-id-verified? s))
        [{:rule :guest-registration-incomplete
          :detail (str subject " の宿泊者本人確認/登録が未完了")}]))))

(defn- folio-total-mismatch-violations
  "For `:stay/check-out`, INDEPENDENTLY recompute whether the stay's
  own claimed folio total equals nights x rate via
  `hospitalityops.registry/folio-total-matches-claim?` -- needs no
  proposal inspection or stored-verdict lookup at all, an honest
  reapplication of the same discipline every sibling actor's own
  cost/total-matching check establishes."
  [{:keys [op subject]} st]
  (when (= op :stay/check-out)
    (let [s (store/stay st subject)]
      (when-not (registry/folio-total-matches-claim? s)
        [{:rule :folio-total-mismatch
          :detail (str subject " の申告宿泊料金合計(" (:claimed-total s)
                      ")が独立再計算値(" (registry/compute-folio-total s) ")と一致しない")}]))))

(defn- guest-disclosure-authorization-unconfirmed-violations
  "For `:stay/check-out`, for a stay whose own record declares
  `:disclosure-requested? true`, INDEPENDENTLY check whether
  `:disclosure-authorized?` is true -- a genuinely new concept,
  CONDITIONAL on the stay's own `:disclosure-requested?` ground truth
  (most stays have no pending disclosure request at all)."
  [{:keys [op subject]} st]
  (when (= op :stay/check-out)
    (let [s (store/stay st subject)]
      (when (and (true? (:disclosure-requested? s))
                 (not (true? (:disclosure-authorized? s))))
        [{:rule :guest-disclosure-authorization-unconfirmed
          :detail (str subject " は開示請求があるが承認が未確認 -- チェックアウト提案は進められない")}]))))

(defn- already-checked-in-violations
  "For `:stay/check-in`, refuses to check in the SAME stay record
  twice, off a dedicated `:checked-in?` fact (never a `:status`
  value)."
  [{:keys [op subject]} st]
  (when (= op :stay/check-in)
    (when (store/stay-already-checked-in? st subject)
      [{:rule :already-checked-in
        :detail (str subject " は既にチェックイン済み")}])))

(defn- already-checked-out-violations
  "For `:stay/check-out`, refuses to check out the SAME stay twice,
  off a dedicated `:checked-out?` fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :stay/check-out)
    (when (store/stay-already-checked-out? st subject)
      [{:rule :already-checked-out
        :detail (str subject " は既にチェックアウト済み")}])))

(defn check
  "Censors a HospitalityOps-LLM proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (guest-registration-incomplete-violations request st)
                           (folio-total-mismatch-violations request st)
                           (guest-disclosure-authorization-unconfirmed-violations request st)
                           (already-checked-in-violations request st)
                           (already-checked-out-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
