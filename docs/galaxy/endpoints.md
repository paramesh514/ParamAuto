---
okf_version: "0.2"
type: API Reference
title: "SecretGoodStarPilot Galaxy API Endpoint Reference"
description: "Comprehensive HTTP REST API endpoint reference for StarPilot Galaxy running on comma devices, verified against live device at http://192.168.68.16:8082."
tags: [galaxy, api, starpilot, comma, parampilot]
status: stable
trust_tier: authoritative
verified_against: "http://192.168.68.16:8082"
inspected_at: "2026-11-04"
server_signature: "BaseHTTP/0.6 Python/3.12.3"
---

# StarPilot Galaxy API Endpoint Reference

Complete documentation of all HTTP endpoints provided by the **StarPilot Galaxy** backend server (running on the comma device at `http://192.168.68.16:8082` on LAN, or `http://172.31.254.1:8082` on comma Wi-Fi hotspot).

All endpoints were live-inspected and verified on November 4, 2026 against the active comma device (`ParamCar`, Toyota RAV4 running StarPilot Galaxy v0.11.3 / build 7.0, branch `Dom`, installed commit `cb707259`, prepared commit `c08b3249`).

---

## 1. Architectural & Authentication Overview

### 1.1 Base URLs & Networking
- **LAN Access:** `http://192.168.68.16:8082` (or current LAN DHCP IP)
- **Direct Hotspot Access:** `http://172.31.254.1:8082` (SSID: `TheGalaxy-XXXX`)
- **Remote Reverse Tunnel:** `https://galaxy.firestar.link/<slug>` (frpc tunnel via FRP / WireGuard / Athena, active slug: `<device-slug>`)
- **Local Bluetooth LE Bridge:** Proxied over RFCOMM / GATT BLE bridge by companion apps (certain high-bandwidth endpoints like video streaming and file uploads are disabled over BLE).

### 1.2 Session & Authentication Lifecycle
1. **Cookie-Based Session (`galaxy_session`):**
   - Direct requests to `/api/*` require an active session cookie: `Cookie: galaxy_session=<token>`.
   - To obtain or initialize a session, the client issues a `GET /api/auth/session`.
   - On local networks (LAN / hotspot), the server automatically establishes a local session and responds with:
     ```http
     HTTP/1.1 200 OK
     Set-Cookie: galaxy_session=<example-session-token>; HttpOnly; SameSite=Strict; Path=/; Max-Age=1800
     Content-Type: application/json

     {"authenticated": true, "state": "configured", "localAccess": true}
     ```
   - Subsequent API requests must pass this cookie.
2. **Unauthenticated / Expired Responses:**
   - Any `/api/*` call without a valid session cookie returns:
     ```http
     HTTP/1.1 401 Unauthorized
     Content-Type: application/json

     {"error": "Refresh the local Galaxy session"}
     ```
3. **Unknown Capability / Route:**
   - Unknown `/api/*` paths return:
     ```http
     HTTP/1.1 404 Not Found
     Content-Type: application/json

     {"error": "Capability unavailable"}
     ```
4. **State Requirements:**
   - **Parked Check:** Critical settings mutations, camera snapshots, sound downloads, and model switching require the vehicle to be parked (`state === "parked"`). When driving, mutating actions return 400 or 409 error codes.
   - **Two-Phase Commit (Preview -> Confirm):** Settings, vehicle selection, and layout mutations use a two-phase protocol:
     1. `POST .../preview` with candidate state -> returns calculated diff, validation result, and a unique `view` revision token.
     2. `POST .../confirm` with `{ view, confirmed: true }` -> commits the change.
   - **Revision Guarding:** Navigation, favorites, drive-state, and layout updates require passing the current `revision` hash to prevent conflicting edits (returns `409 Conflict` on mismatch).

---

## 2. Authentication, Access & Remote Tunnels

### `GET /api/auth/session`
Initializes or refreshes the Galaxy session. Local connections receive an authenticated session automatically with a `galaxy_session` cookie.
- **Method:** `GET`
- **Auth Required:** No
- **Response (200 OK):**
```json
{
  "authenticated": true,
  "state": "configured",
  "localAccess": true
}
```

### `POST /api/auth/login`
Password sign-in for remote (non-local) tunnel access when not connected via LAN or hotspot.
- **Method:** `POST`
- **Headers:** `Content-Type: application/json`
- **Request Body:**
```json
{
  "password": "<remote-access-password>"
}
```
- **Response (200 OK):** Session cookie set, `authenticated: true`.

### `POST /api/auth/logout`
Terminates the current session.
- **Method:** `POST`
- **Response (200 OK):** `galaxy_session` cookie cleared.

### `GET /api/local-access`
Lists all local network interfaces and IP addresses assigned to the comma device.
- **Method:** `GET`
- **Response (200 OK):**
```json
{
  "available": true,
  "addresses": [
    {
      "interface": "wlan0",
      "label": "192.168.68.14 (wlan0)",
      "url": "http://192.168.68.14:8082/"
    }
  ],
  "reason": ""
}
```

### `GET /api/galaxy/status`
Returns remote pairing and reverse tunnel status.
- **Method:** `GET`
- **Response (200 OK):**
```json
{
  "paired": true,
  "url": "https://galaxy.firestar.link/<device-slug>",
  "tunnelClientAvailable": true,
  "legacyPassword": false,
  "legacyPairingAvailable": false
}
```

### `POST /api/galaxy/pair`
Pairs remote access to the device using a password.
- **Method:** `POST`
- **Request Body:** `{"password": "<password>"}`
- **Response (200 OK):** `{"paired": true, "slug": "<slug>", "url": "https://galaxy.firestar.link/<slug>"}`

### `POST /api/galaxy/unpair`
Disables remote pairing and disconnects the remote tunnel.
- **Method:** `POST`
- **Request Body:** `{}`

### `GET /api/galaxy/session`
Returns current pairing slug, tunnel URL, and active session token details.
- **Method:** `GET`
- **Response (200 OK):**
```json
{
  "paired": true,
  "slug": "<device-slug>",
  "url": "https://galaxy.firestar.link/<device-slug>",
  "cookieName": "galaxy_session",
  "sessionToken": "<redacted-example-session-token>"
}
```

### `GET /api/galaxy/device-name`
### `POST /api/galaxy/device-name`
Reads or updates the friendly display name of the comma unit.
- **Method:** `GET` | `POST`
- **Request Body (POST):** `{"name": "ParamCar"}`
- **Response (200 OK):**
```json
{
  "name": "ParamCar"
}
```

### `GET /api/galaxy/hotspot`
### `POST /api/galaxy/hotspot`
Reads or toggles the comma Wi-Fi hotspot (`TheGalaxy-XXXX`).
- **Method:** `GET` | `POST`
- **Request Body (POST):** `{"enabled": true, "ssid": "TheGalaxy-XXXX", "password": "<pass>"}`
- **Response (200 OK):**
```json
{
  "version": 1,
  "config": {
    "enabled": false,
    "ssid": "TheGalaxy-XXXX",
    "password": ""
  },
  "revision": "bdd8a4e76d09ce9840f9683fc6dd663d55e65bc9767b36fd3e838e7ff6227503",
  "editable": true,
  "url": "http://172.31.254.1:8082/",
  "active": false,
  "state": "disabled",
  "reason": ""
}
```

### `GET /api/galaxy/qr.svg`
### `GET /api/galaxy/session/qr.svg`
Generates SVG QR codes for instant mobile device pairing or remote tunnel onboarding.
- **Method:** `GET`
- **Response (200 OK):** `image/svg+xml`

### `GET /api/connect/provider`
### `POST /api/connect/provider`
Manages comma connect (Athena) provider selection and backend tunnel routing.
- **Method:** `GET` | `POST`
- **Response (200 OK):**
```json
{
  "active": "comma",
  "selected": "comma",
  "requestedBoot": "",
  "revision": "85975017f551386025d09146ecc46fdc49369e94a33d2374bf78b0263299dab9",
  "restartRequired": false,
  "providers": [
    {
      "id": "comma",
      "label": "comma connect",
      "url": "https://connect.comma.ai"
    },
    {
      "id": "konik",
      "label": "Konik",
      "url": "https://stable.konik.ai"
    }
  ],
  "canSelect": true
}
```

