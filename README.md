# ParamAuto

Companion Android app for ParamPilot (comma 3/3X/4, Toyota RAV4 2022).
Renders the comma's navigation state on the car's head unit via **Android Auto**
as a legitimate **navigation** app: maneuver card, distance/ETA, and lane
guidance ("which lane am I in / which do I need").

## How it works

```
comma (built-in Galaxy HTTP API :8082) --Wi-Fi--> phone --Android Auto--> head unit
```

- **No comma-side service needed.** The comma already serves a Galaxy HTTP
  API on port 8082. The app polls it; nothing is installed on the comma.
- **Auto-discovery:** the app discovers the comma across local Wi-Fi, the comma
  hotspot (`172.31.254.1:8082`), and Bluetooth LE. It probes open 8082 ports
  against multi-version comma endpoints (`/api/device/status`, `/api/navigation/status`,
  `/api/device/state`, `/api/companion/runtime`) to seamlessly support both
  legacy openpilot and SecretGoodStarPilot typed Galaxy APIs. The found IP is
  cached and prioritized for subsequent sessions.
- **Transports:**
  - **Wi-Fi / LAN / Hotspot:** Primary high-speed transport for telemetry, live
    plots, navigation state, and map tiles.
  - **Bluetooth LE Bridge:** Secondary transport for API status/settings that
    continues functioning even when phone Wi-Fi is occupied by wireless Android Auto.
    Tunnels requests using the official StarPilot companion BLE bridge protocol.
- **Data:** once per second the app fetches `GET /api/params/all` and reads
  `NavInstructionState` (maneuver, street, distance, lane count + your lane),
  `PreviousSpeedLimit` (last accepted limit), and `Offset1..7` (the band
  offset for that limit). Rendered via the Car App Library
  `NavigationTemplate` (maneuver + `Step` with lane cue).
- **Current speed** is not published by the API, so it comes from the
  phone's GPS (grant the location permission on the phone once).
- Turn-by-turn guidance with dynamic lane arrows and active lane guidance cue.
- Display-only: nothing flows from phone to comma.

## Power / battery status (Local Galaxy Telemetry)

Car battery voltage is read directly from the comma device via StarPilot Galaxy's local runtime endpoint:
```http
GET /api/system/monitor
```
Voltage updates automatically every 3 seconds and is displayed on the cockpit HUD's dedicated voltage badge (e.g. `12.2 V`). No Comma Connect account, dongle discovery, Athena cloud proxy, or JWT token is required.

## Building the APK

The project can be built using either the Gradle command line (PowerShell/Bash) or Android Studio.

### Prerequisites

1. **Java Development Kit (JDK 17)**:
   - Required for Gradle 8.x and Android Gradle Plugin 8.2+.
   - Set `$env:JAVA_HOME = "C:\Users\<user>\android-dev\jdk-17"` (or your installed JDK 17 path).
2. **Android SDK** (API 34):
   - Android SDK Platform 34 (`platforms;android-34`)
   - Build-tools 34.0.0 (`build-tools;34.0.0`)
   - Platform Tools (`platform-tools` for `adb`)
   - Licenses accepted (`sdkmanager --licenses`)

### Option 1: Command-Line Build (PowerShell)

```powershell
# 1. Set environment variables
$env:JAVA_HOME = "C:\Users\bogal\android-dev\jdk-17"
$env:ANDROID_HOME = "C:\Users\bogal\AppData\Local\Android\Sdk"
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"

# 2. Compile debug APK
.\gradlew.bat assembleDebug

# 3. Copy output to root APK file
Copy-Item "app\build\outputs\apk\debug\app-debug.apk" -Destination "ParamAuto.apk" -Force
```

*(On macOS/Linux, run `export JAVA_HOME=...; export ANDROID_HOME=...; ./gradlew assembleDebug`)*

The compiled APK will be output at `app/build/outputs/apk/debug/app-debug.apk` and copied to `ParamAuto.apk`.

### Option 2: Android Studio Build

1. Open this directory in **Android Studio** (Hedgehog or newer).
2. Let Gradle sync (`androidx.car.app:app:1.7.0`).
3. Select **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

