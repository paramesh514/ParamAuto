---
okf_version: "0.2"
type: State Report
title: "StarPilot branch state — stable (production) and testing"
description: "As of 2026-11-21: Dom is frozen; production is the stable branch (StarPilot 7.0); testing carries 7.0.1. Both branches expose the same Galaxy contract surface ParamAuto uses."
tags: [starpilot, branches, galaxy, stable, testing]
status: stable
trust_tier: authoritative
generated: { by: "jarvis", at: "2026-11-21" }
verified: { by: "jarvis:source-analysis", at: "2026-11-21" }
---

# StarPilot branch state

_Updated 2026-11-21. The previous version of this page tracked the Dom branch
as of 2026-11-04; Dom has since been frozen and superseded._

## The branches that matter

- **`stable` — production.** StarPilot **7.0**. Tip at last check:
  `008b0cbcf` (2026-11-21). A direct descendant of the Dom baseline
  (Dom + 218 commits).
- **`testing` — pre-release.** StarPilot **7.0.1**. Tip at last check:
  `81dc63dac` (2026-11-21). Dom + 223 commits.
- **`Dom` — frozen.** Tip `2c9a556f5` (2026-11-17, a "Dom → testing"
  migration commit). No longer moves.
- A branch literally named `StarPilot` also exists upstream, but it is a
  **different, sunnypilot-derived codebase** with an incompatible Galaxy
  API. It is not production and ParamAuto does not work against it.

## Consequences for ParamAuto

- The Galaxy contract surface ParamAuto depends on — device state,
  navigation status/search/actions, settings pages, models, networks,
  recordings — is **byte-identical between `stable` and `testing`**
  (verified by diffing the two branches over those paths, 2026-11-21).
  ParamAuto works the same against a production (`stable`) comma as
  against a `testing` one.
- Galaxy serves typed JSON endpoints exclusively. No legacy
  `/api/params/all` route exists.
- A nightly watch tracks both `stable` and `testing` for changes to that
  contract surface; any surface change is analyzed for ParamAuto
  compatibility before it can affect the app.

## Related
- [endpoints.md](endpoints.md) · [typed-api-map.md](typed-api-map.md) · [access-and-tunnel.md](access-and-tunnel.md)