### `GET /api/connect/proxy`
### `POST /api/connect/proxy`
Manages reverse proxy settings for third-party cloud integrations.
- **Method:** `GET` | `POST`
- **Response (200 OK):**
```json
{
  "enabled": false,
  "address": "",
  "origin": "",
  "revision": "0212a1c3f2beb5729b8e75526664c237c2f4e3bc545ba3fe9e44fb5f3b29ab88",
  "canEdit": true,
  "passwordConfigured": true
}
```

---

## 3. Device Status, Telemetry & Diagnostics

### `GET /api/weather`
Returns current ambient outside temperature estimate near the vehicle's last recorded GPS coordinates, fetched on the comma from Open-Meteo.
- **Method:** `GET`
- **Auth Required:** Yes
- **Response (200 OK):**
```json
{
  "temperatureC": 18.8,
  "observedAt": 1793844900,
  "status": "current",
  "locationRecordedAt": 1793842739.3218653
}
```

### `GET /api/device/state`
High-frequency lightweight endpoint for vehicle ignition/driving state polling.
- **Method:** `GET`
- **Response (200 OK):**
```json
{
  "state": "parked",
  "maxAgeMs": 2597
}
```
- **State values:** `"parked"`, `"standby"`, `"driving"`, or `null`.

### `GET /api/system/monitor`
Comprehensive system telemetry sample: CPU utilization, thermal zones, memory, NVMe storage, network throughput, and running comma processes.
- **Method:** `GET`
- **Response (200 OK):**
```json
{
  "schemaVersion": 1,
  "mode": "local-runtime",
  "source": "Local Linux system telemetry",
  "sampledAt": 1793387464.0438912,
  "cpuPercent": 49.8,
  "cores": [
    {
      "name": "cpu0",
      "percent": 42.6
    },
    {
      "name": "cpu1",
      "percent": 56.7
    },
    {
      "name": "cpu2",
      "percent": 57.9
    },
    {
      "name": "cpu3",
      "percent": 42.1
    }
  ],
  "memory": {
    "totalMiB": 3606.3,
    "usedMiB": 1596.7,
    "availableMiB": 2009.6,
    "percent": 44.3
  },
  "storage": {
    "usedGiB": 30.5,
    "totalGiB": 88.0
  },
  "uptimeSeconds": 49956.47,
  // ... (truncated)
```

### `GET /api/plots/live`
Real-time high-fidelity vehicle telemetry and openpilot control loop signals.
- **Method:** `GET`
- **Response (200 OK):**
```json
{
  "schemaVersion": 1,
  "state": "stale",
  "sessionId": "7ffb6d43f2fab51b",
  "sampleIndex": 6,
  "sampleAgeSeconds": null,
  "values": null,
  "error": "",
  "bootStabilizing": false
}
```
- **Payload fields:**
  - `speedMps`: Vehicle speed in m/s (from `vEgo` / `deviceMotion`).
  - `speedSource`: Source sensor/estimator for speed.
  - `controlsActive`, `lateralControlActive`, `longitudinalControlActive`: Boolean engagement flags.
  - `desiredAccelMps2`, `actualAccelMps2`: Target vs executed longitudinal acceleration.
  - `lateralPid`, `longitudinalPid`: PID/controller error terms.

### `GET /api/troubleshoot`
Aggregated troubleshooting bundle containing detected vehicle fingerprint, CAN status, ignition state, and diagnostic notes.
- **Method:** `GET`
- **Response (200 OK):**
```json
{
  "schemaVersion": 1,
  "vehicle": {
    "available": true,
    "fingerprint": "TOYOTA_RAV4_TSS2_2022",
    "brand": "toyota",
    "longitudinal": true,
    "steering": "torque"
  },
  "sections": [
    {
      "title": "Steering and Torque",
      "rows": [
        {
          "label": "Turn Signal Guidance",
          "value": "On"
        },
        {
          "label": "Turn Assist",
          "value": "On"
        },
        {
          "label": "Use StarPilot Auto Learn",
          "value": "On"
        },
        {
          "label": "Lat Accel",
          "value": "1.89999998"
        },
        {
  // ... (truncated)
```

### `GET /api/tmux/live`
Captures the live terminal output from openpilot tmux sessions for on-device console debugging.
- **Method:** `GET`
- **Response (200 OK):**
```json
{
  "available": true,
  "reason": "",
  "text": "xy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\nlogmessaged timed ui navigationd navtilesd galaxy galaxy_phone replay_owner gala\nxy_hotspot companion_uplink map_snapshot_operations deleter pandad hardwared mod\nem usage_stats tombstoned updated uploader\n",
  "truncated": true,
  "pane": "comma:0.0"
}
```

### `GET /api/crash-reports`
### `GET /api/crash-reports/<id>`
### `POST /api/crash-reports/delete`
Lists system crash reports, fetches full stack traces, and removes resolved crash logs.
- **Method:** `GET` | `POST`
- **Delete Request Body:** `{"reportId": "<id>"}` or `{"all": true, "confirmed": true}`
- **List Response (200 OK):**
```json
{
  "schemaVersion": 1,
  "reports": [],
  "scanIncomplete": false,
  "listLimited": false,
  "reportCount": 0,
  "storageBytes": 0,
  "storageIncomplete": false
}
```

---

## 4. Navigation & Offline Maps

### `GET /api/navigation/status`
Fetches full navigation state including active destination, computed route polyline, maneuvers, instructions, alternative routes, recents, and route revision token.
- **Method:** `GET`
- **Query Params:** `?routeKey=<key>` (optional; allows comma to omit route geometries client already holds)
- **Response (200 OK):**
```json
{
  "enabled": true,
  "destination": null,
  "recents": [
    {
      "id": "a38767e85c734104008b",
      "name": "Riverside Apartments",
      "latitude": 33.4484,
      "longitude": -112.0740,
      "address": "1234 Example Avenue, Phoenix, AZ"
    },
    {
      "id": "d6a44c0114feda3f09f6",
      "name": "Office Park",
      "latitude": 33.6798058,
      "longitude": -112.0979211,
      "address": "21111 North 19th Avenue, Phoenix, AZ"
    },
    {
      "id": "70e9e7abaa69bae1ad07",
      "name": "Montebello/19th Ave",
      "latitude": 33.5205878,
      "longitude": -112.099723,
      "address": "North 19th Avenue, Phoenix, Arizona"
    },
    {
      "id": "f4bfc12e0ce66fa30661",
      "name": "Costco Wholesale",
      "latitude": 33.7135,
      "longitude": -112.115,
      "address": "24500 N 19th Ave, Phoenix, AZ 85085"
    }
  ],
  "revision": "f602c87a18ef44aaacc0102447134f70",
  "avoidTolls": false,
  // ... (truncated)
```

### `POST /api/navigation/search`
Queries comma's local and online geocoding/places search service.
- **Method:** `POST`
- **Headers:** `Content-Type: application/json`
- **Request Body:**
```json
{
  "query": "Phoenix Sky Harbor Airport",
  "searchId": "optional-client-search-id",
  "autocomplete": true
}
```
- **Response (200 OK):** Array of candidate destinations with coordinates, place IDs, and formatted labels.

### `POST /api/navigation/action`
Executes navigation actions. Guarantees atomic state transitions via `revision`.
- **Method:** `POST`
- **Headers:** `Content-Type: application/json`
- **Supported Actions:**
  - **Set Destination:**
    ```json
    {
      "action": "select",
      "destination": {
        "latitude": 33.4352,
        "longitude": -112.0101,
        "label": "Phoenix Sky Harbor",
        "placeId": "place_123"
      },
      "revision": "f395aa36..."
    }
    ```
  - **Select Pre-searched Place:** `{"action": "selectPlace", "placeId": "...", "label": "...", "revision": "..."}`
  - **Clear Route:** `{"action": "clear", "revision": "..."}`
  - **Switch Alternative Route:** `{"action": "selectRoute", "routeKey": "...", "revision": "..."}`
  - **Add Favorite:** `{"action": "favorite", "destination": {...}, "label": "Home", "revision": "..."}`
  - **Remove Favorite:** `{"action": "removeFavorite", "id": "...", "revision": "..."}`
  - **Clear Recents:** `{"action": "clearRecents", "revision": "..."}`
  - **Cancel Search:** `{"action": "cancelSearch", "revision": "..."}`
  - **Configure Token (Parked only):** `{"action": "configure", "mapboxToken": "pk.ey...", "revision": "..."}`

