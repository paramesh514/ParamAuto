---
okf_version: "0.2"
type: Build Log
title: "ParamAuto build history (2026-10-27)"
description: "The three commits of 2026-10-27 — SG UI features + BLE client, companion-style transport, typed APIs + search redesign — plus the galaxy-cloud-login branch, with gaps and caveats."
tags: [paramauto, changelog, starpilot]
status: stable
trust_tier: authoritative
generated: { by: "jarvis", at: "2026-10-27" }
---

# ParamAuto build history

Repo: `paramesh514/ParamAuto` (public), branch `main`. All commits below were
written on the assistant VM, which has **no Android SDK — none of this was compiled
locally**; Param builds in Android Studio.

## 75b6331cb — SG UI features + Bluetooth (Param: "add them + steal Bluetooth")
- Bottom **route strip** with live route progress on the AA surface (`RouteStrip.kt`, `RouteProgress.kt`).
- Maneuver card now shows **total remaining distance/time + ETA** computed from route progress.
- **Bookmark** action on the driving screen (app-local timestamped bookmarks; no SG endpoint exists for comma-recording sync).
- Active-nav strip: Search / Bookmark / **End - \<destination\>**.
- **Bluetooth**: Kotlin port of the iOS companion's Galaxy BLE bridge (`GalaxyBle.kt` wire codec, `BleBridgeClient.kt` GATT client, Settings dialog to save the pairing key + test connection, BLE permissions).

## af469747f — Companion-style transport (Param: "add same transport")
- `GalaxyWebActivity`: WebView hosting the Galaxy UI from the comma (LAN or hotspot gateway).
- `StarPilotBleBridge`: `@JavascriptInterface` named `StarPilotBLE` (`request`/`cancel`), backed by `BleBridgeClient`.
- `assets/bluetooth-fetch.js`: the official companion's fetch override, **byte-identical**, injected in BLE mode (same 501 blocklist, same runtime.json rewrite).
- Entry: Settings → "🌐 Galaxy (Web)".

## a4119ea4c — Typed Galaxy APIs + search redesign
- `/api/params/all` replaced by SG typed endpoints (see the typed-API map) with per-endpoint auto-detection and a legacy fallback for pre-SG builds; Galaxy Cloud gets a "typed APIs need LAN/hotspot/BLE" diagnostic.
- Navigation actions via `POST /api/navigation/action` with revision + one 409 retry.
- Search: button-only, cache-first, Home/Office + most-visited (see search design doc).

## galaxy-cloud-login (2026-10-28, merged 2026-10-30) — Galaxy password login for Cloud mode
- Cloud mode on SG now works: `GalaxyCloudSession` (in `SgGalaxyClient.kt`) signs in via `POST /api/auth/login` with the saved Galaxy password, persists the `galaxy_session` token in the app's private preferences, and sends it as a Cookie on cloud-host requests only — never to LAN/hotspot/BLE endpoints. A 401 triggers exactly one re-login and one retry; a rejected sign-in surfaces a "check the Galaxy password in Settings" diagnostic and stops (with a failure cooldown, so no retry loops).
- Settings → "🌐 Configure Galaxy Cloud Session" gained a masked **Galaxy password** field (Cloud mode only, labeled as such); saving a new password drops the old token and re-probes Cloud SG mode.
- Cloud SG diagnostics now distinguish the cases: no password saved → "Galaxy Cloud on SG needs the Galaxy password … or use LAN, hotspot, or Bluetooth"; password saved → cloud typed APIs are attempted with sign-in.
- `SgGalaxyClient.baseUrlFor` returns the real cloud base for Galaxy Cloud endpoints (previously it mangled the cloud URL into an `http://…:8082` LAN shape), so navigation actions can also run over Cloud.
- Compiled and merged: Param merged PR #1 into main on 2026-10-30 (f7dd9e11) after his own fixes; the VM build toolchain was solved the same day (see android-cli-build.md).

## 2026-10-30 — review fixes, Galaxy URL + discovery, Mapbox tiles, token setting
- ae95d248: the full code-review Fix 1–16 (polyline-encoder precedence bug, AA double-drawn route, search/directions caching and dedup, static-image LRU with zoom-scaled refetch gates, usage-counters screen, offline Phoenix tile pack, WebView asset caching, cleanup bundle). Galaxy Cloud switching lock fixed — an auto-saved cloud URL no longer pins the app to Cloud; LAN discovery stays first, Cloud is the fallback. Device-specific Galaxy URL preserved end-to-end (firestar links route by slug path to one comma; the app previously collapsed every endpoint to the bare host, which cannot log in to a device). Navigation send failures now surface as visible errors with reasons instead of failing silently.
- Mapbox tiles are primary on both screens (phone: hand-built streets-v8 vector style; Android Auto: static images with the CARTO stitch as fallback) after a same-day CARTO-on-both-screens build was reverted at Param's word. A manual Mapbox token setting was added to Settings: the SG typed API returns hasKey only and never the token value, so extraction from the comma is impossible on SG and the app runs on a user-pasted token.
- 1cbef71b (Param): End Route button sticks — a stale comma destination no longer reactivates the route.
- c4d9bdad (Param): removed the tracked APK binary from the repo.

## Known gaps (do not paper over)
- **Live speed limit is not exposed by any SG typed endpoint** → HUD limit unavailable on SG.
- **SLC offsets unavailable on SG**: `/api/settings/pages/slc` rows carry label+value but no key, so Offset1..7 can't be mapped safely. Possible follow-up: label/order-based mapping (rows are band-ordered) — not done.
- Galaxy web assets are **not cached** in the WebView mode (the official app caches them); the page shell still needs an IP path to the comma.
- BLE has nothing to talk to until the comma runs a build with the BLE bridge service.
- The phone-UI search in MainActivity still searches directly (the cache redesign covers the AA SearchScreen).

## Related
- [architecture.md](architecture.md) · [search-design.md](search-design.md) · [../galaxy/typed-api-map.md](../galaxy/typed-api-map.md)
