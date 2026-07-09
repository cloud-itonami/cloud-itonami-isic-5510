# cloud-itonami-isic-5510

Open Business Blueprint for **ISIC Rev.5 5510**: accommodation
operations -- guest intake, housekeeping, service and checkout for
small properties.

This repository publishes a community-accommodation actor -- stay
intake, per-jurisdiction accommodation-operations/guest-registration
regulatory assessment, guest check-in and check-out -- as an OSS
business that any qualified operator can fork, deploy, run, improve
and sell, so a small hotel or guesthouse never surrenders guest and
folio data to a closed PMS SaaS.

Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
every prior actor in this fleet (90 prior actors) -- here it is
**HospitalityOps-LLM ⊣ Hospitality Governor**. This blueprint's own
`:itonami.blueprint/governor` keyword, `:hospitality-governor`, is a
UNIQUE keyword fleet-wide (grep-verified: no other blueprint declares
it) -- a fresh, independent build.

> **Why an actor layer at all?** An LLM is great at drafting a stay
> summary, normalizing records, and checking whether a claimed folio
> total actually equals a stay's own recorded nights times rate -- but
> it has **no notion of which jurisdiction's accommodation-operations/
> guest-registration law is official, no license to check a real guest
> in or out, and no way to know on its own whether a guest's identity
> has actually been verified and registered or whether a pending
> guest-data-disclosure request has actually been authorized**.
> Letting it check a guest in or out directly invites fabricated
> regulatory citations, a folio mismatch being charged to a guest, an
> unregistered guest occupying a room, and a disclosure request being
> honored without authorization -- exposing the operator to real
> regulatory and privacy liability. This project seals the
> HospitalityOps-LLM into a single node and wraps it with an
> independent **Hospitality Governor**, a human **approval workflow**,
> and an immutable **audit ledger**.

## Scope: what this actor does and does not do

This actor covers stay intake through accommodation-operations/guest-
registration regulatory assessment, guest check-in and check-out. It
does **not**, by itself, hold any operating license required to run an
accommodation establishment in a given jurisdiction, and it does not
claim to. It also does not perform the actual physical housekeeping or
concierge work itself, or judge service quality --
`hospitalityops.registry/folio-total-matches-claim?` is a pure
ground-truth recompute against the stay's own recorded fields, not a
service-quality judgment. Whoever deploys and operates a live instance
(a qualified accommodation operator/front-desk manager) supplies any
jurisdiction-specific license, the real property-management-system/
booking-channel integration and the real payment-processing
integrations, and bears that jurisdiction's liability -- the software
supplies the governed, spec-cited, audited execution scaffold so that
operator does not have to build the compliance layer from scratch.

### Actuation

**Checking a real guest in and checking a real guest out are never
autonomous, at any phase, by construction.** Two independent layers
enforce this (`hospitalityops.governor`'s `:actuation/check-in-guest`/
`:actuation/check-out-guest` high-stakes gate and `hospitalityops.
phase`'s phase table, which never puts either op in any phase's
`:auto` set) -- see `hospitalityops.phase`'s docstring and
`test/hospitalityops/phase_test.clj`'s `stay-check-in-never-auto-at-
any-phase`/`stay-check-out-never-auto-at-any-phase`. The actor may
draft, check and recommend; a human accommodation operator is always
the one who actually checks a guest in or out. Grounded directly in
this blueprint's own `docs/business-model.md` Trust Controls text
("guest disclosures require approval; service records cannot be
suppressed") -- a genuine DUAL-actuation shape, applied SEQUENTIALLY
to the SAME stay record (check in first, check out later), matching
`freightops`/4920's, `quarryops`/0810's and `agronomyops`/0162's own
sequential shape rather than `retailops`/4711's own alternative-kind
shape.

## The core contract

```
stay intake + jurisdiction facts (hospitalityops.facts, spec-cited)
        |
        v
   ┌───────────────────────┐   proposal      ┌───────────────────────┐
   │ HospitalityOps-LLM    │ ─────────────▶ │ Hospitality Governor          │  (independent system)
   │ (sealed)              │  + citations    │ spec-basis · evidence-       │
   └───────────────────────┘                 │ incomplete · guest-           │
          │                 commit ◀┼ registration-incomplete (FLAGSHIP     │
          │                         │ NEW) · folio-total-mismatch           │
    record + ledger        escalate ┼ (ground-truth) · guest-disclosure-        │
          │              (ALWAYS for│ authorization-unconfirmed (conditional,   │
          │       :actuation/check- │ NEW) · already-checked-in ·               │
          │       in-guest/         │ already-checked-out                       │
          │       :actuation/check- │                                            │
          │       out-guest}         │                                            │
          ▼                          └───────────────────────┘
      human approval
```

**The HospitalityOps-LLM never checks a guest in or out the
Hospitality Governor would reject, and never does so without a human
sign-off.** Hard violations (fabricated regulatory requirements;
unsupported evidence; an incomplete guest registration; a folio-total
mismatch; an unauthorized guest-disclosure request on a disclosure-
requested stay; a double check-in/check-out) force **hold** and
*cannot* be approved past; a clean check-in/check-out proposal still
always routes to a human.

## Run

```bash
clojure -M:dev:run     # walk two clean check-in+check-out lifecycles (no disclosure request, disclosure requested-and-authorized), plus four HARD-hold cases, through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance · facts coverage
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a service robot performs
cleaning, restocking and concierge delivery in guest areas, under the
actor, gated by the independent **Hospitality Governor**. The governor
never dispatches hardware itself; `:high`/`:safety-critical` actions
(such as operating in guest rooms, near guests or handling guest
belongings) require human sign-off.