### `GET /api/navigation/offline`
Returns offline road maps cache state, downloaded geographic areas, storage consumed, and GPS lock.
- **Method:** `GET`
- **Response (200 OK):**
```json
{
  "areas": [
    {
      "latitude": 33.4484,
      "longitude": -112.0740,
      "radiusKm": 80.5,
      "name": "Home",
      "created": 1793314938.2485328,
      "refreshed": 1785251132.9443893,
      "id": "af903b3e2419480cb6ef3940c93d5f0c",
      "progress": {
        "state": "complete",
        "total": 5072,
        "done": 5072,
        "failed": 0
      }
    }
  ],
  "settings": {
    "saveDriven": true
  },
  "position": {
    "latitude": 33.4484,
    "longitude": -112.0740
  },
  "service": {
    "running": true,
    "network": "wifi",
    "failure": null,
    "noSpace": false,
  // ... (truncated)
```

### `GET /api/navigation/map/tiles/<z>/<x>/<y>.png`
Directly serves map raster tiles rendered by Galaxy for offline and low-bandwidth in-car navigation.
- **Method:** `GET`
- **Query Params:** `?theme=dark|light`
- **Response (200 OK):** `image/png`

### `GET /api/maps/setup`
### `GET /api/maps/catalog`
### `GET /api/maps/operation`
### `GET /api/maps/status`
### `POST /api/maps/start`
### `POST /api/maps/cancel`
Offline Mapbox/OSM tile package downloading engine for regions and routes.
- **`GET /api/maps/setup` Response (200 OK):**
```json
{
  "freeDiskBytes": 56973221888,
  "maxNewDiskBytes": 17179869184,
  "maxTransferBytes": 8589934592,
  "packageReady": true,
  "packageState": "ready",
  "parked": true,
  "schemaVersion": 1,
  "selectedGeneration": "69adc0c4223f422dcb23b305a9c90d0151ac4e69d31dae8146324547dc618a5e",
  "selectedSnapshot": {
    "bounds": [
      30,
      -116,
      38,
      -108
    ],
    "generation": "69adc0c4223f422dcb23b305a9c90d0151ac4e69d31dae8146324547dc618a5e",
    "storedBytes": 164427187,
    "tileCount": 1024
  },
  "snapshotReady": true
}
```
- **`GET /api/maps/catalog` Response (200 OK):**
```json
{
  "maxGroups": 64,
  "maxNewDiskBytes": 17179869184,
  "maxTransferBytes": 8589934592,
  "regions": [
    {
      "available": true,
      "bounds": [
        22,
        50,
        28,
        58
      ],
      "groups": 12,
      "name": "United Arab Emirates",
      "token": "nation.AE"
    },
    {
      "available": true,
      "bounds": [
        28,
        60,
        40,
        76
      ],
  // ... (truncated)
```
- **`POST /api/maps/start`:** `{"areaId": "<id>"}` or `{"group": "<group>"}`
- **`POST /api/maps/cancel`:** `{"operationId": "<id>"}`

---

## 5. Settings & Two-Phase Configuration

### `GET /api/settings/pages/<page>`
Reads current toggle states and parameters for a specific UI settings page.
- **Supported Pages:**
  - `hub`: Primary driving controls (Lane Centering, Steering Torque, Driver Monitoring).
  - `developer`: Developer options, SSH keys, experimental toggles.
  - `vehicle`: Vehicle-specific options (Toyota TSS2/TSS-P, radar tracks, stop & go).
  - `slc`: Speed Limit Controller parameters (`Offset1` through `Offset7`).
  - `lane`: Lane keep assist, lateral tuning, camera offset.
  - `lane_change`: Auto lane change nudging, road edge detection.
  - `profiles`: Driving personality (Aggressive, Standard, Relaxed).
  - `traffic`: Traffic stop lights, lead car follow distance.
- **Response Example (`GET /api/settings/pages/slc`):**
```json
{
  "page": "slc",
  "title": "Speed Limit Controller",
  "subtitle": "Adjust these preferences for your vehicle.",
  "parked": true,
  "vehicleIdentified": true,
  "rows": [
    {
      "label": "Speed Limit Controller",
      "value": "On",
      "choices": [
        "Off",
        "On"
      ],
      "step": 0.0,
      "minimum": 0.0,
      "maximum": 0.0,
      "unit": "",
      "available": true,
      "reason": "Adjusts the set speed to accepted speed limits using your source priority and offsets.",
      "page": "",
      "action": true,
      "confirm": false,
      "repairValue": "",
      "defaultValue": "Off",
      "resetAvailable": true,
      "displayScale": null,
      "revision": "483e65edc32a5cd3bda815c0807e83cc6769151b670ad671bafc3f226a60af78"
    },
    {
      "label": "Show speed limit signs",
      "value": "On",
      "choices": [
        "Off",
        "On"
  // ... (truncated)
```

### `POST /api/settings/preview`
Phase 1 of two-phase setting modification. Validates changes against device safety rules and returns calculated diff and temporary `view` token.
- **Method:** `POST`
- **Request Body:**
```json
{
  "page": "slc",
  "view": "xwr4nAz9YiHCjPeQNjF3kcVC1_Gl97G5",
  "values": {
    "Offset1": 5,
    "Offset2": 7
  }
}
```
- **Response (200 OK):** Returns diff validation, updated schema, and new `view` token.

### `POST /api/settings/confirm`
Phase 2 of two-phase setting modification. Commits validated settings to comma's persistent Params storage.
- **Method:** `POST`
- **Request Body:**
```json
{
  "view": "<view-token-from-preview>",
  "confirmed": true,
  "intent": "save"
}
```

### `POST /api/settings/reset-default`
Resets a specific setting or page to factory defaults.
- **Method:** `POST`
- **Request Body:** `{"page": "slc", "key": "Offset1", "view": "..."}`

### `GET /api/settings/non-defaults`
Returns every setting that currently deviates from openpilot default values.
- **Method:** `GET`
- **Response (200 OK):**
```json
{
  "view": "ZgjQuqXVp_M0SYPB_Uvlpba7B19CKhfB",
  "rows": [
    {
      "id": 0,
      "page": "curve",
      "label": "Curve Speed Controller",
      "current": "On",
      "default": "Off",
      "modified": true,
      "available": true,
      "reason": "Adjusts speed for curves while StarPilot controls acceleration and braking",
      "resetAvailable": true
    },
    {
      "id": 1,
      "page": "curve",
      "label": "Show Curve status",
      "current": "On",
      "default": "Off",
      "modified": true,
      "available": true,
      "reason": "Display preference only; it does not enable control",
      "resetAvailable": true
    },
  // ... (truncated)
```

### `POST /api/settings/recovery/preview`
### `POST /api/settings/recovery/confirm`
Two-phase bulk recovery and rollback for non-default settings. Phase 1 validates the selected row restoration against the non-default snapshot; Phase 2 commits the rollback.
- **Method:** `POST`
- **Auth Required:** Yes
- **Parked Required:** Yes
- **Phase 1 (`/preview`) Request Body:**
```json
{
  "view": "ZgjQuqXVp_M0SYPB_Uvlpba7B19CKhfB",
  "selected": [
    "Curve Speed Controller",
    "Show Curve status"
  ]
}
```
- **Phase 1 Response (200 OK):**
```json
{
  "intent": "recovery_commit_token_abc123",
  "question": "Reset 2 toggles to default?",
  "labels": [
    "Curve Speed Controller",
    "Show Curve status"
  ],
  "diff": {
    "Curve Speed Controller": { "from": "On", "to": "Off" }
  }
}
```
- **Phase 2 (`/confirm`) Request Body:**
```json
{
  "intent": "recovery_commit_token_abc123",
  "confirmed": true
}
```
- **Phase 2 Response (200 OK):**
```json
{
  "confirmed": true,
  "view": "new_view_token_xyz789"
}
```

