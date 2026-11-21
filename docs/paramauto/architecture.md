---
okf_version: "0.2"
type: Architecture
title: "ParamAuto architecture"
description: "The standing data path — comma → phone → Android Auto — plus the app's transports (LAN, hotspot, BLE, WebView) and data sources."
tags: [paramauto, architecture, android-auto]
status: stable
trust_tier: authoritative
generated: { by: "jarvis", at: "2026-10-27" }
verified: { by: "param", at: "2026-10-27" }
---

# ParamAuto architecture

## Standing rule (Param, 2026-10-27)
**Always comma → phone → Android Auto.** The phone hosts Android Auto and renders the
app to the car screen; the comma is only a data source. The comma → auto path
(SecretGoodStarPilot's on-comma Android Auto projection) is **not** used and features
must not be designed around it.

## Data path
1. The comma runs StarPilot (SecretGoodStarPilot line; Dom is the same code since 2026-10-27) and serves Galaxy on port 8082.
2. The phone app (ParamAuto, `com.parampilot.auto`) polls Galaxy typed APIs and receives state ~1 Hz.
3. The phone renders the navigation UI to the RAV4 head unit through Android Auto (car-app library templates + a custom-drawn surface for the map/HUD).

## Transports (phone ↔ comma)
- **LAN**: subnet scan for `:8082`, then typed-API polling. Primary path.
- **Comma hotspot**: `TheGalaxy-XXXX` → `http://172.31.254.1:8082` (fixed address, no scan). Pairs with wired Android Auto, since wireless AA occupies the phone's Wi-Fi.
- **BLE bridge**: Galaxy API requests tunneled over Bluetooth LE (see the BLE bridge protocol doc). Requires the comma-side bridge build, not in SG mainline.
- **Galaxy WebView**: a WebView screen hosting the Galaxy web UI; in BLE mode its `/api/*` fetches are bridged natively (`StarPilotBLE`), mirroring the official companion app.
- **Galaxy Cloud (legacy)**: the old firestar portal + params API; dead on SG builds.

## Data sources on SG (current)
- Navigation state: `GET /api/navigation/status` (instruction, route, alternatives, GPS, revision).
- Vehicle speed + engagement: `GET /api/plots/live`.
- Device state: `GET /api/device/state`. Power / battery voltage: `GET /api/system/monitor`.
- Destinations out: `POST /api/navigation/action`.
- Full mapping: [../galaxy/typed-api-map.md](../galaxy/typed-api-map.md).

## Related
- [build-history.md](build-history.md) · [search-design.md](search-design.md) · [../companion/ble-bridge-protocol.md](../companion/ble-bridge-protocol.md)
