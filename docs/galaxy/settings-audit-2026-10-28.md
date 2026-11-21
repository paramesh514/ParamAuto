---
okf_version: "0.2"
type: State Report
title: "SG Galaxy settings audit — 2026-10-28"
description: "First full read-only settings inventory on SecretGoodStarPilot (portal, ParamCar), analyzed against Param's Dom-era setup. Nothing was changed."
tags: [galaxy, settings, audit, starpilot, paramcar]
status: stable
trust_tier: authoritative
generated: { by: "jarvis", at: "2026-10-28" }
---

# SG Galaxy settings audit — 2026-10-28

Read-only sweep of every Galaxy settings page via the remote portal, 2026-10-28
03:47–04:03 MST, device **ParamCar** only (no device switching, per Param's rule).
No control was toggled, slid, typed into, or confirmed. Dropdown lists were opened
and closed with Escape only. Baseline for comparison: the Dom-era audits
(2026-10-05, 2026-10-16) and Param's standing decisions through 2026-10-27.

Device state at audit: Parked · Remote connection. Branch **Dom**, version **7.0**,
commit `efe875ffd6fb`. Vehicle TOYOTA_RAV4_TSS2_2022, Imperial units. LAN
192.168.68.13 (wlan0). Last drive Oct 27 22:10–22:26, 1.1 mi, 3% engaged, model
Regret Driven Framework V4 (Small).

Only **14 settings differ from SG defaults** (Troubleshoot "Changed settings" view):
Curve Speed Controller On, Show Curve status On, Use VOACC On, One change per
signal Off, Automatic Lane Changes On, Min adjacent lane width 7.0 ft, Lane-change
follow gap 1.0 s, Approaching lead buffer On, Faster Takeoffs On, Speed Limit
Controller On, SLC secondary source Maps, Sound Pack tesla, Alpha Longitudinal On,
LKAS press = Cycle personality.

## Analysis — what matches Param's setup

- **Saved driving mode: Conditional Experimental** — his daily solo mode. Chill
  fallback triggers: ≥45 mph, ≥35 mph with lead, +3 mph set-speed margin, lead
  vehicle On — the familiar CE trigger defaults.
- **SLC fallback behavior preserved in spirit:** "Previous accepted limit" is On
  (the SG analogue of his 2026-10-19 "Previous Limit" fix), primary source Vision,
  secondary Maps, automatic adoption On.
- **On-comma Android Auto: Not set up / Off** — consistent with his standing
  architecture rule (comma → phone → Android Auto; the phone hosts AA).
- Personalities: Aggressive follow 1.25 s low / 1.0 s high, jerks 50%; Standard
  1.45/1.2 s, jerks 100%; Relaxed 1.6/1.4 s, jerks 100%; Traffic low-speed follow
  0.75 s. LKAS button cycles personality. These replace the old single
  "Safety Gap Bias" setting; no SG equivalent of the Dom-era "Lead Detection
  Sensitivity 50" exists in the toggles.
- Long Planner On; acceleration profile StarPilot Default, deceleration Comfort.
- Navigation On; a destination is set (Office — work); GPS source
  "Phone GPS as backup"; Mapbox usage this month is tiny (2/495 searches,
  4/99,000 routes) — the SG nav is not burning his quota.
- Hotspot disabled (TheGalaxy-XXXX), auto-broadcast Off. Data uploads over metered
  networks Off. Cloud provider comma connect. Sound pack tesla, all volumes Auto.
- Toyota auto lock leaving Park / auto unlock entering Park both On.

## Analysis — anomalies and decisions for Param

1. **SLC offsets are all zero.** Every band (0–24 … 74–99, 99+) reads 0 under a
   "Saved legacy schedule" policy, with an unpressed "Adopt fixed offsets" button.
   His last confirmed Dom-era table (params dump, 2026-10-20) was
   **0, 3, 4, 6, 7, 9, 9**. The offsets did not carry into SG's displayed schedule.
   Restoring them means adopting fixed offsets with that table — his call.
2. **"Slower lead" CE trigger is On again.** On 2026-10-20 Param had Slower Lead
   and Stopped Lead turned OFF (they flipped longitudinal into Experimental and
   caused hard braking at stopped/slow vehicles; they are meant for radar-less
   cars). SG now shows Slower lead **On**, Stopped lead Off. Note the interaction:
   **Use VOACC is also On** (vision-only ACC instead of radar-assisted lead
   detection), so the car is currently running in the radar-less configuration
   those triggers were designed for — but against his explicit Oct 20 decision.
3. **Force Stop is Off; Stop Distance Adjustment 0.0 ft (slider disabled).** The
   2026-10-16 approved "+15 ft force-stop distance" tweak has no live equivalent
   in the current SG state. (Distinct from the declined "Fix 5" tunable
   stopped-follow gap, which stays declined.)
4. **eGPU not detected.** Model Manager: "An external GPU is not detected. Active
   Small is used; your Big selection is saved for later." Active Big selection is
   Flip Flop, but the running setup is the on-device Small model (RDF V4).
   System Monitor also reported all eGPU temperatures as "—". Whether the
   Chestnut dock was unplugged/powered down while parked is unknown.
5. **An update is staged.** Software & Updates: "Update Available", downloaded
   2026-10-28 03:51, auto-download On. Installing is Param's call; the Dashboard
   updater line disagreed between views ("None reported" vs earlier
   "Finalized update ready").
6. **Lane Centering master toggle reads Off while Always On Lateral is On.**
   Lane offset 0.0 m, model path preference 100%. Lane Centering Strength reads
   **5** (1–10 scale) on the Toggles page but **100** in the Troubleshoot
   snapshot — an unresolved display discrepancy; the authoritative scale was not
   determined.
7. **Curve Speed Controller learning is empty** (progress 0.0%, "No learned
   samples", saved learning "Valid"). The Dom-era calibration (73% progress)
   did not carry over; it relearns with driving.
8. **Lane-change combination to be aware of:** Automatic Lane Changes On +
   "One change per signal" Off (default On) + min lane-change speed at the 5 mph
   floor. Guards present: min adjacent lane width 7.0 ft, lane-change follow gap
   1.0 s, auto delay 1.0 s.
9. **Alpha Longitudinal is On** (Vehicle Controls; default Off; page warns it
   "May disable Automatic Emergency Braking", applies after next startup). It was
   already set before this audit — flagged because of the AEB note, not changed.

## Full inventory (condensed, verbatim values)

### Lateral
- Lane Centering: Enable **Off**; Pause on signal On (disabled, unavailable for
  this vehicle); Lane offset 0.0 m (−0.3…0.3); Model path preference 100%;
  Strength 5 (1–10) [snapshot says 100 — see anomaly 6].
- Lane Changes: Lane Change Speed 5.0 (1–10); Allow lane changes On; Min speed
  5.0 mph; One change per signal Off; Automatic Lane Changes On; Auto delay 1.0 s;
  Min adjacent lane width 7.0 ft; Close lane-change gap Off; Lane-change follow
  gap 1.0 s; Signal lane detection On; Signal lane width 0 ft.
- Steering and Torque: Turn Signal Guidance On; Turn Assist On; Auto Learn On;
  Lat Accel 1.90 (learned); Friction 0.11 (learned).
- Always On Lateral: Enable On; Pause steering below 0 mph; Pause only while
  signaling Off; Steering resume delay 0 s; Brake pause below 0.0 mph.

### Longitudinal
- Long Planner: StarPilot Longitudinal Planner On; Swap Toyota Cruise Steps Off;
  Force Stop Off; Stop Distance Adjustment 0.0 ft; Approaching lead buffer On;
  Faster Takeoffs On; Accel profile StarPilot Default; Decel profile Comfort;
  Custom Following and Jerk Off.
- Conditional Driving Modes: Saved mode **Conditional Experimental**. Experimental
  triggers: speed threshold 0, speed with lead 0, signal speed 0, Open road Off,
  Curves Off, Curves with lead Off, Lead vehicle On, **Slower lead On**,
  Stopped lead Off, Stop lights On, Model stop time 7.7 s, Remember manual choice
  Off. Chill triggers: threshold 45 mph, with lead 35 mph, set-speed margin 3 mph,
  Lead vehicle On, Launch assist Off, Remember manual choice Off.
- Curve Speed Controller: On; Pause while following a lead Off; Show Curve status
  On; learning Valid, progress 0.0%, no learned samples.
- Speed Limit Controller: On; Show signs On; Automatic adoption On; Primary
  Vision; Secondary Maps; Previous accepted limit On; Offset policy "Saved legacy
  schedule"; all displayed offsets **0 mph**; 99+ "Zero offset".
- Personalities: see Analysis (Aggressive 50% jerks; Standard/Relaxed 100%;
  Traffic low follow 0.75 s, inherits Relaxed at speed; all Following Presets
  "StarPilot Default").

### Wheel Controls
- LKAS press: **Cycle personality** (choices include Force coast, Experimental
  override, Traffic mode, Switchback, Bookmark, Adopt speed limit, Quick Select
  1–3, Pulse and glide).

### Visual
- Layout (Comma, Small): 9 active widgets — Speed limit, Set speed, Steering
  wheel, Torque bar, Speed limit actions, Driver monitoring, Model confidence,
  CEM and driving mode, Following distance. Inactive: PiP left/right cameras,
  Driving Mode Descriptions, Clock, Stopped timer. Path style Acceleration colors;
  Path #30FF9C; closest lane #00FF40; outer lines #FFFFFF.
- Screen options: Developer Sidebar Off; Lead Indicator Off; Lead Info Off
  (disabled); Camera View Standard; Driver Camera on Reverse Off; Hide
  Personality Alerts Off; C4 amber border Off; C4 red blind-spot border On.
- Quick Select 1–3: all unassigned and disabled.
- Blind Spot Camera: Use Off; on signal Off; on blind-spot warning Off; mirror
  crop Off.

### Sounds & Alerts
- All seven volumes Auto. Sound Pack **tesla** (Duck also installed; Old Engage
  Sounds and Weeb not installed).

### Device & Data
- Custom Display Settings Off (brightness Auto, parked timeout 30 s, driving
  interaction timeout 5 s — all disabled with it); Turn Off Comma Screen Off;
  Keep Screen On While eGPU Loads Off; Always Allow Uploads Off.

### Developer
- Cloud provider comma connect; Use VOACC **On**; Reset Driver Monitoring Ready.

### Tools (settings-bearing)
- Android Auto: Not set up, switch Off, automatic connection Off.
- Bluetooth: power On; saved device Pixel 11 Pro XL (Disconnected); controller
  buttons feature Off, nothing assigned.
- Sentry: Off; motion sensitivity 0.040; warning persistence 1.0 s; all four
  notification channels unconfigured; 0 events.
- V-ASM spot monitoring: Off; confidence 0.94; smoothing 0.20 s.
- Navigation Setup: Navigation On; GPS source Phone GPS as backup; avoid
  tolls/highways/ferries all Off; Mapbox key saved; offline overlay 0 KB (no
  areas); Save roads as you drive On; offline speed-limit DB 156.8 MiB, coverage
  lat 30–38, lon −116…−108.
- Model Manager: Selected RDF V4 (Small, built-in); installed 2 of 110 (RDF V4 +
  Flip Flop as Active Big); Jetlink Off (Cinque Terre V3 prepared, not active);
  Model Randomizer Off; eGPU not detected.
- Model Laboratory: pair composition unavailable in this build.
- Vehicle Controls: Auto Detection On (Toyota Rav4 Tss2 2022); Toyota auto
  lock/unlock On; **Alpha Longitudinal On**; Continental Radar Off.
- System: Download updates automatically On; Force Drive State Auto (device
  Offroad, parked); target branch Development — Dom.
- Galaxy Access: hotspot disabled; app key never displayed; companion connections
  disabled, 0.0 MB today.
- Force Drive State page: Auto mode current. Crash reports: none. Plots: no live
  data while parked; Auto Learn training report "unsupported", all 30 steering
  parameters show no learned proposal.

## Gaps and unverified items
- Routes/recordings are local-connection only — not viewable through the tunnel.
- SLC secondary-source and personality Accel/Braking preset choice lists were not
  opened (values recorded; options unverified). Nav GPS-source list not opened.
- Per-widget edit panels enumerated only for Set speed. Model catalogs (110)
  summarized, not enumerated. Offline-map region list (229) not enumerated.
- Lane Centering Strength scale discrepancy (5 vs 100) unresolved.
- Secrets (app key, Mapbox token, hotspot password) were never displayed.

## Related
- [endpoints.md](endpoints.md) · [typed-api-map.md](typed-api-map.md) ·
  [access-and-tunnel.md](access-and-tunnel.md) · [branch-state.md](branch-state.md)
