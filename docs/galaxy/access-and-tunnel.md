---
okf_version: "0.2"
type: State Report
title: "Galaxy remote access and tunnel state"
description: "Validated 2026-11-04 on live device at http://192.168.68.16:8082: frpc reverse tunnel slug <device-slug>, LAN wlan0 IP 192.168.68.16, and hotspot 172.31.254.1."
tags: [galaxy, remote-access, tunnel, frpc]
status: stable
trust_tier: authoritative
generated: { by: "jarvis", at: "2026-11-04" }
verified: { by: "jarvis:live-device-check", at: "2026-11-04" }
---

# Galaxy remote access and tunnel state

## The Links & Base URLs (Verified Live 2026-11-04)

- **Local LAN Access:** `http://192.168.68.16:8082/` (comma interface `wlan0`, DHCP assigned).
- **Wi-Fi Hotspot Access:** `http://172.31.254.1:8082/` (SSID: `TheGalaxy-XXXX`, Dongle ID suffix: `ec34`).
- **Remote Reverse Tunnel:** `https://galaxy.firestar.link/<device-slug>` (paired frpc reverse tunnel to firestar.link gateway).
- **Session Cookie:** `galaxy_session=<tunnel_id>:<token>` (automatically established for local LAN/hotspot peers; password authenticated for remote tunnel peers).

## Session Model (from the SG Source)

- SG Galaxy remote access runs over an **frpc tunnel** (`frpc_linux_arm64`) with a pairing record on the comma (`/api/galaxy/status` reports `paired: true`, the tunnel `url`, and `tunnelClientAvailable: true`).
- **Local peers** (LAN `192.168.68.16`, comma hotspot `172.31.254.1`, on-device BLE bridge) get an authenticated session automatically upon `GET /api/auth/session`.
- **Remote/proxied peers get 401** until they sign in with the Galaxy password (`POST /api/auth/login`). API probes through the tunnel without a session return 401 — this is by design, not breakage.

## State on 2026-11-04 (Live-Checked on Device)

- Comma LAN address: **192.168.68.16 (wlan0)** — `http://192.168.68.16:8082/`.
- Device Header: **"Parked · Local connection"**.
- Tunnel Status (`/api/galaxy/status`): `{"paired": true, "url": "https://galaxy.firestar.link/<device-slug>", "tunnelClientAvailable": true, "legacyPassword": false, "legacyPairingAvailable": false}`.
- Hotspot Status (`/api/galaxy/hotspot`): Broadcast disabled (`"enabled": false`), network name `"TheGalaxy-XXXX"`.
- Cloud Telemetry Provider (`/api/connect/provider`): Active and selected: `"comma"` (`https://connect.comma.ai`).

## Related
- [endpoints.md](endpoints.md) · [branch-state.md](branch-state.md)