### Building for Release & Google Play

To prepare for production release ([developer.android.com/studio/publish](https://developer.android.com/studio/publish)):

1. **Generate signing keystore** (first time only):
   ```bat
   tools\generate_keystore.bat
   ```
2. **Build Google Play App Bundle (.aab)**:
   ```powershell
   .\gradlew.bat bundleRelease
   ```
   *Output*: `app/build/outputs/bundle/release/app-release.aab`
3. **Build Release APK**:
   ```powershell
   .\gradlew.bat assembleRelease
   ```
   *Output*: `app/build/outputs/apk/release/app-release.apk`

See [docs/paramauto/release-and-publishing.md](docs/paramauto/release-and-publishing.md) for full Google Play Console submission steps, policy forms, and Android Auto quality checks.

---

## Installing & Sideloading to Phone

### 1. Push via ADB

With USB debugging enabled on your phone and connected via USB:

```powershell
# Verify device connection
adb devices

# Install or update the APK
adb install -r ParamAuto.apk

# Grant required permissions (GPS speed, Wi-Fi discovery, Bluetooth LE bridge)
adb shell pm grant com.parampilot.auto android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.parampilot.auto android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant com.parampilot.auto android.permission.BLUETOOTH_SCAN
adb shell pm grant com.parampilot.auto android.permission.BLUETOOTH_CONNECT
adb shell pm grant com.parampilot.auto android.permission.NEARBY_WIFI_DEVICES

# Launch Phone HUD Dashboard
adb shell am start -n com.parampilot.auto/.MainActivity
```

### 2. Enable Android Auto Developer Mode

Android Auto requires **Unknown sources** to be enabled for sideloaded navigation apps:

1. On your phone, open **Settings > Connected devices > Connection preferences > Android Auto** (or search for *Android Auto* in Settings).
2. Scroll to the bottom and tap **Version** 10 times consecutively until prompted to allow developer settings.
3. Tap the three dots (**⋮**) in the top-right corner > **Developer settings**.
4. Check **Unknown sources**.
5. Connect your phone to your vehicle (USB or wireless Android Auto) — **ParamPilot Auto** will now appear in your vehicle's head unit app launcher.

---

## Testing with Desktop Head Unit (DHU)

To test the Android Auto interface on your PC without a vehicle:
1. Start the Android Auto Head Unit Server on your phone: Android Auto Developer Settings > **Start head unit server**.
2. Forward the port: `adb forward tcp:5277 tcp:5277`.
3. Launch the Android Auto Desktop Head Unit (`desktop-head-unit.exe`).

---

## Scanning & Discovery Architecture & Troubleshooting

This section details the root causes identified and resolved for both Wi-Fi/LAN auto-discovery and Bluetooth LE bridge scanning.

### 1. Permission Issues & Resolutions

* **Bluetooth LE Scanning & Location Dependency (Android 12+ / API 31+)**:
  * **Issue**: In `AndroidManifest.xml`, `BLUETOOTH_SCAN` was declared without `android:usesPermissionFlags="neverForLocation"`. On Android 12+, scanning without this assertion mandates that `ACCESS_FINE_LOCATION` is granted **and** that the phone's physical Location (GPS) toggle is turned ON. When Location was off or restricted, Android silently dropped scan results without emitting errors.
  * **Fix**: Added `android:usesPermissionFlags="neverForLocation"` (with `tools:targetApi="s"`) to `android.permission.BLUETOOTH_SCAN`. Bluetooth LE discovery no longer requires location services.
* **Missing Runtime Permissions at App Startup**:
  * **Issue**: On Android 12+, `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` are runtime-dangerous permissions. `MainActivity` previously only requested Location permissions, leaving Bluetooth permissions in a `granted=false` state until manually toggled.
  * **Fix**: Updated `MainActivity.checkAndRequestPermissions()` to check and prompt for Location (`FINE`/`COARSE`), Bluetooth (`SCAN`/`CONNECT` on API 31+), and Nearby Wi-Fi Devices (`NEARBY_WIFI_DEVICES` on API 33+) simultaneously during startup.
* **Additional Network & Wi-Fi Permissions**:
  * **Issue**: Wi-Fi multicast and network routing changes during subnet discovery were missing explicit declarations.
  * **Fix**: Declared `CHANGE_WIFI_MULTICAST_STATE`, `CHANGE_NETWORK_STATE`, and `NEARBY_WIFI_DEVICES` in `AndroidManifest.xml`.

### 2. Wi-Fi / Local Subnet Scanning Fixes (`NetworkScanner.kt`)

* **Endpoint 404 in Comma Verification**:
  * **Issue**: `verifyComma(ip)` solely queried `GET http://$ip:8082/api/device/status`. SecretGoodStarPilot replaced `/api/device/status` with `/api/device/state` and added `/api/navigation/status`. When probing a modern SecretGood comma, `/api/device/status` returned HTTP 404 (`{"error":"Capability unavailable"}`), causing the scanner to reject open 8082 ports as "not a comma".
  * **Fix**: Multi-endpoint fallback verification probing `/api/device/status`, `/api/navigation/status`, `/api/device/state`, and `/api/companion/runtime`. Matching on JSON markers (`status`, `online`, `lanIp`, `state`, `revision`, `schemaVersion`, `instance`).
* **Missing Comma Hotspot Gateway (`172.31.254.1`)**:
  * **Issue**: When connecting to the comma's Wi-Fi hotspot (`TheGalaxy-xxxx`), the comma gateway IP is always `172.31.254.1`. This IP was omitted from `fastCandidates`, `quickCheckLan()`, and subnet bases.
  * **Fix**: Added `172.31.254.1` to priority candidates and `172.31.254` to scanned subnet bases.
* **Galaxy Session Authentication for Typed APIs (`SgGalaxyClient.kt`)**:
  * **Issue**: On SecretGoodStarPilot, Galaxy endpoints (`/api/navigation/status`, `/api/plots/live`, `/api/device/state`) require a valid session cookie. Unauthenticated requests receive `HTTP 401 Unauthorized` (`{"error": "Refresh the local Galaxy session"}`). Because `probe()` previously attempted to call `/api/navigation/status` without a cookie, the probe returned 401, causing `isSgEndpoint()` to falsely conclude the comma was not running SecretGoodStarPilot and fall back to the legacy `/api/params/all` endpoint (which does not exist, triggering a repeated failure/rediscovery loop).
  * **Fix**: Implemented automatic session negotiation in `SgGalaxyClient.refreshSession()`: it queries `/api/auth/session` (which automatically authenticates local LAN/hotspot peers), captures the `galaxy_session` cookie from `Set-Cookie`, and attaches it to all subsequent API requests with automatic 401 retry handling.
* **Modern Gateway Resolution**:
  * **Issue**: `WifiManager.dhcpInfo` is deprecated and returns `0.0.0.0` on Android 12+ without special location access.
  * **Fix**: Implemented `ConnectivityManager.getLinkProperties()` to inspect active network routing tables for the true default IPv4 gateway.

### 3. Bluetooth LE Scanning Fixes (`BleBridgeClient.kt` & `SettingsDialogs.kt`)

* **Scan Failure Logging & Throttling Detection**:
  * **Issue**: `scanForBridge` caught `onScanFailed(errorCode)` without logging, hiding Android scan throttling (`SCAN_FAILED_SCANNING_TOO_FREQUENTLY`) or registration failures from the user.
  * **Fix**: Captured error codes in `scanErrorRef` and propagated human-readable status messages ("scanned too frequently", "internal Bluetooth error", etc.).
* **Flexible Device Matching**:
  * **Issue**: Hardware filtering on 128-bit Service UUIDs can fail on certain Bluetooth LE chipsets if the UUID is placed in scan response data.
  * **Fix**: Matches on `GalaxyBle.SERVICE_UUID` in scan records and device name heuristics (`Galaxy`, `StarPilot`, `comma`).
* **Settings Dialog Validation**:
  * **Issue**: Clicking "Test Connection" with an empty pairing key silently failed with no feedback.
  * **Fix**: Added input validation alerting the user to enter their pairing key from comma settings (`Settings -> Pair phone`).

### 4. Single-Source-of-Truth Navigation & Cross-Device Synchronization

* **Comma `/api/navigation/status` as Single Source of Truth**:
  * Eliminated divergent routes where Phone, Android Auto, and Comma previously competed with independent Mapbox Directions calculations.
  * When connected to a Comma (`client.commaFound == true`), local Mapbox Directions calls on Phone and Android Auto are disabled. Comma's `/api/navigation/status` provides all route waypoints, alternative paths, turn cues, and ETAs.
* **Alternative Routes Parsing & Geometry Extraction**:
  * On SecretGoodStarPilot, `/api/navigation/status` delivers top-level `route: []` when multiple routes are present, embedding waypoints in `alternatives[i].geometry`.
  * `publishSgRoute()` and `applySgSnapshot()` extract `destination`, `selectedRoute`, and all alternative routes into `NavStateManager.activeRoutes`, establishing instant multi-route display.
* **Interactive Cross-Device Route Switching**:
  * Tapping an alternative route on the Phone map or tapping the `Alt (±X m)` button in the Android Auto `ActionStrip` invokes `NavigationManager.selectRouteSg()`.
  * Dispatches `POST /api/navigation/action` with `{"action":"selectRoute", "index":...}`, updating the Comma's internal active route. The Comma broadcasts the updated state back to Phone and Android Auto, ensuring 100% lockstep synchronization.
* **Android Auto Multi-Route Display Parity (`CarMapRenderer.kt` & `NavScreen.kt`)**:
  * Updated `CarMapRenderer` to render all routes: alternative routes are drawn underneath in muted slate-grey (`#64748B`) with dark casing (`#1E293B`), while the primary route is rendered on top in glowing cyan (`#00E5FF`).
  * Divergence ETA bubbles (`+X min` / `-X min`) and a classic Google Maps red teardrop destination pin marker are projected onto the car head unit display.
* **Lock-Screen & Always-On Automotive HUD**:
  * Added `android:showWhenLocked="true"` and `android:turnScreenOn="true"` to `AndroidManifest.xml`.
  * In `MainActivity.onCreate()`, enabled `setShowWhenLocked(true)`, `setTurnScreenOn(true)`, and `FLAG_KEEP_SCREEN_ON` so the dashboard stays active in car mounts without locking or dimming while driving.
* **Device State Badge (`/api/device/state`)**:
  * Added live device state query (`http://<comma>:8082/api/device/state`) in `CommaClient` and `NavStateManager`.
  * Renders a dedicated vehicle state badge directly above the IP address badge in `HudOverlay` and in the Android Auto cockpit status strip (`CarMapRenderer`): `🅿️ Parked` (amber), `🚗 Driving` (green), and `⏸️ Standby` (cyan).
* **POST `/api/navigation/action` CSRF Header Requirements**:
  * SecretGood Galaxy endpoints reject action POST requests with HTTP 403 `{"error": "Local access only"}` unless explicit `Origin: http://<comma>:8082` and `Referer: http://<comma>:8082/` headers are attached. Added these headers to `SgGalaxyClient.postNavAction`.
* **Vehicle Location & Route Polyline Continuity**:
  * Prioritized live comma GPS coordinates over phone GPS when available from `/api/navigation/status` (`location.latitude`, `location.longitude`, `location.bearing`).
  * `MainActivity.drawRoutesOnMap` dynamically prepends the vehicle's live coordinates (`currentGps`) to index 0 of the route polyline if within 1.5 km, completely eliminating visual gaps between the vehicle chevron and the route start.
  * Dynamically appends destination coordinates to the route end and anchors a custom Google Maps style red teardrop pin marker (`DestinationPin`) at the destination.
* **UI Overlap & Transparency Bleed Fixes**:
  * Decoupled the `⌖ Re-center` button's bottom margin (`270dp` when navigation is active, `175dp` when inactive) so it floats cleanly above the left HUD stack (`Voltage -> State -> IP -> Settings`).
  * Set `TurnBannerView` card and lane row backgrounds to 100% solid opaque (`0xFF0E382B` night, `0xFF1B5E4B` day), completely preventing map street names and vector labels from bleeding through.


