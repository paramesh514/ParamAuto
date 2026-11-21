---
okf_version: "0.2"
type: Audit Reference
title: "StarPilot Galaxy Web Application Live Functionality Audit"
description: "Comprehensive functional audit of StarPilot Galaxy (v0.11.3 / build 7.0) inspected live at http://192.168.68.21:8082/#/, analyzing all 14 capability areas and 97 frontend modules."
tags: [galaxy, audit, starpilot, comma, web, functionalities]
status: stable
trust_tier: authoritative
verified_against: "http://192.168.68.21:8082/#/"
inspected_at: "2026-10-31"
---

# StarPilot Galaxy Web Application Live Functionality Audit

**Target URL:** `http://192.168.68.21:8082/#/`  
**Inspection Date:** October 31, 2026  
**Hardware / System:** Comma 3/3X (`ParamCar`, Toyota RAV4), StarPilot Galaxy v0.11.3 / Build 7.0  
**Backend:** BaseHTTP/0.6 Python 3.12.3 on port 8082  
**Architecture:** Vue 3 ESM Browser Application with hash routing (`/#/`), responsive dark-slate design (`#0e0a1a` canvas, `#6f42a6` purple brand tint, `#131314` cards), and local zero-credential session authminting.

---

## 1. Architectural & Interface Overview

The Galaxy web interface is the full dashboard, vehicle control, telemetry monitor, and settings suite served directly by the comma device. It operates across three distinct modes:
1. **Local Mode (`local`):** Direct LAN or comma Wi-Fi hotspot connection (`192.168.68.x:8082` or `172.31.254.1:8082`). Grants full authenticated access automatically (`localAccess: true`) without password prompts.
2. **Cloud Mode (`preview` / `remote`):** Remote reverse tunnel via `https://galaxy.firestar.link/<slug>`. Requires password authentication via `POST /api/auth/login`.
3. **Bluetooth LE Mode:** Proxied over RFCOMM / GATT BLE bridge by companion apps (high-bandwidth features like live video streams and raw file uploads are disabled).

---

## 2. Exhaustive Functional Breakdown by Domain

### 2.1 Navigation & Maps (`/navigation`, `/navigation/maps`, `/navigation/setup`)
- **Interactive Destination Search:**
  - Real-time POI, business, and address search via comma geocoder (`POST /api/navigation/search` with `{query, searchId, clientId, autocomplete: true}`).
  - Temporary search suggestion handles (`selectPlace`) where comma performs server-side coordinate retrieval to protect user privacy and conserve bandwidth.
- **Active Guidance & Routing:**
  - Live status polling via `GET /api/navigation/status?routeKey={cachedKey}` (1 Hz).
  - Multi-alternative route generation (primary + up to 2 alternatives with duration deltas and polyline geometry).
  - Alternative route selection via `POST /api/navigation/action` with `{"action": "selectRoute", "index": n}`.
  - Route cancellation via `POST /api/navigation/action` with `{"action": "clear"}`.
  - Revision guarding: every navigation mutation requires the active `revision` token, returning `409 Conflict` on stale state.
- **Offline Maps Management:**
  - Region-by-region offline map package catalog (`GET /api/maps/catalog`).
  - Active download progress and state tracking (`GET /api/maps/operation`).
  - Download trigger (`POST /api/maps/start`) and cancel (`POST /api/maps/cancel`).
  - Local disk space inspection and storage quotas.
- **Mapbox & Provider Setup:**
  - Mapbox API token configuration and validation (`POST /api/maps/setup`).
  - Theme selection (day, night, automatic vehicle CAN headlight sync).

---

### 2.2 Neural Driving Models & Laboratory (`/manage_models`, `/model_laboratory`)
- **Model Manager:**
  - Full catalog of bundled and downloadable driving neural networks (`GET /api/models/manager`).
  - Metadata for each model: series name (e.g. TR Series, Regret Driven, Polaris), release date, parameter size, GPU capability, and compatibility reason.
  - Active model status inspection (`GET /api/models/status`).
  - Model activation (`POST /api/models/active` with `{"model": "<id>"}`). **Parked Gate:** Requires vehicle `state == "parked"`.
  - Background model download (`POST /api/models/download`) and deletion (`POST /api/models/delete`).
  - Model Randomizer feature for community A/B comparison.