## Open business

This repository is not only source code. It is a public, forkable
business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, Hospitality Governor, check-in/check-out draft records, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an
open business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`5510`). This vertical's guest/folio records are practice-specific
rather than a shared cross-operator data contract, so
`hospitalityops.*` runs on the generic robotics/identity/forms/dmn/
bpmn/audit-ledger stack only -- no bespoke domain capability lib to
reference at all (unlike `retailops`/4711's own `kotoba-lang/retail`
and `freightops`/4920's own `kotoba-lang/logistics` integrations;
`kotoba-lang/com-opera-pms` and `kotoba-lang/com-shares-pms` are
verified clean-room PMS-protocol compatibility shims for specific
commercial vendors, not a bespoke domain capability library, matching
`quarryops`/0810's and `agronomyops`/0162's own investigated-and-
ruled-out precedent).

## Layout

| File | Role |
|---|---|
| `src/hospitalityops/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + check-in AND check-out history (dual history). The double-actuation guard checks dedicated `:checked-in?`/`:checked-out?` booleans rather than a `:status` value |
| `src/hospitalityops/registry.cljc` | Check-in/check-out draft records, plus `folio-total-matches-claim?` -- an honest reapplication of the SAME ground-truth-recompute discipline every sibling actor's own cost/total-matching check establishes |
| `src/hospitalityops/facts.cljc` | Per-jurisdiction accommodation-operations AND guest-registration catalog with an official spec-basis citation per entry, honest coverage reporting -- three of four seeded jurisdictions have a guest-registration sub-citation here (an honest single-jurisdiction gap for the US) |
| `src/hospitalityops/hospitalityopsllm.cljc` | **HospitalityOps-LLM** -- `mock-advisor` ‖ `llm-advisor`; intake/jurisdiction-assessment/check-in/check-out proposals |
| `src/hospitalityops/governor.cljc` | **Hospitality Governor** -- 5 HARD checks (spec-basis · evidence-incomplete · guest-registration-incomplete, FLAGSHIP NEW, the 80th unconditional-evaluation-discipline grounding · folio-total-mismatch · guest-disclosure-authorization-unconfirmed, CONDITIONAL, the 81st grounding) + 2 double-actuation guards + 1 soft (confidence/actuation gate) |
| `src/hospitalityops/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted assess → supervised (check-in/check-out always human; stay intake is the ONLY auto-eligible op, no direct guest-facing risk) |
| `src/hospitalityops/operation.cljc` | **OperationActor** -- langgraph StateGraph |
| `src/hospitalityops/sim.cljc` | demo driver |
| `test/hospitalityops/*_test.clj` | governor contract · phase invariants · store parity · registry conformance · facts coverage |

## Business-process coverage (honest)

This actor covers stay intake through accommodation-operations/guest-
registration regulatory assessment, guest check-in and check-out --
the core governed lifecycle this blueprint's own `docs/
business-model.md` names in its Offer:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Stay intake + per-jurisdiction evidence checklisting, HARD-gated on an official spec-basis citation (`:stay/intake`/`:jurisdiction/assess`) | Real property-management-system/booking-channel integration, real service-quality judgment (see `hospitalityops.facts`'s docstring) |
| Guest check-in, HARD-gated on full evidence and a verified guest registration, plus a double-check-in guard (`:actuation/check-in-guest`) | |
| Guest check-out, HARD-gated on full evidence, a matching folio claim and (when applicable) an authorized guest-disclosure request, plus a double-check-out guard (`:actuation/check-out-guest`) | |
| Immutable audit ledger for every intake/assessment/check-in/check-out decision | |

Extending coverage is additive: add the next gate (e.g. a
housekeeping-completion-verification check) as its own governed op
with its own HARD checks and tests, following the SAME "an independent
governor re-verifies against the actor's own records before any
real-world act" pattern this repo's flagship ops already establish.

## Jurisdiction coverage (honest)

`hospitalityops.facts/coverage` reports how many requested
jurisdictions actually have an official spec-basis in
`hospitalityops.facts/catalog` -- currently 4 seeded (JPN, USA, GBR,
DEU) out of ~194 jurisdictions worldwide. This is a starting catalog
to prove the governor contract end-to-end, not a claim of global
coverage. Adding a jurisdiction is additive: one map entry in
`hospitalityops.facts/catalog`, citing a real official source -- never
fabricate a jurisdiction's requirements to make coverage look bigger.
Note that the guest-registration sub-citation is an HONEST
single-jurisdiction gap: unlike the recent streak of full four-
jurisdiction sub-citations (`quarryops`/0810's own blast-safety,
`agronomyops`/0162's own water-buffer), the US genuinely lacks a
single controlling federal guest-registration statute (fragmented
state-by-state "innkeeper" law instead), so no US guest-registration
sub-citation is asserted -- reported honestly rather than straining
for a shaky single-state citation.

## Maturity

`:implemented` -- `HospitalityOps-LLM` + `Hospitality Governor` run as
real, tested code (see `Run` above), promoted from the originally-
published `:blueprint`-tier scaffold, following the SAME governed-
actor architecture as the 90 other prior actors across this fleet,
with its own distinct, independently-named governor. See
`docs/adr/0001-architecture.md` for the history and design.

## License

Code and implementation templates are AGPL-3.0-or-later.