### `GET /api/settings/backup`
### `POST /api/settings/restore`
Exports full settings configuration as a JSON bundle or restores a backup.
- **`GET /api/settings/backup` Response (200 OK):**
```json
{
  "format": "galaxy-toggles",
  "version": 1,
  "createdAt": "2026-10-30T19:11:06.915076+00:00",
  "metric": false,
  "vehicle": "TOYOTA_RAV4_TSS2_2022",
  "settings": [
    {
      "page": "aggressive",
      "key": "AggressiveFollow",
      "value": "1.25"
    },
    {
      "page": "aggressive",
      "key": "AggressiveFollowHigh",
      "value": "1.0"
    },
    {
      "page": "aggressive",
      "key": "AggressiveJerkAcceleration",
      "value": "50.0"
    },
    {
      "page": "aggressive",
      "key": "AggressiveJerkDanger",
  // ... (truncated)
```

---

## 6. Software, Updates & Version Management

### `GET /api/software/status`
Fetches current git branch, installed openpilot version, commit hashes, update channel, and updater state.
- **Method:** `GET`
- **Query Params:** `?history=0` (omits commit log history to save bandwidth)
- **Response (200 OK):**
```json
{
  "schemaVersion": 1,
  "installed": {
    "version": "0.11.3",
    "displayVersion": "7.0",
    "branch": "Dom",
    "commit": "9e7e4dde52168875158c55ec98d9bd4d6ce84338"
  },
  "updater": {
    "state": "finalizing update...",
    "targetBranch": "Dom",
    "lastCheckedAt": "2026-10-30T19:09:59.286518Z",
    "lastSuccessAt": "2026-10-30T17:39:57.120208Z",
    "lastFetchAt": "2026-10-30T17:39:57.024638Z",
    "targetChangeFound": false,
    "finalizedUpdateReady": false,
    "osUpdate": {
      "branch": "Dom",
      "required": false
    },
    "failedCount": 0,
    "fast": {
      "stage": "complete",
      "detail": "Update installed. Device is restarting; please wait for reconnection."
    }
  },
  "operations": {
    "repository": "StarPilotHQ/openpilot",
    "updaterAvailable": true,
    "progress": {
      "stage": "finalizing update...",
      "detail": "",
      "percent": null
    },
    "parked": true,
  // ... (truncated)
```

### `POST /api/software/action`
Executes software updater operations.
- **Method:** `POST`
- **Headers:** `Content-Type: application/json`
- **Supported Payloads:**
  - **Check for Updates:** `{"action": "check"}`
  - **Download Update:** `{"action": "download", "branch": "dom"}`
  - **Install Update:** `{"action": "install", "branch": "dom"}`
  - **Reboot System (Parked only):** `{"action": "reboot"}`
  - **Switch Branch/Commit:** `{"action": "version", "branch": "dom", "expectedCommit": "...", "selectedCommit": "..."}`
  - **Configure Updater:** `{"action": "preferences", "automaticDownloads": true}`

---

## 7. Models & Model Laboratory

### `GET /api/models/status`
Returns the currently loaded neural network driving model, requested model, and runtime variant.
- **Method:** `GET`
- **Response (200 OK):**
```json
{
  "schemaVersion": 1,
  "catalog": [
    {
      "id": "bundled-current",
      "name": "Regret Driven Framework V4",
      "selectable": true
    },
    {
      "id": "tr14223",
      "name": "TR14 \ud83d\udc40\ud83d\udce1",
      "selectable": true
    },
    {
      "id": "tr15223",
      "name": "TR15 \ud83d\udc40\ud83d\udce1",
      "selectable": true
    },
    {
      "id": "tr16223",
      "name": "TR16 \ud83d\udc40\ud83d\udce1",
      "selectable": true
    },
    {
      "id": "letr223",
      "name": "LeTR \ud83d\udc40\ud83d\udce1",
      "selectable": true
    },
    {
      "id": "spacelab22223",
  // ... (truncated)
```

### `GET /api/models/manager`
Lists available driving models (e.g. TR14, Regret Drive, etc.), downloaded status, active small/big models, and eGPU Jetlink status.
- **Method:** `GET`
- **Response (200 OK):**
```json
{
  "schemaVersion": 1,
  "models": [
    {
      "value": "tr14223",
      "label": "TR14 \ud83d\udc40\ud83d\udce1",
      "version": "v8",
      "series": "TR Series",
      "released": "2025-06-30",
      "communityFavorite": false,
      "userFavorite": false,
      "blacklisted": false,
      "installed": false,
      "checking": false,
      "builtin": false,
      "requiresGpu": false,
      "gpuAvailable": true,
      "small": true,
      "selectable": false,
      "downloadAvailable": true,
      "artifactSize": 89750662,
      "unavailableReason": "Download required"
    },
    {
      "value": "tr15223",
      "label": "TR15 \ud83d\udc40\ud83d\udce1",
      "version": "v8",
      "series": "TR Series",
      "released": "2025-07-11",
      "communityFavorite": false,
  // ... (truncated)
```

### `POST /api/models/active`
Switches the active driving model (requires vehicle to be parked).
- **Method:** `POST`
- **Request Body:** `{"model": "tr14223"}`

### `POST /api/models/download`
### `POST /api/models/download_all`
### `POST /api/models/cancel`
### `POST /api/models/delete`
Downloads or deletes neural network model packages.
- **Request Body (download/delete):** `{"model": "<model-id>"}`
- **Request Body (cancel):** `{"jobId": "<job-id>"}`

### `GET /api/models/laboratory`
### `POST /api/models/laboratory`
### `POST /api/models/laboratory/download`
### `POST /api/models/laboratory/delete`
Experimental model laboratory for custom test models and multi-model switching.
- **`GET /api/models/laboratory` Response (200 OK):**
```json
{
  "schemaVersion": 1,
  "chestnutReady": true,
  "isOnroad": false,
  "configuration": {
    "enabled": false,
    "lateralModel": "",
    "longitudinalModel": ""
  },
  "configurationError": "",
  "runtimeSupported": false,
  "runtimeUnavailableReason": "Pair inference is not available in this build yet.",
  "runtime": {
    "requested": false,
    "active": false,
    "lateralModel": "",
    "longitudinalModel": "",
    "error": "",
    "health": "unavailable"
  },
  "download": {
    "model": "",
    "progress": "Catalog updated",
    "downloading": false,
    "jobId": "a10e673b27f249e3bcba754caccc2865",
  // ... (truncated)
```

---

## 8. Drive State, Driving Stats & Replay

### `GET /api/drive-state/status`
### `POST /api/drive-state/action`
Inspects drive state arbitration (auto vs manual override) and permits forcing drive state for test benches.
- **`GET /api/drive-state/status` Response (200 OK):**
```json
{
  "mode": "auto",
  "revision": "f395aa3603c74187a95a48cfc0e2ff32",
  "available": true,
  "effective": "offroad",
  "overrideAllowed": true,
  "reason": null
}
```
- **`POST /api/drive-state/action`:** `{"action": "force", "state": "driving"|"parked"|"standby"}`

### `GET /api/drives/stats`
Returns aggregated driving statistics, distance traveled, engagement percentage, and trip logs.
- **Method:** `GET`
- **Query Params:** `?timezone=America/Phoenix`
- **Response (200 OK):**
```json
{
  "schemaVersion": 1,
  "isMetric": false,
  "analysis": {
    "running": false,
    "pending": 0,
    "state": "failed",
    "failed": 10,
    "quarantined": 10,
    "failures": [
      {
        "routeId": "00000003--31ec53e840",
        "reason": "Route has no observed duration",
        "attempts": 1,
        "retryStopped": true
      },
      {
        "routeId": "00000004--e658c113c8",
        "reason": "Route has no observed duration",
        "attempts": 1,
        "retryStopped": true
      },
      {
        "routeId": "00000005--bd4fb9724e",
        "reason": "Route has no observed duration",
        "attempts": 1,
        "retryStopped": true
      },
      {
        "routeId": "00000006--86425de30d",
  // ... (truncated)
```

