---
okf_version: "0.2"
type: Integration Map
title: "ParamAuto typed-API map"
description: "How each ParamAuto data need maps to an SG typed Galaxy endpoint, with exact payload shapes and known gaps."
tags: [galaxy, api, paramauto]
status: stable
trust_tier: authoritative
generated: { by: "jarvis", at: "2026-10-27" }
---

# ParamAuto → SecretGoodStarPilot typed-API map

Derived 2026-10-27 by reading the SG Galaxy source on the Dom branch (which is now
identical to SecretGoodStarPilot): galaxy/server.py route dispatch,
navigation/owner.py, navigation/status.py, navigation/runtime.py,
navigation/route_engine.py, navigation/wire.py + legacy_navigation.capnp,
galaxy/plots.py, galaxy/device_state.py, galaxy/settings.py.

Session model: direct local connections (phone on LAN / comma hotspot / on-device
BLE bridge) are granted a session automatically — no login needed for reads or for
navigation actions from a local peer. Remote/proxied access (Galaxy Cloud) signs in
via POST /api/auth/login with the saved Galaxy password — see the Cloud sign-in
section below.

## Cloud sign-in (POST /api/auth/login)
Body must be exactly {"password": "..."} (6–255 chars; any extra or missing key → 400).
Success answers 200 with `Set-Cookie: galaxy_session=<token>; HttpOnly; SameSite=Strict;
Path=/; Max-Age=1800`; later requests send `Cookie: galaxy_session=<token>` — to the
cloud host only, never to LAN/hotspot/BLE endpoints. Sessions are short-lived
(~30 min): a 401 from a cloud request means sign in once more and retry once.
Implemented in ParamAuto by `GalaxyCloudSession` (password + token in the app's
private preferences; neither is ever logged).

## Poll endpoints (GET, JSON)

### GET /api/navigation/status  (append ?routeKey=<16hex> once known)
{
  "enabled": bool, "status": "disabled|needsKey|noDestination|waitingForLocation|routing|routeUnavailable|guiding|arrived",
  "destination": {"id": str, "name": str, "latitude": num, "longitude": num, "address": str?} | null,
  "revision": str,                       // needed for every navigation action
  "instruction": {                       // null unless guiding/arrived
    "text": str,                         // street / instruction text
    "maneuverType": str,                 // Mapbox maneuver type ("turn", "arrive", ...)
    "maneuverModifier": str,             // "left", "slight right", ... ("none" possible)
    "distanceMeters": num,               // distance to THIS maneuver
    "remainingDistanceMeters": num,      // TOTAL remaining distance (server-computed)
    "remainingDurationSeconds": num      // remaining duration of current step (prorated)
  } | null,
  "route": [{"latitude": num, "longitude": num}, ...],   // selected route polyline
  "alternatives": [{"index": int, "durationSeconds": num, "distanceMeters": num, "geometry": [...]}, ...],
  "selectedRoute": int,
  "location": {"latitude": num, "longitude": num, "bearing": num?, "source": str, "validForMs": num} | null,
  "gpsSource": str, "phoneGps": {...}, "favorites": [...], "recents": [...],
  "hasKey": bool, "isMetric": bool, "network": "online|offline|unknown",
  "routeKey": "16hex"                    // if a poll sends the current routeKey,
                                         // response omits route+alternatives and adds "routeUnchanged": true
}
No lane-guidance fields exist in the instruction (same gap as the params era).

### GET /api/plots/live
{
  "speedMps": num | null,                // REAL vehicle speed (carState vEgo or deviceMotion)
  "speedSource": "carState|deviceMotion|unavailable",
  "controlsActive": bool | null,         // openpilot engaged
  "lateralControlActive": bool | null, "longitudinalControlActive": bool | null,
  "desiredLongitudinalAccel": num | null, "actualLongitudinalAccel": num | null,
  ... PID terms ..., "poseFresh": bool, "controlsFresh": bool
}

### GET /api/device/state
{"state": "parked|standby|driving" | null, "maxAgeMs": int}   // replaces /api/device/status

### GET /api/software/status
Version/branch/update info for the comma (parse defensively).

### GET /api/settings/pages/slc
Settings page rows with current values. SLC offset keys are literally
"Offset1".."Offset7" (mph values, same semantics as the old params).
Parse rows defensively; if unreadable, offsets stay unavailable (never guess).

## Write endpoint (POST, JSON)

### POST /api/navigation/action
Body always includes "action" and "revision" (from the latest status snapshot).
- {"action": "select", "destination": {"name": str, "latitude": num, "longitude": num, "address": str?}, "revision": str}
- {"action": "clear", "revision": str}
- {"action": "selectRoute", "index": int, "revision": str}
- {"action": "favorite", "destination": {...}, "revision": str}
Responses: 200 + fresh snapshot; 409 on revision conflict (re-fetch status, retry once);
401 if no session (from a local peer this should not happen; on Cloud it means the
session expired — sign in again and retry).

### POST /api/navigation/search
{"query": str} → {"results": [...]} (not used in v1; app keeps its existing search.)

## Known gaps on SG (do not fake)
- Live accepted speed limit is NOT exposed by any typed GET (it was derived from a
  param before; the param is gone). Show limit as unavailable on SG unless a source
  is found. Offsets ARE available via the slc settings page.
- Mapbox token is write-only (hasKey flag only). App keeps its existing token sources.
- Galaxy Cloud (firestar portal) serves SG typed APIs only with a Galaxy password
  sign-in (`GalaxyCloudSession` in ParamAuto); without a saved password, SG
  mode is LAN/hotspot/BLE only.