- **Model Laboratory:**
  - Dual-model pairing: allows selecting separate lateral control models and longitudinal control models.
  - Experimental eGPU and NPU acceleration options.

---

### 2.3 Driving Settings & Longitudinal Curves (`/driving`, `/driving/longitudinal-curves`)
- **Driving Personalities:**
  - 3-mode profile selector: **Aggressive** (tight follow gap, quick acceleration), **Standard** (balanced cruising), and **Relaxed** (1.8s gap, smooth deceleration).
  - Traffic mode optimization.
- **Speed Limit Controller (SLC) Curves:**
  - 7 discrete speed bands: $0\text{–}24\text{ mph}$, $25\text{–}34\text{ mph}$, $35\text{–}44\text{ mph}$, $45\text{–}54\text{ mph}$, $55\text{–}64\text{ mph}$, $65\text{–}74\text{ mph}$, and $75+\text{ mph}$.
  - Custom offset configuration per band via two-phase preview (`POST /api/settings/preview`) and confirm (`POST /api/settings/confirm`).
- **Steering & Button Mappings:**
  - Steering torque sensitivity, stock longitudinal override, and steering wheel button action reassignments.

---

### 2.4 Live Telemetry & Auto Learn Analysis (`/tuning/plots`, `/tuning/flm`)
- **Real-Time Control Plots (`GET /api/plots/live`):**
  - High-rate vehicle speed ($v_{\text{Ego}}$ in m/s) with source tagging (`carState` vs `deviceMotion`).
  - Master engagement state (`controlsActive`), lateral steering assist (`lateralControlActive`), and longitudinal adaptive cruise (`longitudinalControlActive`).
  - Steering effort, PID error, desired vs. actual lateral acceleration ($m/s^2$).
  - Target acceleration vs. actual engine/brake acceleration ($m/s^2$).
  - Signal freshness health indicators (`controlsFresh`, `poseFresh`).
- **StarPilot Auto Learn (FLM):**
  - Driver Monitoring Neural Network calibration status (`GET /api/flm/training-results`).
  - Learning progress: samples collected, block progress (e.g. $0/8$ blocks), training status (`collecting`, `training`, `ready`), and applied status.
  - Delay-paired steering episode visualizer.

---

### 2.5 Cameras, Sentry & Monitoring (`/cameras`, `/cameras/pip`, `/cameras/events`, `/cameras/vasm`)
- **Live Cabin & Road Previews:**
  - Real-time snapshot viewer (`GET /api/cameras/snapshot`).
- **Blind Spot PIP (Picture-in-Picture):**
  - Crop window adjustment and live cabin preview with side camera blind-spot overlay configuration (`/cameras/pip`).
- **Sentry Security & Motion Detection:**
  - Motion event list (`GET /api/sentry/events`).
  - Event categories: **Selfie**, **Warning**, and **Alarm**.
  - High-resolution event image retrieval (`GET /api/sentry/image/{id}`).
  - Event deletion (`POST /api/sentry/events/delete`).
  - WebPush and mobile notification dispatcher (`/api/sentry/notifications`).
- **V-ASM (Visual Active Spot Monitoring):**
  - Interactive cabin camera region drawing: user can draw custom window bounding boxes directly on the video frame.
  - Visual blind-spot choice toggles and sensitivity tuning.

---

### 2.6 StarPort & Dashboard Integration (`/starport`, `/starport/audio`)
- **StarPort In-Car Display Bridge:**
  - Direct connection to car head units via wired Android Auto, wireless dongle, or companion display (`/api/starport/*`).
  - Receiver discovery and selection (`/api/starport/receivers`, `/api/starport/pairing/select`).
  - Display resolution, scaling, and layout customization (`/api/starport/layout`).
- **Spotify Soloist & Phone Audio Forwarding:**
  - Comma-hosted Spotify playback daemon (`/api/spotify/*`).
  - API key setup, playlist management, and playback controls (play, pause, next, volume).
  - Phone audio forwarding directly through the comma audio hardware to car aux/speakers.