### `POST /api/drives/ignore`
Marks a specific drive segment to be excluded from analytics.
- **Method:** `POST`
- **Request Body:** `{"routeId": "<route-id>", "ignored": true}`

### `GET /api/replay/status`
### `POST /api/replay/start`
### `POST /api/replay/control`
### `POST /api/replay/stop`
### `GET /api/replay/timeline`
### `GET /api/replay/media`
### `POST /api/replay/export-start`
### `GET /api/replay/export-download`
On-device playback engine for recorded drive logs (`rlog.zst`) with live synchronized video feeds.
- **`GET /api/replay/status` Query:** `?clientId=<client-id>`
- **`POST /api/replay/start` Body:**
```json
{
  "routeId": "7ffb6d43f2fab51b|2026-10-30--12-00-00",
  "target": "web",
  "clientId": "<client-id>",
  "segment": 0
}
```
- **`POST /api/replay/control` Body:**
```json
{
  "sessionId": "<session-id>",
  "clientId": "<client-id>",
  "action": "pause" | "seek" | "rate",
  "paused": true,
  "position": 42.5,
  "rate": 1.0
}
```
- **`POST /api/replay/stop` Body:** `{"sessionId": "...", "clientId": "..."}`
- **`GET /api/replay/media` Query:** `?sessionId=<id>&clientId=<id>&epoch=<epoch>&sequence=<seq>` (streams raw video chunk).

---

## 9. Drive Recordings & Video/Log Streaming

### `GET /api/recordings/local`
Lists all drive recordings stored on local flash storage.
- **Method:** `GET`
- **Response (200 OK):**
```json
{
  "schemaVersion": 1,
  "source": "local",
  "partialHistory": true,
  "scanIncomplete": false,
  "routes": [
    {
      "routeId": "00000000--9b84f46246",
      "segmentCount": 6,
      "provider": "comma",
      "fileTime": 1793162850.662257,
      "segments": [
        {
          "number": 0,
          "segmentName": "00000000--9b84f46246--0",
          "files": {
            "rlog": true,
            "qlog": true,
            "fcamera": true,
            "dcamera": false,
            "ecamera": true,
            "qcamera": true
          },
          "fileTime": 1793162850.662257,
          "provider": "comma",
  // ... (truncated)
```

### `GET /api/recordings/detail?routeId=<id>`
Fetches route segment metadata, start/end timestamps, distance, and video file availability.
- **Method:** `GET`

### `GET /api/recordings/segment-summary?segmentName=<seg>`
Returns detailed CAN and sensor summaries for a single 1-minute route segment.
- **Method:** `GET`

### `GET /api/recordings/route/<routeId>/<camera>`
### `GET /api/recordings/files/<segmentName>/<filename>`
### `GET /api/recordings/logs/<routeId>`
Streams recorded H.264 / HEVC video files (`fcamera.hevc`, `dcamera.hevc`, `qcamera.ts`) and downloads raw route log archives (`rlog.zst`, `qlog.zst`).
- **Camera targets:** `"road"`, `"wideRoad"`, `"driver"`, `"qcamera"`

### `POST /api/recordings/action`
Preserves or unpreserves routes from automatic circular storage deletion.
- **Method:** `POST`
- **Request Body:** `{"action": "preserve"|"unpreserve", "routeId": "<route-id>"}`

### `POST /api/recordings/delete-videos`
Purges heavy video files for a route while retaining telemetry logs.
- **Method:** `POST`
- **Request Body:** `{"routeId": "<route-id>"}`

---

## 10. Sentry Mode & Push Notifications

### `GET /api/sentry/events`
Lists recorded sentry events (impact, motion, wake triggers) with timestamps and snapshot links.
- **Method:** `GET`
- **Response (200 OK):**
```json
{
  "schemaVersion": 1,
  "source": "local",
  "scanIncomplete": false,
  "capacity": 512,
  "events": [],
  "storageBytes": 0,
  "storageIncomplete": false
}
```

### `GET /api/sentry/image/<eventId>/<camera>`
Retrieves JPEG thumbnail captures associated with a sentry trigger event.
- **Method:** `GET`
- **Response (200 OK):** `image/jpeg`

### `POST /api/sentry/events/delete`
Deletes sentry records.
- **Method:** `POST`
- **Request Body:** `{"eventId": "<id>"}` or `{"all": true, "confirmed": true}`

### `GET /api/sentry/notifications`
### `POST /api/sentry/notifications`
Configures WebPush and phone notification relays for instant sentry alerts.
- **`GET /api/sentry/notifications` Response (200 OK):**
```json
{
  "schemaVersion": 1,
  "channels": {
    "webPush": {
      "enabled": false,
      "configured": false,
      "pending": 0,
      "lastState": "idle",
      "lastError": ""
    },
    "discord": {
      "enabled": false,
      "configured": false,
      "pending": 0,
      "lastState": "idle",
      "lastError": ""
    },
    "webhook": {
      "enabled": false,
      "configured": false,
      "pending": 0,
      "lastState": "idle",
      "lastError": ""
    },
    "ntfy": {
  // ... (truncated)
```
- **Supported `POST` Actions:**
  - `configure`: `{"action": "configure", "channel": "appPush", "enabled": true, "url": "...", "token": "..."}`
  - `test`: `{"action": "test", "target": "all"|"app", "id": "..."}`
  - `simulate`: `{"action": "simulate"}` (triggers test sentry alarm)
  - `subscribe`: `{"action": "subscribe", "subscription": {"endpoint": "...", "keys": {...}}}`

---

## 11. Vehicle Controls, Toyota Tools & Controllers

### `GET /api/vehicle-selection`
### `POST /api/vehicle-selection/preview`
### `POST /api/vehicle-selection/confirm`
Inspects, previews, and overrides automatic CAN car fingerprinting.
- **`GET /api/vehicle-selection` Response (200 OK):**
```json
{
  "version": 1,
  "parked": true,
  "readable": true,
  "valid": true,
  "selected": null,
  "selectedLabel": "Auto detection",
  "selectionMode": "auto",
  "reported": {
    "platform": "TOYOTA_RAV4_TSS2_2022",
    "make": "Toyota",
    "label": "Toyota Rav4 Tss2 2022",
    "origin": "detected"
  },
  "view": "1uCFNkA1ZVjC7SXQE4jNc2SM5tqgBN8Y",
  "choices": [
    {
      "platform": "ACURA_ADX",
      "make": "Acura",
      "label": "Acura ADX 2025-26"
    },
    {
      "platform": "ACURA_ILX",
      "make": "Acura",
      "label": "Acura Ilx"
    },
    {
      "platform": "ACURA_INTEGRA",
      "make": "Acura",
      "label": "Acura Integra 2023-26"
  // ... (truncated)
```
- **Preview Request:** `{"platform": "TOYOTA_RAV4_2019", "view": "..."}`
- **Confirm Request:** `{"intent": "save", "confirmed": true}`

### `GET /api/toyota-tools`
### `POST /api/toyota-tools/action`
Specialized utilities for Toyota/Lexus platforms (TSS security keys, radar recalibration, DSU bypass).
- **`GET /api/toyota-tools` Response (200 OK):**
```json
{
  "version": 1,
  "visible": true,
  "parked": true,
  "security": false,
  "view": "HhEB-IwMyxByVjLbWb_uIJ5JXmrM9AUY",
  "keys": [],
  "activePresent": false,
  "readable": true,
  "lock": true,
  "unlock": true
}
```

