# cloud-itonami-5510

Open Business Blueprint for **ISIC Rev.5 5510**: accommodation operations — guest intake, housekeeping, service and checkout for small properties.

This repository designs a forkable OSS business for community accommodation operations:
run by a qualified operator so a community keeps its own operating records
instead of renting a closed SaaS.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work**. Here a service robot performs cleaning, restocking and concierge delivery in guest areas under an actor that proposes
actions and an independent **Hospitality Governor** that gates them. The governor never
dispatches hardware itself; `:high`/`:safety-critical` actions (such as
operating in guest rooms, near guests or handling guest belongings) require human sign-off.

## Core Contract

```text
intake + identity + identity records
        |
        v
Advisor -> Hospitality Governor -> proceed, hold, or human approval
        |
        v
robot actions (gated) + operating records + audit ledger
```

No automated advice can dispatch a robot action the governor refuses, suppress
an operating record, or disclose sensitive data without governor approval and
audit evidence.

A live sample of the operator console (robotics safety console, shared template) is rendered in [docs/samples/operator-console.html](docs/samples/operator-console.html) — pure-data HTML output of `kotoba.robotics.ui`.

## Capability layer

Resolves via [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry)
(ISIC `5510`). Required capabilities:

- `:robotics`
- `:identity`
- `:forms`
- `:dmn`
- `:bpmn`
- `:audit-ledger`

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