---

### 2.7 Vehicle Selection & Hardware Controls (`/vehicle`)
- **Vehicle Selection Engine (`/api/vehicle-selection`):**
  - Full vehicle database: Toyota, Lexus, Honda, Hyundai, Kia, Ford, Subaru, Volkswagen, etc.
  - Fingerprint detection status and CAN bus validation.
  - Next-start manual vehicle override with two-phase commit: `preview` -> `confirm`.
  - Parked Auto mode (automatic fingerprint identification on ignition).

---

### 2.8 Bluetooth Management (`/bluetooth`)
- **Adapter Control & State:**
  - Bluetooth radio power toggle (`GET|POST /api/bluetooth/status`, `/api/bluetooth/action`).
  - Paired/bonded device list with signal RSSI.
  - Device unpairing / forget action (`{"operation": "forget", "address": "..."}`).
  - Pair phone action: opens comma's 2-minute pairing claim window (`{"operation": "accept_pair"}`).

---

### 2.9 System Tools, Software Updates & Versioning (`/system`)
- **Software Status & Branch Management (`GET /api/software/status`):**
  - Current git branch (e.g. `SecretGoodStarPilot-v0.11.3`), commit SHA, and release notes.
  - Available upstream git branches with 1-tap checkout.
  - Update check, download progress, and staged updates.
  - Software update trigger (`POST /api/software/action`).
  - Automatic restart schedule (e.g. 3:00 AM daily reboot).

---

### 2.10 Drive Recordings & Dashcam Workspace (`/recordings`)
- **Local Route Recordings (`GET /api/recordings/local`):**
  - List of past drives with date, duration, miles driven, and video segments.
  - Video preview streaming and download.
  - Selective or bulk route video cleanup (`POST /api/recordings/delete-videos`).
  - Drive stats and commute summaries (`GET /api/drives/stats`).

---

### 2.11 Settings Hub, Non-Defaults & Recovery (`/settings`, `/settings-recovery`)
- **Full Parameter Configuration Tree:**
  - Multi-category hierarchy: Device, Network, Toggles, Driving, StarPort, Display, Sounds.
  - Two-phase mutation pipeline (`/api/settings/preview` -> `/api/settings/confirm`).
- **Settings Audit & Non-Default Detection (`GET /api/settings/non-defaults`):**
  - Highlights every parameter that differs from openpilot stock defaults.
  - 1-click restore to default per parameter (`POST /api/settings/reset-default`).
  - Complete configuration backup and restore (`POST /api/settings/backup`, `/api/settings/restore`).

---

### 2.12 Diagnostics, Tmux & Logs (`/logs`)
- **System Activity Monitor (`GET /api/system/monitor`):**
  - Real-time CPU usage, core frequencies, RAM allocation, thermal sensors, and storage free space.
- **Console Tail & Live tmux (`GET /api/tmux/live`):**
  - Live streaming tail of comma daemon consoles (`navd`, `controlsd`, `camerad`, `plannerd`).
- **Crash Reports & Troubleshooting:**
  - Local crash log viewer (`GET /api/crash-reports`).
  - Hardware diagnostic analyzer (`GET /api/troubleshoot`).

---

### 2.13 Colors & Onroad Display Customizer (`/theme_maker`)
- **Theme Maker:**
  - Visual color palette customizer for Big Layout and Small Layout.
  - Customizable road lane colors, lead car markers, HUD speed text size, and alert banner styling.
  - Layout configuration saved to `/api/ui/layout`.

---

### 2.14 Galaxy Remote Access & Tunnel (`/galaxy`)
- **Tunnel Pairing & Hotspot Setup:**
  - Remote cloud pairing with `galaxy.firestar.link` portal (`/api/galaxy/pair`).
  - Dynamic QR codes for phone companion pairing (`/api/galaxy/qr.svg`, `/api/galaxy/session/qr.svg`).
  - Wi-Fi Hotspot configuration and SSID/password display (`/api/galaxy/hotspot`).

