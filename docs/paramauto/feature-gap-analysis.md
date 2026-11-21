---
okf_version: "0.2"
type: Analysis
title: "Galaxy Web vs ParamAuto: Comprehensive Feature Parity & Gap Analysis"
description: "Comprehensive side-by-side capability audit comparing the live StarPilot Galaxy web suite with the ParamAuto Android Auto companion app, identifying implemented features, partial bridges, and critical gaps."
tags: [paramauto, galaxy, gap-analysis, parity, android-auto]
status: stable
trust_tier: authoritative
inspected_at: "2026-10-31"
---

> **Status note (2026-11-21):** Dated snapshot. Most gaps identified here were closed in the November 2026 releases (comma settings, Wi-Fi manager, recordings playback, and more) — see the [README](../../README.md) feature list for the current state.


# Galaxy Web vs ParamAuto: Feature Parity & Gap Analysis

**Date:** October 31, 2026  
**Audited Target:** `http://192.168.68.21:8082/#/` (StarPilot Galaxy v0.11.3) vs `d:\ParamAuto` (ParamAuto)  
**Goal:** Map every feature in the Galaxy web interface against what is currently implemented in ParamAuto, highlight missing capabilities, and provide an actionable roadmap for closing the gap.

---

## 1. Feature Parity Matrix

| Domain | Galaxy Web Capability | ParamAuto Status | Gap Level | Implementation in ParamAuto |
|---|---|---|:---:|---|
| **Turn-by-Turn Nav** | `/navigation` active status, ETA, cues | **Fully Implemented** | None | `NavScreen.kt`, `CarMapRenderer.kt`, `TurnBannerView.kt` |
| **Route Geometry** | Multi-alternative routes, polyline | **Fully Implemented** | None | `NavigationManager.kt`, `RouteStrip.kt`, alternative route selector |
| **Destination Search** | `/api/navigation/search` + POI geocoding | **Fully Implemented** | None | `SearchScreen.kt`, `SearchCache.kt`, button-only submit |
| **Map Tiles** | Comma local raster tiles (`:8082`) | **Fully Implemented** | None | `MapTileProvider.kt`, `CommaTileDiskCache.kt`, CARTO fallback |
| **Digital Speedometer** | $v_{\text{Ego}}$ in mph/kmh from `/api/plots/live` | **Fully Implemented** | None | `CarSurfaceHudDrawer.kt`, `SpeedometerSmoother.kt` |
| **MUTCD Speed Sign** | Road speed limit + SLC band offset | **Fully Implemented** | None | `SpeedLimitSignView.kt`, `CarSurfaceHudDrawer.kt` |
| **Engagement HUD** | Gemini Aurora glow pills (Steer/Cruise/OP) | **Fully Implemented** | None | `CarSurfaceHudDrawer.kt`, `HudOverlay.kt` |
| **Personality Profiles**| Aggressive / Standard / Relaxed | **Fully Implemented** | None | `SettingsRepository.kt`, two-phase preview/confirm |
| **SLC Offset Curves** | 7-band speed limit controller offsets | **Fully Implemented** | None | `SettingsRepository.kt`, `SgGalaxyClient.kt` |
| **Model Manager** | Catalog, active model, download/delete | **Fully Implemented** | None | `ModelManagerRepository.kt`, parked-gate safety check |
| **Sound Packs** | Download & select alert sound themes | **Fully Implemented** | None | `ModelManagerRepository.kt`, `/api/sounds/download` |
| **FLM Auto Learn** | Driver monitoring calibration progress | **Fully Implemented** | None | `ModelManagerRepository.kt`, `GalaxyFlmStatus` |
| **Multi-Transport** | Bluetooth LE, Wi-Fi LAN, Hotspot, Cloud | **Fully Implemented** | None | `CommaClient.kt`, `BleBridgeClient.kt`, `LanPairing.kt` |
| **Full Web Interface** | Embedded WebView for full web GUI | **Fully Implemented** | None | `GalaxyWebActivity.kt`, `StarPilotBleBridge.kt` |
| **Vehicle Selection** | `/api/vehicle-selection` model picker | **Missing in Native App** | **High** | Available only via embedded WebView |
| **Cameras & Sentry** | Live snapshot, Sentry events/images | **Missing in Native App** | **High** | Available only via embedded WebView |
| **Blind Spot PIP / V-ASM**| Camera crop adjustment, spot monitoring | **Missing in Native App** | **Medium**| Available only via embedded WebView |
| **Software Updates** | Branch checkout, update actions, schedule| **Partially Implemented**| **Medium**| `CommaSoftwareUpdate.kt` skeleton only |
| **Drive Recordings** | Local dashcam video list, download, delete| **Missing in Native App** | **Medium**| Available only via embedded WebView |
| **Bluetooth Device UI**| Adapter power toggle, forget device | **Missing in Native App** | **Medium**| Available only via embedded WebView |
| **StarPort / Spotify** | Spotify controls, soloist, audio forwarding| **Missing in Native App** | **Low** | Available only via embedded WebView |
| **Settings Recovery** | Non-default audit, backup, restore | **Missing in Native App** | **Low** | Available only via embedded WebView |
| **tmux Live Console** | Daemon tail (`navd`, `controlsd`, etc.) | **Missing in Native App** | **Low** | Available only via embedded WebView |