### `GET /api/controllers/status`
### `POST /api/controllers/action`
Manages hardware joystick/gamepad controls (drive-by-wire controller input for testing).
- **`GET /api/controllers/status` Response (200 OK):**
```json
{
  "version": 1,
  "available": true,
  "editable": true,
  "enabled": false,
  "revision": "6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d",
  "valid": true,
  "devices": [],
  "slots": [
    {
      "index": 0,
      "label": "Favorite 1",
      "key": null,
      "available": false
    },
    {
      "index": 1,
      "label": "Favorite 2",
      "key": null,
      "available": false
    },
    {
      "index": 2,
      "label": "Favorite 3",
      "key": null,
      "available": false
    },
    {
      "index": 3,
      "label": "Button 1",
  // ... (truncated)
```

### `POST /api/cameras/snapshot`
Captures an immediate live still image from on-comma cameras (Parked only).
- **Method:** `POST`
- **Request Body:** `{"camera": "road" | "driver" | "wideRoad"}`
- **Response (200 OK):** `image/jpeg`

---

## 12. StarPort (Android Auto Wireless Projection)

*Note: StarPort replaces earlier experimental `/api/android-auto/*` routes in current builds.*

### `GET /api/starport/setup`
Inspects Android Auto wireless projection subsystem status, Bluetooth readiness, and APK package installation.
- **Method:** `GET`
- **Response (200 OK):**
```json
{
  "enabled": false,
  "bluetoothEnabled": true,
  "parked": true,
  "installReady": true,
  "serviceReady": false,
  "identity": {
    "installed": false,
    "message": "No Android Auto identity installed"
  },
  "import": {
    "state": "idle"
  },
  "download": {
    "version": "17.9.664044-release",
    "url": "https://download.pureapk.com/b/XAPK/Y29tLmdvb2dsZS5hbmRyb2lkLnByb2plY3Rpb24uZ2VhcmhlYWRfMTc5NjY0MDQ0X2U0Yjg5MGQ1?_fn=QW5kcm9pZCBBdXRvX3YxNy45LjY2NDA0NC1yZWxlYXNlX2Fwa3B1cmUuY29tLnhhcGs&as=6a0fa8b73122efe9dac10d0433fbe4976ae6391d&ai=507014343&at=1793386397&_sa=ai%2Cat&k=f4a0df3e9bd4bea8cd641986247699e06ae78a9d&_p=Y29tLmdvb2dsZS5hbmRyb2lkLnByb2plY3Rpb24uZ2VhcmhlYWQ&c=1%7CAUTO_AND_VEHICLES%7CZGV2PUdvb2dsZSUyMExMQyZ0PXhhcGsmcz01MDUyODk0MyZ2bj0xNy45LjY2NDA0NC1yZWxlYXNlJnZjPTE3OTY2NDA0NA",
    "size": 50528943
  },
  "downloadPending": false,
  "maxUploadBytes": 268435456,
  "wiredAvailable": true,
  "steps": [
    "The comma acts as the Android Auto phone; the car is the receiver.",
    "Enable StarPort, then upload your Android Auto APK, XAPK, or APKM on-road or off-road.",
    "Wait for on-device certificate/key verification; replace it before expiry.",
    "In offroad mode or Park, pair the car over Bluetooth. The car then gives the comma its Wi-Fi access point.",
    "Start wireless projection after the car is selected, or turn on Wired USB Connection and plug the car data USB port into the comma."
  ]
}
```

### `GET /api/starport/pairing/status`
### `POST /api/starport/pairing`
### `POST /api/starport/pairing/select`
### `POST /api/starport/pairing/response`
### `POST /api/starport/pairing/cancel`
Coordinates wireless Android Auto Bluetooth hands-free handshake with the vehicle head unit.
- **`GET /api/starport/pairing/status` Response (200 OK):**
```json
{
  "pairing": {
    "active": false,
    "receiver": null,
    "prompt": null,
    "approved": false
  },
  "selectedReceiver": null,
  "runtime": null
}
```
- **Select Receiver:** `{"address": "AA:BB:CC:DD:EE:FF"}`
- **Pairing Response:** `{"prompt_id": "...", "accepted": true, "value": "..."}`

### `GET /api/starport/receivers`
Lists car head unit receivers discovered or previously paired.
- **Method:** `GET`

### `POST /api/starport/enable`
Enables or disables on-comma Android Auto wireless projection daemon.
- **Method:** `POST`
- **Request Body:** `{"enabled": true}`

### `POST /api/starport/control`
Sends runtime lifecycle commands (start, stop, reload) to the `starportd` daemon.
- **Method:** `POST`
- **Request Body:** `{"action": "start"|"stop"|"restart"}`

### `GET /api/starport/layout`
### `POST /api/starport/layout`
Custom layout document and screen geometry configuration for StarPort projection.
- **`GET /api/starport/layout` Response (200 OK):**
```json
{
  "version": 1,
  "document": null,
  "defaults": null,
  "metadata": null,
  "screen": null,
  "revision": "6e021b9a6c37596fa4c7ff6c07c5f83d087189fb65b37a393c4db5fc83ade109",
  "available": false,
  "editable": false,
  "valid": false,
  "reason": "Enable StarPort to edit its layout",
  "retainedLayout": false,
  "colors": {
    "palette": {
      "cardFill": "#000000A6",
      "cardBorder": "#C4CDD0B4",
      "text": "#FFFFFFFF"
    },
    "widgetColors": {},
    "roadColors": {}
  }
}
```

### `GET /api/starport/logs`
Fetches runtime log sessions for wireless projection debugging.
- **Method:** `GET`
- **Response (200 OK):**
```json
{
  "schemaVersion": 1,
  "sessions": [],
  "others": []
}
```

### `POST /api/starport/upload`
Uploads projection package binaries or assets.
- **Method:** `POST` (Multipart / Binary)

### `DELETE /api/starport/identity`
Clears stored Bluetooth identity certificates and pairings.
- **Method:** `DELETE`

---

## 13. Spotify & Audio Subsystem

### `GET /api/spotify/setup`
Inspects on-device Spotify daemon status, installed packages, audio pipeline, and Spotify API key presence.
- **Method:** `GET`
- **Response (200 OK):**
```json
{
  "enabled": false,
  "parked": true,
  "release": {
    "installed": false
  },
  "keyPresent": false,
  "audioReady": true,
  "preferences": {
    "deviceName": "StarPilot",
    "cacheMb": 256,
    "initialVolume": 100,
    "output": "bluetooth",
    "outputAddress": "",
    "outputName": "",
    "source": "phone",
    "phoneAddress": "70:28:7D:65:E3:6A",
    "phoneName": "Pixel 11 Pro XL",
    "pauseMute": 0.5,
    "skipMute": 0.25,
    "muteMax": 1.5
  },
  "devices": [
    {
      "address": "70:28:7D:65:E3:6A",
      "name": "Pixel 11 Pro XL",
      "connected": false
    }
  ],
  "service": null,
  // ... (truncated)
```

### `POST /api/spotify/enable`
Enables or disables Spotify daemon.
- **Method:** `POST`
- **Request Body:** `{"enabled": true}`

### `POST /api/spotify/key`
Provisions Spotify developer credentials / auth tokens.
- **Method:** `POST`
- **Request Body:** `{"key": "<client-secret-or-token>"}`

### `POST /api/spotify/preferences`
Configures audio output sink and behavior:
- **Method:** `POST`
- **Request Body:**
```json
{
  "source": "starport" | "local",
  "phoneAddress": "AA:BB:CC:DD:EE:FF",
  "output": "bluetooth" | "starport" | "test",
  "outputAddress": "AA:BB:CC:DD:EE:FF",
  "deviceName": "ParamCar Spotify",
  "cacheMb": 512,
  "onroadMute": 0.2,
  "offroadMute": 1.0
}
```

### `POST /api/spotify/control`
Controls media playback.
- **Method:** `POST`
- **Request Body:** `{"action": "play" | "pause" | "next" | "prev"}`

### `POST /api/spotify/maintenance`
Executes maintenance routines on the Spotify service.
- **Method:** `POST`
- **Request Body:** `{"action": "remove_key" | "clear_cache" | "reset_session"}`

### `DELETE /api/spotify`
Removes Spotify service configuration completely.
- **Method:** `DELETE`

