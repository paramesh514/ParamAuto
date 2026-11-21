---
okf_version: "0.2"
type: Protocol Specification
title: "Galaxy BLE bridge protocol"
description: "How companion apps tunnel Galaxy API requests over Bluetooth LE: GATT layout, pairing key, session handshake, encrypted envelope, fragmentation, and response framing."
tags: [bluetooth, ble, galaxy, protocol, companion]
status: stable
trust_tier: authoritative
generated: { by: "jarvis", at: "2026-10-27" }
---

# Galaxy BLE bridge protocol

Source: the iOS companion source (natehuby76/ios-clone), cross-validated against the
official Android companion APK (identical UUID prefix and AAD strings in its DEX).
ParamAuto's Kotlin port (`GalaxyBle.kt`, `BleBridgeClient.kt`) implements this.

## GATT layout
- Service: `bd490001-6dc1-4de7-a7d0-6cdb441f7650`
- RX `bd490002` (requests in), TX `bd490003` (responses out), INFO `bd490004` (session), NOTIFY `bd490005` (notification stream)

## Pairing
- The comma shows a QR: `{"type":"galaxy-bluetooth","version":1,"key":"<64 hex>"}` (Galaxy: Settings → Pair phone).
- The hex decodes to a 32-byte AES key. It never changes per session; the session id does.

## Session handshake
- After connecting, read INFO, drop its first byte; the remaining 16 bytes hex-encode to the session id used in every request.

## Request path
1. Build JSON: `{id, session, counter, path, method, headers, body (base64), responseCodec: 2}` — counter increments per request.
2. Envelope: `[flag][uint32be length][payload]` — payload is zlib-deflated when smaller, else plain.
3. Seal with AES-GCM, AAD `galaxy-ble-v1/request`; frame as `[uint32be length][nonce | ciphertext | tag]`.
4. Fragment for GATT: `[1][uint32be seq][chunk]` — chunk size `min(512, mtu) − 5`, minimum 15.

## Response path
- Responses arrive on TX; each fragment is acked with `[2][uint32be seq]`.
- Open with AES-GCM (AAD `galaxy-ble-v1/response`); codec-2 plaintext is `[uint32be metaLen][meta JSON][raw body]`, meta carrying status + headers.

## Health check
- `GET /_bridge/health` over the bridge returns `{protocol, transport, readStream, notificationStream, catalogSHA256}`.

## Availability and limits
- The comma-side bridge (service `bd490001`) ships in Nate's Galaxy Bluetooth build; it is **not in SecretGoodStarPilot mainline**, so on a stock SG comma there is nothing to connect to yet.
- ParamAuto v1 uses the TX request/response stream only, not the notify-window stream.
- Over BLE, Galaxy blocks media/upload/auth paths with 501 (recordings, replay, cameras, AA/Spotify upload, tiles, uplink, app.apk, login/logout) — see the endpoint reference.

## Related
- [android-companion-apk.md](android-companion-apk.md) · [../galaxy/endpoints.md](../galaxy/endpoints.md)