---

## 2. In-Depth Gap Analysis & Architectural Priorities

### 2.1 Gap 1: Vehicle Model Selection (`/api/vehicle-selection`)
- **Galaxy Web Feature:**  
  Allows drivers to select their vehicle make and model (e.g. Toyota RAV4, Corolla, Honda Civic, Hyundai Ioniq 5) or choose "Parked Auto" to let openpilot auto-fingerprint. Mutation follows a two-phase commit:
  1. `POST /api/vehicle-selection/preview` with `{vehicle: "<id>"}` -> returns `{valid: true, diff: {...}}`.
  2. `POST /api/vehicle-selection/confirm` with `{confirmed: true}` -> commits to persistent params.
- **ParamAuto Current State:**  
  ParamAuto relies on the comma having the correct vehicle already selected. If fingerprinting fails or the user changes cars, the user must open `GalaxyWebActivity` or touch the comma screen.
- **Implementation Recommendation:**  
  Add `VehicleRepository.kt` with `getAvailableVehicles()`, `previewVehicleSelection(id)`, and `confirmVehicleSelection(id)`. Expose a clean Google Material 3 vehicle selection dialog in `SettingsDialogs.kt` accessible from the phone dashboard and Android Auto parked settings screen.

---

### 2.2 Gap 2: Live Cameras & Sentry Security Dashboard (`/api/cameras`, `/api/sentry`)
- **Galaxy Web Feature:**  
  - Real-time snapshot viewer (`GET /api/cameras/snapshot`).
  - Sentry event browser (`GET /api/sentry/events`) with high-resolution intrusion/motion images (`GET /api/sentry/image/{id}`) and event deletion.
- **ParamAuto Current State:**  
  ParamAuto has no camera stream or snapshot viewer. Sentry notifications are not handled natively.
- **Implementation Recommendation:**  
  1. Create `CameraRepository.kt` supporting `fetchSnapshot(): Bitmap?`.
  2. Add a parked camera preview screen in `MainActivity.kt` showing cabin and road snapshots.
  3. Support Sentry motion alerts via Android push notifications when the vehicle is parked and armed.

---

### 2.3 Gap 3: Software Updates & Git Branch Switching (`/api/software`)
- **Galaxy Web Feature:**  
  Galaxy allows checking current version, switching branches (e.g. testing new StarPilot releases), staging updates, and configuring automatic reboot schedules.
- **ParamAuto Current State:**  
  `CommaSoftwareUpdate.kt` provides basic update checking, but branch checkout and automatic reboot scheduling are not exposed in the UI.
- **Implementation Recommendation:**  
  Expand `CommaSoftwareUpdate.kt` into a full repository with `fetchSoftwareStatus()`, `checkoutBranch(branchName)`, and `triggerUpdate()`. Expose a "Software & Branches" card in the phone settings view.

---

### 2.4 Gap 4: Dashcam Drive Recordings & Route Workspace (`/api/recordings`)
- **Galaxy Web Feature:**  
  Lists all saved driving routes with duration, distance, and downloadable forward/cabin video clips. Allows deleting routes to free disk space.
- **ParamAuto Current State:**  
  ParamAuto focuses purely on real-time driving projection and telemetry; historical routes are not presented.
- **Implementation Recommendation:**  
  Add a "Drive History" tab in `MainActivity.kt` that queries `GET /api/recordings/local`, showing a timeline of recent drives with summary statistics (miles, time, efficiency).

---

### 2.5 Gap 5: Bluetooth Paired Devices & Adapter Control (`/api/bluetooth`)
- **Galaxy Web Feature:**  
  Displays Bluetooth adapter power, discoverable status, and a list of paired phones with a 1-tap "Forget" button.
- **ParamAuto Current State:**  
  ParamAuto has an advanced BLE bridge client (`BleBridgeClient.kt`) that automatically connects, pairs via ECDH, and tunnels requests. However, it does not display the comma's list of paired devices or adapter status.
- **Implementation Recommendation:**  
  Add a "Bluetooth Devices" section to `SettingsDialogs.kt` allowing users to view paired phones and toggle comma Bluetooth power remotely.

---

## 3. Summary of Core Strengths

While auxiliary tools (cameras, dashcam recordings, software branch switching) currently reside in the embedded `GalaxyWebActivity` WebView, **all critical automotive driving functions are fully natively implemented in ParamAuto:**
- Zero-latency turn-by-turn navigation with Google Maps aesthetic.
- Comma-first offline raster tile engine with cellular fallback.
- Real-time 60 FPS HUD overlay with Gemini Aurora engagement styling.
- Two-phase safety-guarded personality profile and SLC curve updates.
- AI Model Manager and sound pack customization.
- Intelligent multi-transport routing (Bluetooth LE -> LAN -> Hotspot -> Cloud).

