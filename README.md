# ParamAuto

**Android + Android Auto companion app for comma devices running [StarPilot](https://github.com/StarPilotHQ/StarPilot).**

ParamAuto puts your comma's navigation on your phone and your car's screen:
search for a place, send it to the comma, and follow the drive with a live map,
lane guidance, speed limits and turn cues — all driven by the comma itself.

```
comma (StarPilot, Galaxy API) ──► phone (ParamAuto) ──► Android Auto head unit
```

The phone hosts Android Auto; the comma is the navigation brain. Nothing is
installed on the comma — ParamAuto talks to the Galaxy API the comma already
serves.

## Features

- **Live navigation on phone and Android Auto** — route, alternatives,
  maneuver card, distance/ETA, lane guidance, traffic signals and stop signs
  along your route
- **Search & go** — search on the phone (comma, Google, Mapbox or key-free
  sources) and send the destination straight to the comma; long-press the map
  to drop a pin and save a favorite; accept destinations shared from Google
  Maps, Apple Maps and Waze
- **Speed limits your way** — comma limits plus optional phone sources, with
  per-band cruise offsets you can adjust from the phone
- **Your comma, from your phone** — driving models (with Active Small/Big
  picks), driving personalities, the full comma settings pages, software
  updates, and a Wi-Fi manager for the comma's networks
- **Recordings** — browse your drives on the comma and play or download route
  videos over a local connection
- **Three ways to connect** — local network, Bluetooth bridge, or Galaxy
  Cloud, with automatic failover and a built-in connection log for
  troubleshooting
- **Offline & key-free friendly** — offline map packs, and a key-free
  OpenStreetMap mode for Android Auto; bring your own Mapbox or Google key
  only if you want those providers

## Requirements

- A comma 3 / 3X / 4 running **StarPilot**
- An Android phone (Android 10 or newer)
- Android Auto, if you want the car display

## Get the app

ParamAuto is a hobby project — free forever, no ads, no in-app purchases.

It's currently in **closed testing** on Google Play while it works toward a
public release:

1. Join the test: https://play.google.com/apps/testing/com.bogala.paramauto
2. Install ParamAuto from the Play Store
3. Open the app next to your comma and follow the two-step setup

## Feedback

Feature requests and bug reports are very welcome:
**param@muse.ai** — every message is read and tracked.

## Building

- JDK 17 and the Android SDK (API 36)
- `./gradlew assembleDebug`

Release signing uses a local `keystore.properties` (see
`keystore.properties.example`) — never commit real keys.

## Privacy

ParamAuto's privacy policy: https://bogala.in/privacy/paramauto.html

Your map keys stay on your phone and your comma. The app has no analytics,
no ads and no accounts.

## Check out Muse

Check out Muse, your personal AI agent. Redeem my code in Settings within 48 hours of joining and we'll both get 1 billion Muse tokens.

Code: NI9ADY
https://muse.ai/join

## License

MIT — see [LICENSE](LICENSE).

*Developed with the aid of Muse, Meta's personal AI assistant.*
