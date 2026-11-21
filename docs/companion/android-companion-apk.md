---
okf_version: "0.2"
type: App Analysis
title: "Official StarPilot companion Android app (APK analysis)"
description: "Identity, architecture, transports, and endpoints of link.firestar.companion v1.1.30, from analysis of the APK Param provided."
tags: [companion, android, apk, galaxy]
status: stable
trust_tier: authoritative
generated: { by: "jarvis", at: "2026-10-27" }
---

# Galaxy: StarPilot Companion — Android APK analysis

Analyzed 2026-10-27 from the APK Param provided (`starpilot-companion__1__1_r09w.apk`,
SHA-256 `e394cb91c89e24d6e37969493806b6672282ff974a24ed55d800cc77acfcea8d`, 752 KB).
Method: zip inventory, binary AndroidManifest decode, DEX string/class extraction,
APK Signing Block v2 certificate extraction. No decompiler was available on the VM,
so logic-level claims are inferred from class names, strings, and the bundled JS —
each is marked as observed vs inferred.

## Identity

- Package: `link.firestar.companion`, app label **StarPilot**
- Version: **1.1.30** (versionCode 32)
- minSdk 26, targetSdk 35, compileSdk 37; built with Android Gradle Plugin 9.4.1
- Single `classes.dex` (~2.4 MB), **no native libraries**, Kotlin app
- Signing: self-signed cert `CN=StarPilot Companion`, serial 6283ADF85652510A,
  valid Oct 13 2026 → Feb 28 2054,
  SHA-256 fingerprint `D9:E7:2C:BB:84:2E:95:30:B4:B6:86:DE:C4:24:CD:EB:8F:29:82:10:9B:E6:87:5F:A7:35:94:C3:33:32:FC:3A`
- `allowBackup=false`, network security config present (cleartext LAN expected)

## Architecture (the headline)

A **thin native shell around the Galaxy web UI**, not a big native app:

- `GalaxySource` / `GalaxyAssetStore` / `GalaxyAssetManifest` download and cache the
  Galaxy web assets from the comma (`/api/companion/web-manifest`) and serve them to a
  WebView from the fake origin `https://galaxy-app.invalid`. The comma can also push a
  newer Galaxy UI (`GalaxyUpdates`, `GalaxyTooOldException`).
- Three transports behind a `Transport` abstraction:
  - **LAN** — `LanDiscovery` (mDNS/multicast; CHANGE_WIFI_MULTICAST_STATE), Galaxy at
    `http://172.31.254.1:8082` on the comma hotspot, pairing via `LanPairing`/`ClaimPairing`.
  - **Bluetooth** — `BleTransport` + `BleWire` implement the same BLE bridge protocol as
    the iOS companion (UUID prefix `bd49000…`, AAD prefix `galaxy-ble-v1/` both present
    in the DEX). The WebView's `fetch` is monkey-patched by `assets/bluetooth-fetch.js`:
    same-origin `/api/*` calls are forwarded to the native `StarPilotBLE` JS bridge
    (`request(id, json)` / `cancel(id)`, replies via `window.__galaxyBleReply`), and
    `/data/runtime.json` is rewritten to `/api/companion/runtime`.
  - **Cellular/uplink** — `CellularTransport` / `AndroidCellularTransport` (inferred:
    reach Galaxy over the phone's own data path).
- **Phone → comma internet uplink**: `UplinkService` (foreground, connectedDevice type)
  runs an in-app **Kotlin SOCKS5 server** (`SocksServer`) and heartbeats a lease to
  `/api/companion/uplink` — exactly the comma-side `companion.py` protocol (peer in the
  172.31.254.0/24 hotspot subnet, SOCKS port + credentials, 15 s lease). No native tunnel
  binary in the app; the comma side runs hev-socks5-tunnel.
- **Phone GPS sharing**: `PhoneGpsService` (foreground, location type) + `PhoneGps`
  post the phone's location to `/api/companion/location`.
- **Share-to-navigate**: `ShareActivity` accepts `ACTION_SEND` text/plain and `VIEW`
  for `geo:` / `google.navigation:` URIs, and contains a resolver regex for
  `maps.app.goo.gl` / `goo.gl/maps` short links (inferred: shared Maps links become
  comma destinations).
- **Self-update from the comma**: `AppUpdateJob`, `AppInstaller`, `AppInstallReceiver`,
  `AppUpdateCompletion` (MY_PACKAGE_REPLACED) + `REQUEST_INSTALL_PACKAGES` — downloads
  `/api/companion/app.apk` from the comma and installs it. This is why `companion.py`
  has an APK path: Galaxy serves the app; the repo just doesn't commit the binary.
- **Auto-start**: `BootReceiver` on BOOT_COMPLETED.
- Other endpoints referenced: `/api/auth/session`, `/api/bluetooth/action` (app asks
  the comma to perform Bluetooth actions), `/api/companion/bluetooth-ready`,
  `/api/companion/status`, `/api/software/status?history=0`.

## What Bluetooth mode cannot do (from bluetooth-fetch.js, observed)

Over BLE these return 501 `bluetooth_unavailable`: recordings, replay, cameras,
Android Auto APK upload, Spotify upload, map tile downloads, the uplink endpoint,
the app.apk download, and auth login/logout. Request bodies are capped at 1 MB and
external origins are rejected. BLE is a settings/status/light-API transport; media
and setup flows require Galaxy Wi-Fi or LAN.

## Permissions

INTERNET, ACCESS_NETWORK_STATE, CHANGE_NETWORK_STATE, CHANGE_WIFI_MULTICAST_STATE,
FOREGROUND_SERVICE (+CONNECTED_DEVICE, +LOCATION), POST_NOTIFICATIONS,
REQUEST_INSTALL_PACKAGES, RECEIVE_BOOT_COMPLETED, BLUETOOTH/BLUETOOTH_ADMIN (≤30),
BLUETOOTH_SCAN (neverForLocation), BLUETOOTH_CONNECT, ACCESS_FINE/COARSE_LOCATION.
Feature: bluetooth_le not required.

## Implications for ParamAuto

1. **The WebView pattern is the official one.** On SecretGoodStarPilot, hosting the
   typed Galaxy UI (cached assets + fetch bridge) is how the vendor's own app avoids
   reimplementing settings screens natively.
2. **The BLE port in ParamAuto is protocol-correct** — same UUID family and
   `galaxy-ble-v1/` AAD strings exist in their Android code. Their trick worth copying:
   tunnel the *WebView's* fetch over BLE (`bluetooth-fetch.js` pattern) instead of
   building native screens for BLE mode.
3. **Share-target navigation** (geo/google.navigation/SEND + Maps short links) is a
   small, high-value add that fits Param's Google Maps → comma workflow directly.
4. **Phone GPS → comma** (`/api/companion/location`) is a real SG API ParamAuto
   could feed; the comma evidently consumes phone location when its own fix is poor.
5. **Self-update from the comma** is elegant but sideload-only; skip unless
   ParamAuto ever ships outside the Play Store.
