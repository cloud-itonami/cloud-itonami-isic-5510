(ns hospitalityops.phase
  "Phase 0->3 staged rollout for the community-accommodation actor.

    Phase 0  read-only        -- no writes, still governor-gated.
    Phase 1  assisted-intake  -- stay intake allowed, every write
                                 needs human approval.
    Phase 2  assisted-assess  -- adds jurisdiction assessment writes,
                                 still approval.
    Phase 3  supervised auto  -- governor-clean, high-confidence
                                 `:stay/intake` (no guest-facing risk
                                 yet) may auto-commit. `:stay/check-
                                 in`/`:stay/check-out` NEVER auto-
                                 commit, at any phase.

  `:stay/check-in`/`:stay/check-out` are deliberately ABSENT from
  every phase's `:auto` set, including phase 3 -- a permanent
  structural fact, not a rollout milestone still to come. Checking a
  real guest in and checking a real guest out are the two real-world
  acts this actor performs; both are always a human accommodation
  operator's call. `hospitalityops.governor`'s `:actuation/check-in-
  guest`/`:actuation/check-out-guest` high-stakes gate enforces the
  same invariant independently -- two layers, not one, agree on this.
  Like every prior sibling's phase 3 `:auto` set, this domain has only
  ONE member (`:stay/intake`) -- no separate no-guest-facing-risk
  'file' lifecycle distinct from the stay itself.")

(def read-ops  #{})
(def write-ops #{:stay/intake :jurisdiction/assess :stay/check-in :stay/check-out})

;; NOTE the invariant: `:stay/check-in`/`:stay/check-out` are members
;; of `write-ops` (governor-gated like any write) but are NEVER
;; members of any phase's `:auto` set below. Do not add them there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"       :writes #{}                                                              :auto #{}}
   1 {:label "assisted-intake" :writes #{:stay/intake}                                                   :auto #{}}
   2 {:label "assisted-assess" :writes #{:stay/intake :jurisdiction/assess}                               :auto #{}}
   3 {:label "supervised-auto" :writes write-ops
      :auto #{:stay/intake}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:stay/check-in`/`:stay/check-out` are never auto-eligible at any
    phase, so they always escalate once the governor clears them (or
    hold if the governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Hospitality Governor verdict to a base disposition before the
  phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
