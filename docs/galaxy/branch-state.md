---
okf_version: "0.2"
type: State Report
title: "StarPilot branch state — Dom branch live updates & commit line"
description: "Validated 2026-11-04 on live device at http://192.168.68.16:8082: Dom branch running build 7.0 (v0.11.3) with installed commit cb707259 and downloaded update c08b3249."
tags: [starpilot, branches, galaxy, dom, updater]
status: stable
trust_tier: authoritative
generated: { by: "jarvis", at: "2026-11-04" }
verified: { by: "jarvis:live-device-check", at: "2026-11-04" }
---

# StarPilot branch state

## Facts (Checked Live on Device http://192.168.68.16:8082, 2026-11-04)

- **Active Branch:** `Dom` (`StarPilotHQ/openpilot`).
- **Installed Software:** Version `0.11.3` (Display Version: `7.0`), Commit [`cb707259`](https://github.com/StarPilotHQ/openpilot/commit/cb707259be2cf0895e580fdde26ee8d4c6750534) (*"Refresh inventory receipt for schema qualification tests"*, 2026-11-04 18:07:28 -06:00).
- **Prepared / Downloaded Update:** Commit [`c08b3249`](https://github.com/StarPilotHQ/openpilot/commit/c08b3249b6313dbf0214814c6859193898dca94d) (*"Align Subaru engagement test with transmitted steering history"*, 2026-11-04 19:15:52 -06:00).
  - Status: `finalizedUpdateReady: true`, downloaded 11/4/2026, 6:40:36 PM. Auto install scheduled for `2026-11-05T00:00:00-07:00` while offroad / parked.
- **Previous Commit:** [`73d805fa`](https://github.com/StarPilotHQ/openpilot/commit/73d805fafedbf6e4e380a0ab26a22c6370e298f1) (*"Coordinate lane-centering nudges with planned turns"*, 2026-11-04 10:26:26 -06:00).

## Major Capabilities & Recent Commits on Dom (November 1–4, 2026)

1. **Vision Stop Signs & Force Stop Integration:**
   - `0571890b`: Integrate confirmed vision stop signs with Force Stop.
   - `49ed51a4`: Qualify reviewed vision stop-sign schema addition.
   - `88566f18`: Add a STOP-capable joint traffic sign model.
2. **Turn Assist & Lateral Controls:**
   - `c8296b3b`: Keep Turn Assist helping through braking turns.
   - `e30016c0`: Restore familiar torque arc colors and label.
   - `2929a25a`: Add parked manual editing for 30 steering coordinates in Tuning and Analysis.
   - `73d805fa`: Coordinate lane-centering nudges with planned turns.
3. **Android Auto, StarPort & Resource Priority:**
   - `7c0d1d50`: Prioritize driving and audio over Android Auto on every comma.
   - `ea27a69c`: Keep Android Auto transport below driving and audio on comma 4.
   - `f45fd8ed`: Keep comma 4 display work below driving and audio.
   - `13930a89`: Share personality alert preference with Android Auto.
   - `d907c22a`: Bind each Android Auto startup fixture to its device.
   - `91dad8b6`: StarPort AutoSetup 20.2 merged into main line.
4. **Vehicle Support & Safety Guardrails:**
   - `c08b3249`, `95bf9468`, `4cf5c9e0`, `89af8a65`, `5f960fba`: Subaru engagement and EPS steering handoff safety bounds.
   - `3eb7f3fe`, `4b8ca5c3`, `465db1bc`: Ford lateral intent preservation and native pinion boundary matching.
   - `db1aaa67`, `a15b58fc`: Elantra Hybrid refresh year support and CAN interrupt fault guards.
   - `bf4e27fc`, `d42ddc3a`: Kia Carnival independent lateral and longitudinal firmware.
   - `1509b5e6`: Distinguish legacy Toyota radar pre-warning from unavailability.

## Consequences for ParamAuto

- **Stable Typed API Contract:** Galaxy serves typed JSON endpoints exclusively. No legacy `/api/params/all` route exists.
- **Auto Learn / FLM:** Real-time steering learning runs autonomously for `TOYOTA_RAV4_TSS2_2022` with state exposed on `/api/flm/training-results`.
- **Scheduled Updates:** Galaxy automatically prepares downloads in the background while parked and applies updates on schedule (`0 0 * * *`) when parked.

## Related
- [endpoints.md](endpoints.md) · [typed-api-map.md](typed-api-map.md) · [access-and-tunnel.md](access-and-tunnel.md)
