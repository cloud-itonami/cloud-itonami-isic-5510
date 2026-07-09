(ns hospitalityops.registry
  "Pure-function check-in + check-out record construction -- an
  append-only accommodation book-of-record draft.

  Like every sibling actor's registry, there is no single international
  reference-number standard for a check-in or check-out record --
  every operator/jurisdiction assigns its own reference format. This
  namespace does NOT invent one; it builds a jurisdiction-scoped
  sequence number and validates the record's required fields, the
  same honest, non-fabricating discipline `hospitalityops.facts` uses.

  `folio-total-matches-claim?` is an HONEST reapplication of the SAME
  ground-truth-recompute DISCIPLINE `agronomyops.registry`'s own
  `dose-matches-claim?`, `quarryops.registry`'s own `royalty-matches-
  claim?`, `leathergoods.registry`'s/`specialtyrepair.registry`'s own
  `parts-cost-matches-claim?` and `retailops.registry`'s own `sale-
  total-matches-claim?` establish (verify a claimed monetary total
  against the entity's own recorded quantity x unit fields), reapplied
  to a stay's folio line rather than a royalty, dose, repair-parts or
  retail-sale line -- not claimed as new code, though no literal code
  is shared (different domain).

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real property-management system. It builds the RECORD an
  operator would keep, not the act of checking a guest in or out itself
  (that is `hospitalityops.operation`'s `:stay/check-in`/`:stay/
  check-out`, always human-gated -- see README `Actuation`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the accommodation operator's act, not this actor's. See README
  `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn compute-folio-total
  "The ground-truth folio total for `stay`'s own `:nights` and `:rate`
  -- a single flat nights x rate calculation, not a full folio engine
  with taxes/incidentals."
  [{:keys [nights rate]}]
  (* (double nights) (double rate)))

(defn folio-total-matches-claim?
  "Does `stay`'s own `:claimed-total` equal the independently
  recomputed `compute-folio-total`? A pure ground-truth check against
  the stay's own permanent fields -- see ns docstring for why this is
  an honest reapplication of the SAME discipline every sibling actor's
  own cost/total-matching check establishes, not a new concept."
  [{:keys [claimed-total] :as stay}]
  (== (double claimed-total) (compute-folio-total stay)))

(defn register-check-in
  "Validate + construct the CHECK-IN registration DRAFT -- the
  accommodation operator's own legal act of checking a real guest into
  a real room. Pure function -- does not touch any real property-
  management system; it builds the RECORD an operator would keep.
  `hospitalityops.governor` independently re-verifies the stay's own
  guest-registration ground truth, and blocks a double-check-in of the
  same record, before this is ever allowed to commit."
  [stay-id jurisdiction sequence]
  (when-not (and stay-id (not= stay-id ""))
    (throw (ex-info "check-in: stay_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "check-in: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "check-in: sequence must be >= 0" {})))
  (let [check-in-number (str (str/upper-case jurisdiction) "-CHI-" (zero-pad sequence 6))
        record {"record_id" check-in-number
                "kind" "check-in-draft"
                "stay_id" stay-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "check_in_number" check-in-number
     "certificate" (unsigned-certificate "CheckIn" check-in-number check-in-number)}))

(defn register-check-out
  "Validate + construct the CHECK-OUT registration DRAFT -- the
  accommodation operator's own legal act of checking a real guest out
  of a real room (triggering folio settlement). Pure function -- does
  not touch any real property-management system; it builds the RECORD
  an operator would keep. `hospitalityops.governor` independently
  re-verifies the stay's own folio/disclosure ground truth, and blocks
  a double-check-out of the same record, before this is ever allowed
  to commit."
  [stay-id jurisdiction sequence]
  (when-not (and stay-id (not= stay-id ""))
    (throw (ex-info "check-out: stay_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "check-out: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "check-out: sequence must be >= 0" {})))
  (let [check-out-number (str (str/upper-case jurisdiction) "-CHO-" (zero-pad sequence 6))
        record {"record_id" check-out-number
                "kind" "check-out-draft"
                "stay_id" stay-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "check_out_number" check-out-number
     "certificate" (unsigned-certificate "CheckOut" check-out-number check-out-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