---

## 14. Bluetooth Management

### `GET /api/bluetooth/status`
Returns host Bluetooth adapter state, powered mode, discovery status, and bonded devices.
- **Method:** `GET`
- **Response (200 OK):**
```json
{
  "version": 1,
  "available": true,
  "parked": true,
  "powered": true,
  "discovering": false,
  "devices": [
    {
      "address": "70:28:7D:65:E3:6A",
      "name": "Pixel 11 Pro XL",
      "paired": true,
      "connected": false,
      "trusted": true
    }
  ],
  "errorCode": null,
  "pairing": null,
  "adapter": true,
  "adapterAddress": "39:90:21:64:07:00",
  "adapterName": "comma-c9a4d143",
  "claim": {
    "open": false,
    "left": 0,
    "claimed": "",
    "paired": false
  }
}
```

### `POST /api/bluetooth/action`
Executes Bluetooth adapter operations: scan, pair, unpair, connect, or disconnect.
- **Method:** `POST`
- **Supported Payloads:**
  - **Scan:** `{"action": "scan", "enabled": true}`
  - **Pair:** `{"action": "pair", "address": "AA:BB:CC:DD:EE:FF"}`
  - **Unpair:** `{"action": "unpair", "address": "AA:BB:CC:DD:EE:FF"}`
  - **Connect / Disconnect:** `{"action": "connect"|"disconnect", "address": "..."}`

---

## 15. Companion App Integration & Static Data

### `GET /api/companion/status`
Reports companion app uplink lease, bridge protocol version, and connection state.
- **Method:** `GET`
- **Response (200 OK):**
```json
{
  "protocol": 1,
  "enabled": false,
  "connected": false,
  "active": false,
  "state": "disabled",
  "reason": "",
  "bytes": 0,
  "version": null,
  "appAvailable": false,
  "shippedVersion": "",
  "updateAvailable": false
}
```

### `POST /api/companion/settings`
Enables or disables mobile uplink sharing and companion telemetry syncing over local Wi-Fi / hotspot.
- **Method:** `POST`
- **Auth Required:** Yes
- **Headers:** `Content-Type: application/json`
- **Request Body:**
```json
{
  "enabled": true
}
```
- **Response (200 OK):**
```json
{
  "protocol": 1,
  "enabled": true,
  "connected": false,
  "active": false,
  "state": "enabled",
  "reason": "",
  "bytes": 0,
  "version": null,
  "appAvailable": false,
  "shippedVersion": "",
  "updateAvailable": false
}
```

### `GET /api/companion/bluetooth-ready`
Checks if the device is currently in a state ready to accept BLE pairing from the companion app.
- **Method:** `GET`
- **Response (200 OK):**
```json
{
  "schema": 1,
  "ready": true,
  "code": "ready",
  "message": "Ready to pair."
}
```

### `GET /api/companion/runtime` (Alias of `/data/runtime.json`)
Ultra-lightweight liveness probe for monitoring apps.
- **Method:** `GET`
- **Response (200 OK):**
```json
{
  "schemaVersion": 1,
  "monitor": "local",
  "instance": "20f313d2f6efbb89"
}
```

### `GET /data/catalog.json`
Catalog manifest of all Galaxy tools, navigation tabs, and availability constraints (`"local-only"`, `"always"`, `"partial-preview"`).
- **Method:** `GET`
- **Response (200 OK):**
```json
{
  "mode": "offline-preview",
  "tools": [
    {
      "name": "Bluetooth",
      "path": "/bluetooth",
      "icon": "bi-bluetooth",
      "description": "Adapter power, saved devices and connections",
      "availability": "local-only"
    },
    {
      "name": "Cameras & Monitoring",
      "path": "/cameras",
      "icon": "bi-camera-video",
      "description": "Live cabin previews, Side Camera, Sentry motion, and V-ASM settings",
      "availability": "local-only",
      "children": [
        {
          "name": "Blind Spot Camera and Preview",
          "path": "/cameras/pip",
          "icon": "bi-camera-video",
          "description": "Adjust the camera crop with a live cabin preview.",
          "availability": "local-only"
        },
        {
          "name": "Sentry",
          "path": "/cameras/events",
          "icon": "bi-shield",
          "description": "View motion events, configure saved motion settings, and manage notifications.",
          "availability": "local-only"
  // ... (truncated)
```

### `GET /api/companion/web-manifest`
Lists asset file hashes and commit signatures for offline web caching by mobile companion apps.
- **Method:** `GET`
- **Response (200 OK):**
```json
{
  "schema": 1,
  "commit": "9e7e4dde52168875158c55ec98d9bd4d6ce84338",
  "files": [
    {
      "path": "assets/galaxy-icon-180.png",
      "size": 5884,
      "sha256": "1c604af80a274420fe328a1388fa6fb92eef8b7ca63e5cfee884c5b2609e9803"
    },
    {
      "path": "assets/galaxy-icon-192.png",
      "size": 6377,
      "sha256": "b4f20309399804dc200044add646338ddbc8daebfc0bbdb8487e1387b97dd553"
    },
    {
      "path": "assets/galaxy-icon-512.png",
      "size": 20646,
      "sha256": "2ed9feda3156bfbd125dc6e543daa7051faefa95ea7c1703ac5d81056bf91538"
    },
    {
  // ... (truncated)
```

### `POST /api/companion/uplink`
Grants an internet uplink lease via the phone's SOCKS5 proxy when connected to the comma hotspot.
- **Method:** `POST`
- **Request Body:**
```json
{
  "port": 1080,
  "username": "<socks-user>",
  "password": "<socks-pass>"
}
```

### `POST /api/companion/location`
Injects phone GPS fixes into the comma's location daemon (enables `gpsSource = "phone"` when internal comma GPS is degraded).
- **Method:** `POST`
- **Request Body:**
```json
{
  "latitude": 33.4484,
  "longitude": -112.0740,
  "accuracy": 3.5,
  "bearing": 182.0,
  "speed": 18.2,
  "altitude": 380.0,
  "time": 1730315415000
}
```

---

## 16. Sound Packs & Driver Monitoring (FLM)

### `GET /api/sounds`
Lists alert sound packs installed on the device (e.g. Duck, StarPilot default).
- **Method:** `GET`
- **Response (200 OK):**
```json
{
  "parked": true,
  "packs": [
    {
      "id": "duck",
      "name": "Duck",
      "installed": true
    },
    {
      "id": "old_engage_sounds",
      "name": "Old Engage Sounds",
      "installed": false
    },
    {
      "id": "tesla",
      "name": "Tesla",
      "installed": true
    },
    {
      "id": "weeb",
      "name": "Weeb",
      "installed": false
    }
  ],
  "job": null
}
```

### `POST /api/sounds/download`
### `POST /api/sounds/cancel`
Downloads custom alert sound packs.
- **Download Body:** `{"pack": "duck"}`
- **Cancel Body:** `{"job": "<job-id>"}`

### `GET /api/flm/training-results`
Fetches Driver Monitoring Neural Network (FLM) calibration metrics and face detection history.
- **Method:** `GET`
- **Response (200 OK):**
```json
{
  "version": 1,
  "current": {
    "fresh": false,
    "ageSeconds": 11124.158371082,
    "enabled": true,
    "status": "collecting",
    "reason": "source_configuration_uncertain",
    "progress": {
      "samples": 0,
      "blocks": 0,
      "requiredBlocks": 8
    },
    "fit": null,
    "application": {
      "applied": false,
      "reason": "report_stale"
    },
    "details": {
      "Vehicle": "TOYOTA_RAV4_TSS2_2022",
      "Collection session": "",
      "Current objective": "None",
      "Total retained blocks": 0,
      "Current block samples": 0,
      "Maneuver block coverage": {},
      "Required maneuver groups": [],
      "Maximum block samples": 240,
      "Block sealed; waiting for a different maneuver": false,
      "Discarded collection sessions": 0,
      "Collection reset reasons": {},
  // ... (truncated)
```

