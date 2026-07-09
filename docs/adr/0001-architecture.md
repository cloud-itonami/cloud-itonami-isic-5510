# ADR-0001: HospitalityOps-LLM ⊣ Hospitality Governor architecture

## Status

Accepted. `cloud-itonami-isic-5510` promoted from `:blueprint` to
`:implemented` in the `kotoba-lang/industry` registry.

## Context

`cloud-itonami-isic-5510` publishes an OSS business blueprint for
community accommodation operations (guest intake, housekeeping,
service and checkout for small properties). Like every prior actor in
this fleet, the blueprint alone is not an implementation: this ADR
records the governed-actor architecture that promotes it to real,
tested code, following the same langgraph StateGraph + independent
Governor + Phase 0→3 rollout pattern established by
`cloud-itonami-isic-6511` (life insurance) and applied across 90 prior
siblings, most recently `cloud-itonami-isic-0162` (community agronomy
support).

Like `quarryops`/0810 and `agronomyops`/0162, this vertical has no
bespoke domain capability library in `kotoba-lang` to wrap. Two
`kotoba-lang` repos matched a property-management-system search
(`com-opera-pms`, `com-shares-pms`), but both are verified to be
clean-room API-COMPATIBILITY shims for specific commercial vendor
protocols (relocated from `etzhayyim/root/20-actors/*-compat` per
ADR-2606302300's org-taxonomy library-placement rule), not a
bespoke domain capability library this actor's own domain logic could
call into. This build returns to self-contained domain logic, the
same pattern the majority of this fleet's actors use.

This blueprint's own `:itonami.blueprint/governor` keyword,
`:hospitality-governor`, is grep-verified UNIQUE fleet-wide -- no
naming-collision precedent question, a fresh independent build.

## Decision

### Decision 1: fresh governor identity, no reuse precedent needed

`:hospitality-governor` is grep-verified unique across every
blueprint.edn in this fleet. This build follows the SAME governed-
actor architecture as every prior actor, but with its own distinct
governor identity.

### Decision 2: dual-actuation shape, SEQUENTIAL on the SAME `stay` entity

This blueprint's own operating states ("intake : reserve : check-in :
serve : check-out : audit") name two real-world acts: checking a guest
in and checking a guest out. These apply SEQUENTIALLY to the SAME
`stay` entity -- check in first, check out later -- matching
`freightops`/4920's, `quarryops`/0810's and `agronomyops`/0162's own
sequential shape rather than `retailops`/4711's own alternative-kind
shape. `high-stakes` is `#{:actuation/check-in-guest :actuation/
check-out-guest}`.

### Decision 3: `folio-total-matches-claim?` -- an honest reapplication of the ground-truth-recompute discipline

`hospitalityops.registry/folio-total-matches-claim?` (stay's own
claimed folio total vs. nights x rate) applies the SAME discipline
`agronomyops.registry`'s own `dose-matches-claim?`, `quarryops.
registry`'s own `royalty-matches-claim?`, `leathergoods.registry`'s/
`specialtyrepair.registry`'s own `parts-cost-matches-claim?` and
`retailops.registry`'s own `sale-total-matches-claim?` establish --
verify a claimed monetary total against the entity's own recorded
fields, independent of proposal inspection. No literal code is shared
(different domain), but the discipline is the same, documented as
such rather than claimed as a novel invention.

### Decision 4: entity and op shape

The primary entity is a `stay`. Four ops: `:stay/intake` (directory
upsert, no guest-facing risk), `:jurisdiction/assess` (per-
jurisdiction accommodation-operations/guest-registration evidence
checklist, never auto), `:stay/check-in` (POSITIVE, high-stakes), and
`:stay/check-out` (POSITIVE, high-stakes).

### Decision 5: `guest-registration-incomplete?` -- the 80th unconditional-evaluation grounding, the FLAGSHIP genuinely new check

Grep-verified absent fleet-wide (zero hits for `guest-registration` as
a governor check name). Grounded in real guest-registration law:
Japan's own 旅館業法第6条 (Hotel Business Act Article 6, enforced by
prefectural governments/health centers), the UK's Immigration (Hotel
Records) Order 1972 (enforced under the Immigration Act 1971), and
Germany's Bundesmeldegesetz §29 (Beherbergungsstättenmeldepflicht,
enforced by local Meldebehörden). Evaluated UNCONDITIONALLY on every
`:stay/check-in` (every check-in needs a verified guest registration).
**Honest single-jurisdiction gap for the US**: unlike the recent
streak of full four-jurisdiction sub-citations (`quarryops`/0810's own
blast-safety, `agronomyops`/0162's own water-buffer), guest
registration in the US is governed by fragmented state-by-state
"innkeeper" statutes rather than one uniform national law -- no US
guest-registration sub-citation is asserted, reported honestly rather
than straining for a shaky single-state citation to force apparent
full coverage.

### Decision 6: `guest-disclosure-authorization-unconfirmed?` -- the 81st unconditional-evaluation grounding, the TENTH conditional variant

Before writing this check, every prior sibling's governor namespace
was grepped for any check function named `guest-disclosure` or
`disclosure-authoriz` -- zero hits, confirming this is a genuinely new
concept. This is the TENTH conditional variant (after
`socialresearch`/7220's, `bizassoc`/9411's, `training`/8549's,
`furniture`/9524's, `specialtyrepair`/9529's, `leathergoods`/9523's,
`ictrepair`/9511's, `quarryops`/0810's and `agronomyops`/0162's own, at
63rd, 64th, 66th, 67th, 68th, 69th, 71st, 77th and 79th) --
CONDITIONAL on the stay's own `:disclosure-requested?` ground truth:
most stays have no pending third-party disclosure request at all
(e.g. law enforcement or family inquiry), only a stay with one
actually needs authorization confirmation. Directly grounded in this
blueprint's own text ("guest disclosures require approval").

### Decision 7: dedicated double-actuation-guard booleans

`:checked-in?`/`:checked-out?` are dedicated booleans on the `stay`
record, never a single `:status` value -- the same discipline every
prior governor's guards establish, informed by
`cloud-itonami-isic-6492`'s real status-lifecycle bug
(ADR-2607071320).

### Decision 8: Store protocol, MemStore + DatomicStore parity

`hospitalityops.store/Store` is implemented by both `MemStore` (atom-
backed, default for dev/tests/demo) and `DatomicStore` (`langchain.
db`-backed), proven to satisfy the same contract in
`test/hospitalityops/store_contract_test.clj`.

### Decision 9: no bespoke domain capability lib; a genuine `blueprint.edn` field-sync gap found and fixed

Verified explicitly this session: `com-opera-pms`/`com-shares-pms` are
clean-room PMS-protocol compatibility shims (vendor integration
adapters), not a domain capability library this actor's own logic
could call into -- matching `quarryops`/0810's and `agronomyops`/
0162's own investigated-and-ruled-out precedent rather than
`retailops`/4711's and `freightops`/4920's own genuine capability-
library wraps. This repo's `blueprint.edn` had the correct
`:required-technologies` matching the `kotoba-lang/industry`
registry's own entry for `"5510"` exactly, but was MISSING
`:optional-technologies [:optimization]` entirely (matching
`agronomyops`/0162's own field-sync gap pattern) -- found by reading
the full registry entry carefully and fixed alongside the `:maturity`
flip in the same commit.

### Decision 10: mock + LLM advisor pair

`hospitalityops.hospitalityopsllm` provides `mock-advisor`
(deterministic, default everywhere -- the actor graph and governor
contract run offline) and `llm-advisor` (backed by `langchain.model/
ChatModel`, with a defensive EDN-proposal parser so a malformed LLM
response degrades to a safe low-confidence noop rather than ever
auto-checking a guest in or out).

## Alternatives considered

- **An unconditional guest-disclosure-authorization check** (applying
  to every check-out regardless of whether a disclosure request is
  actually pending). Rejected: most stays have no pending disclosure
  request at all -- forcing the check onto every check-out would
  fabricate a requirement.
- **Fabricating a US guest-registration citation** to match the
  pattern of the recent full-coverage streak. Rejected: the same
  honesty discipline that forbids fabricating coverage also forbids
  straining for a shaky citation just to look complete -- the US
  genuinely lacks a single controlling federal guest-registration
  statute (fragmented state "innkeeper" law instead).
- **Treating `com-opera-pms`/`com-shares-pms` as this vertical's
  capability library.** Considered and explicitly ruled out: they are
  vendor-protocol compatibility shims, not domain capability logic --
  this build correctly returns to self-contained domain logic rather
  than forcing a false capability-library integration.

## Consequences

- 91st actor in this fleet (90 implemented before this build).
- Establishes two genuinely NEW unconditional-evaluation-discipline
  checks: `guest-registration-incomplete?` (FLAGSHIP, 80th distinct
  application overall) and `guest-disclosure-authorization-
  unconfirmed?` (81st distinct application overall, the TENTH
  conditional variant).
- `MemStore` ‖ `DatomicStore` parity is proven by
  `test/hospitalityops/store_contract_test.clj`.
- 39 tests / 175 assertions pass; lint is clean; the demo
  (`clojure -M:dev:run`) walks two clean check-in+check-out lifecycles
  (no disclosure request, disclosure requested-and-authorized), plus
  four HARD-hold scenarios, end-to-end.
- `blueprint.edn` needed a genuine field-sync fix this time (a missing
  `:optional-technologies [:optimization]` key) in addition to the
  `:maturity` flip -- the same gap pattern `agronomyops`/0162 found.

## References

- `cloud-itonami-isic-6511/docs/adr/0001-architecture.md` (origin of
  the general governed-actor architecture pattern)
- `cloud-itonami-isic-0162/docs/adr/0001-architecture.md` (most recent
  prior sibling, template for this ADR's structure)
- Hotel Business Act (旅館業法) Article 6 (Japan)
- Immigration (Hotel Records) Order 1972; Regulatory Reform (Fire
  Safety) Order 2005 (UK)
- Bundesmeldegesetz (BMG) §29; Gaststättengesetz (Germany)
- Hotel and Motel Fire Safety Act of 1990, 15 U.S.C. §2225 (US)
