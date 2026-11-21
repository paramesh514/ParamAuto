# ParamCar knowledge bundle (OKF v0.2)

Open Knowledge Format bundle for the comma / StarPilot / ParamAuto project.
Read this index first; each entry is one concept with a one-sentence description.

## Galaxy (StarPilot)
- [Galaxy endpoint reference](galaxy/endpoints.md) — Every HTTP route in the SG Galaxy server verified against a live device (133 endpoints cataloged), grouped by area with methods and payload notes.
- [ParamAuto typed-API map](galaxy/typed-api-map.md) — How each app data need maps to a typed Galaxy endpoint, with payload shapes and known gaps.
- [StarPilot branch state](galaxy/branch-state.md) — stable (production, 7.0) and testing (7.0.1) branch state; Dom frozen.
- [Galaxy remote access and tunnel state](galaxy/access-and-tunnel.md) — Verified base URLs (LAN IP on port 8082, `172.31.254.1:8082`, `https://galaxy.firestar.link/<device-slug>`), local session auth, and tunnel status.
- [Live Galaxy Web Functionality Audit](galaxy/functionality-audit-2026-10-31.md) — Comprehensive breakdown of all 14 capability areas and 97 frontend modules inspected on a live device.

## Companion apps
- [Official companion Android app — APK analysis](companion/android-companion-apk.md) — Identity, architecture, and transports of link.firestar.companion v1.1.30.
- [Galaxy BLE bridge protocol](companion/ble-bridge-protocol.md) — The Bluetooth LE protocol the companion apps use to tunnel Galaxy API requests.

## ParamAuto
- [Startup Analysis, Phone vs. Auto UI Comparison & Features to Implement](feature-to-implement.md) — Root-cause analysis of startup bottlenecks, UI layer comparison between Phone and Android Auto, and prioritized P0–P2 implementation roadmap.
- [Feature Gap Analysis: Galaxy Web vs ParamAuto](paramauto/feature-gap-analysis.md) — Side-by-side capability matrix comparing Galaxy web features with ParamAuto native capabilities and prioritization.
- [Code Flow, Concurrency, Hang Bug & Optimization Review](paramauto/code-flow-and-optimization-review.md) — Deep architectural review of all 43 Kotlin source files, thread pools, race conditions, hang bugs, and applied fixes.
- [Feature Specification & Implementation Design](ParamAuto.spec.md) — Comprehensive design, wireframes, and Kotlin implementation for Nav, HUD, Settings & Models.
- [Architecture](paramauto/architecture.md) — The standing rule: comma → phone → Android Auto; transports and data sources.
- [Build history](paramauto/build-history.md) — What each commit added, with known gaps and caveats.
- [Search design](paramauto/search-design.md) — Button-only, cache-first search with Home/Office and most-visited places, built to conserve the Mapbox quota.
- [Android CLI build setup](paramauto/android-cli-build.md) — Android CLI installed in the assistant environment; the network-permission blocker and the feedback filed about it.

## Related local knowledge (outside this bundle)
- (local paths omitted)
- (local paths omitted)