### `GET /api/favorites/slots`
### `POST /api/favorites/slots`
Reads and assigns shortcut destination slots to quick-access launcher tiles.
- **`GET /api/favorites/slots` Response (200 OK):**
```json
{
  "slots": [
    {
      "enabled": false,
      "show_onroad": false,
      "key": null,
      "label": ""
    },
    {
      "enabled": false,
      "show_onroad": false,
      "key": null,
      "label": ""
    },
    {
      "enabled": false,
      "show_onroad": false,
      "key": null,
      "label": ""
    }
  ],
  "states": [
    {
      "index": 0,
      "kind": "action",
      "stateLabel": "Not assigned",
      "available": false,
      "reason": "Choose a control"
    },
    {
  // ... (truncated)
```

### `GET /api/ui/layout`
### `POST /api/ui/layout`
### `POST /api/ui/layout/preview`
Reads or modifies the on-road UI widget layout document (speedometer, map widget, compass, thermal badges).
- **`GET /api/ui/layout` Response (200 OK):**
```json
{
  "document": {
    "version": 12,
    "clock24Hour": false,
    "palette": {
      "cardFill": "#000000A6",
      "cardBorder": "#C4CDD0B4",
      "text": "#FFFFFFFF"
    },
    "roadColors": {
      "large": {},
      "compact": {}
    },
    "widgetColors": {
      "large": {},
      "compact": {}
    },
    "layouts": {
      "large": {
        "current_speed": {
          "x": 640,
          "y": 30,
          "enabled": true
        },
        "cruise_limits": {
          "x": 88,
          "y": 75,
          "enabled": true
        },
        "speed_limit_actions": {
  // ... (truncated)
```

---

## 17. BLE Bridge Transport Restrictions

When connected via Bluetooth Low Energy (BLE bridge) rather than Wi-Fi / LAN, high-bandwidth endpoints return `501 Not Implemented`:
- `/api/recordings/*` (Video files and raw logs)
- `/api/replay/*` (Drive replay media streaming)
- `/api/cameras/*` (High-resolution camera snapshots)
- `/api/starport/upload` (APK package uploads)
- `/api/spotify/upload` (Spotify package uploads)
- `/api/navigation/map/tiles/*` (Map raster tiles)
- `/api/companion/uplink` (SOCKS5 proxy routing)
- `/api/companion/app.apk` (Companion APK downloads)
- `/api/auth/login` / `/api/auth/logout` (Remote password auth)

---

## 18. Summary of API Route Dispatch Table

| Category | HTTP Method | Endpoint Path | Auth Required | Parked Required | Two-Phase Commit |
|---|---|---|:---:|:---:|:---:|
| **Auth & Session** | GET | `/api/auth/session` | No | No | No |
| | POST | `/api/auth/login` | No | No | No |
| | POST | `/api/auth/logout` | Yes | No | No |
| | GET | `/api/local-access` | Yes | No | No |
| | GET / POST | `/api/galaxy/status` | Yes | No | No |
| | POST | `/api/galaxy/pair` / `unpair` | Yes | No | No |
| | GET | `/api/galaxy/session` | Yes | No | No |
| | GET / POST | `/api/galaxy/device-name` | Yes | No | No |
| | GET / POST | `/api/galaxy/hotspot` | Yes | No | No |
| | GET | `/api/galaxy/qr.svg`, `session/qr.svg` | Yes | No | No |
| | GET / POST | `/api/connect/provider`, `proxy` | Yes | No | No |
| **System & Telemetry** | GET | `/api/device/state` | Yes | No | No |
| | GET | `/api/weather` | Yes | No | No |
| | GET | `/api/system/monitor` | Yes | No | No |
| | GET | `/api/plots/live` | Yes | No | No |
| | GET | `/api/troubleshoot` | Yes | No | No |
| | GET | `/api/tmux/live` | Yes | No | No |
| | GET / POST | `/api/crash-reports` (list / delete) | Yes | No | No |
| | GET | `/api/crash-reports/<id>` | Yes | No | No |
| **Navigation & Maps** | GET | `/api/navigation/status` | Yes | No | No |
| | POST | `/api/navigation/search` | Yes | No | No |
| | POST | `/api/navigation/action` | Yes | No | Revision guarded |
| | GET | `/api/navigation/offline` | Yes | No | No |
| | GET | `/api/navigation/map/tiles/<z>/<x>/<y>.png` | Yes | No | No |
| | GET / POST | `/api/maps/setup`, `catalog`, `operation`, `status`, `start`, `cancel` | Yes | No | No |
| **Settings** | GET | `/api/settings/pages/<page>` | Yes | No | No |
| | POST | `/api/settings/preview` | Yes | No | Phase 1 |
| | POST | `/api/settings/confirm` | Yes | Yes (most) | Phase 2 |
| | POST | `/api/settings/reset-default` | Yes | Yes | No |
| | GET | `/api/settings/non-defaults` | Yes | No | No |
| | POST | `/api/settings/recovery/preview`, `confirm` | Yes | Yes | Two-Phase |
| | GET / POST | `/api/settings/backup`, `restore` | Yes | Yes | No |
| **Software** | GET | `/api/software/status` | Yes | No | No |
| | POST | `/api/software/action` | Yes | Yes (install/reboot) | No |
| **Models** | GET | `/api/models/status`, `manager`, `laboratory` | Yes | No | No |
| | POST | `/api/models/active` | Yes | Yes | No |
| | POST | `/api/models/download`, `download_all`, `cancel`, `delete`, `preferences` | Yes | No | No |
| | POST | `/api/models/laboratory`, `.../download`, `.../delete` | Yes | Yes | No |
| **Driving & Replay** | GET / POST | `/api/drive-state/status`, `action` | Yes | No | No |
| | GET / POST | `/api/drives/stats`, `ignore` | Yes | No | No |
| | GET / POST | `/api/replay/status`, `start`, `control`, `stop`, `timeline`, `media`, `export-*` | Yes | Yes (start) | No |
| **Recordings** | GET | `/api/recordings/local`, `detail`, `segment-summary` | Yes | No | No |
| | GET | `/api/recordings/route/...`, `files/...`, `logs/...` | Yes | No | No |
| | POST | `/api/recordings/action`, `delete-videos` | Yes | No | No |
| **Sentry** | GET / POST | `/api/sentry/events`, `delete` | Yes | No | No |
| | GET | `/api/sentry/image/<eventId>/<camera>` | Yes | No | No |
| | GET / POST | `/api/sentry/notifications` | Yes | No | No |
| **Vehicle & Controls** | GET / POST | `/api/vehicle-selection` (preview / confirm) | Yes | Yes | Two-Phase |
| | GET / POST | `/api/toyota-tools`, `action` | Yes | Yes | No |
| | GET / POST | `/api/controllers/status`, `action` | Yes | No | No |
| | POST | `/api/cameras/snapshot` | Yes | Yes | No |
| **StarPort (AA)** | GET / POST | `/api/starport/setup`, `pairing/*`, `receivers`, `enable`, `control`, `layout`, `logs` | Yes | Yes (pair/enable) | No |
| | POST | `/api/starport/upload` | Yes | Yes | No |
| | DELETE | `/api/starport/identity` | Yes | Yes | No |
| **Spotify** | GET / POST | `/api/spotify/setup`, `enable`, `key`, `preferences`, `control`, `maintenance` | Yes | No | No |
| | DELETE | `/api/spotify` | Yes | Yes | No |
| **Bluetooth** | GET / POST | `/api/bluetooth/status`, `action` | Yes | No | No |
| **Companion** | GET / POST | `/api/companion/status`, `settings`, `bluetooth-ready`, `runtime`, `uplink`, `location` | Yes | No | No |
| | GET | `/data/runtime.json`, `/data/catalog.json` | No | No | No |
| **UI & Sounds** | GET / POST | `/api/sounds`, `download`, `cancel` | Yes | Yes | No |
| | GET | `/api/flm/training-results` | Yes | No | No |
| | GET / POST | `/api/favorites/slots` | Yes | No | Revision guarded |
| | GET / POST | `/api/ui/layout`, `preview` | Yes | No | Revision guarded |
