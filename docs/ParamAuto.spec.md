# ParamAuto Specification & Implementation Design

**Document:** `ParamAuto.spec.md`  
**Status:** Approved Specification  
**Design Language:** Google Material 3 (Material You) + Google Maps / Gemini / Drive Automotive Styling  
**Target Platform:** Android Auto (`androidx.car.app`) + Android Companion (`com.parampilot.auto`)  
**Backend:** StarPilot Galaxy HTTP REST API (`http://192.168.68.14:8082` / `http://172.31.254.1:8082` / `https://galaxy.firestar.link/<slug>`)  
**Data Flow Rule:** **Comma -> Phone -> Android Auto Head Unit** (strictly unidirectional display to head unit).

---

## 1. Executive Summary & Architecture

ParamAuto bridges a comma 3/3X device running **StarPilot Galaxy** with any vehicle head unit via native **Android Auto**. The phone acts as the intelligent gateway and render engine: it polls the comma's Galaxy server over LAN, Wi-Fi hotspot, BLE bridge, or Galaxy Cloud, parses real-time navigation, telemetry, and settings states, and renders custom maps and vehicle HUD overlays directly onto the vehicle dashboard screen.

```
+-------------------------------------------------------------------------------+
|                             COMMA 3/3X (CAR)                                  |
|   openpilot / StarPilot Core  <---->  Galaxy REST Server (Port 8082)         |
|   - Navigation daemon (navd)          - /api/navigation/status, /action       |
|   - Controls & Telemetry (controlsd)  - /api/plots/live, /api/device/state    |
|   - Model Manager & Settings          - /api/settings/*, /api/models/*        |
+-------------------------------------------------------------------------------+
                                     |
        Wi-Fi LAN / Hotspot (172.31.254.1) / BLE Bridge / Galaxy Cloud
                                     v
+-------------------------------------------------------------------------------+
|                         PHONE (ParamAuto App)                            |
|                                                                               |
|   [ SgGalaxyClient / CommaClient ]                                            |
|   - Session Manager (Cookie: galaxy_session via /api/auth/session or /login)  |
|   - 1-2 Hz Telemetry & Nav State Poller                                       |
|   - Two-Phase Settings Commit Engine (/api/settings/preview -> /confirm)      |
|                                                                               |
|   [ NavStateManager & NavigationManager ]                                     |
|   - Global synchronized navigation & telemetry state singleton                |
|   - Comma-Primary / Cellular-Fallback Tile Engine                             |
|   - SLC (Speed Limit Controller) band offset math                             |
|   - Turn instruction parser & route geometry precomputer                      |
+-------------------------------------------------------------------------------+
                                     |
                         Android Auto Projection
                                     v
+-------------------------------------------------------------------------------+
|                      VEHICLE HEAD UNIT (Android Auto)                         |
|                                                                               |
|   [ CarMapRenderer & NavScreen (SurfaceCallback) ]                            |
|   - Primary Map Source: Comma-served local raster tiles (/api/navigation/...) |
|   - Fallback Map Source: Cellular / online tiles (Mapbox Static / CARTO)      |
|   - Route polyline with Gemini subtle glow + alternative route lines          |
|   - Real-time car marker & heading needle                                     |
|                                                                               |
|   [ Surface HUD & M3 Google Components ]                                      |
|   - Floating Google Maps style turn banner (capsule radii, green guidance)    |
|   - Digital Speedometer (v_Ego) with Gemini Aurora engagement pill            |
|   - Google Drive style filter/shortcut chips (Home, Work, Quick Favorites)    |
|   - Speed limit sign + delta offset badge                                     |
+-------------------------------------------------------------------------------+
```

---

## 2. Modern Google Design System (Material 3, Maps, Gemini, Drive)

To ensure the in-car interface looks like an official first-party Google automotive product, ParamAuto incorporates visual paradigms from Google's flagship applications:

### 2.1 Aesthetic Influences & Visual Identity

| Google App / Paradigm | Visual Element Applied in ParamAuto | Implementation Details |
|---|---|---|
| **Google Maps** | **Floating Guidance Banner & Capsule Controls** | High-contrast deep green guidance headers (`#0F9D58` primary / `#0B8043` dark / `#137333` light), floating pill action buttons (`28dp` full capsule radius), clean turn maneuvers, and floating bottom ETA card with cancel cross. |
| **Google Gemini** | **Aurora Glows & AI Model Pills** | Luminous multi-tone gradient pills (`#4285F4` Blue → `#7C4DFF` Violet → `#00E5FF` Cyan) for active driving model badges, openpilot active states, and neural network calibration metrics. |
| **Google Drive** | **Clean Assist Chips & Surface Cards** | M3 assist and filter chips (`32dp` height, `8dp` radius, subtle `1dp` border) for destination shortcuts (Home, Office, Favorites), settings categories, and profile pickers. Card surfaces use tonal elevation without harsh borders. |
| **Google (Material 3)** | **Tonal Elevation & Dynamic Night Theme** | Automotive-optimized dark theme using deep slate/charcoal tones (`#131314` base, `#1E1F20` surface container, `#2B2B2C` high container) preventing night glare while maximizing OLED contrast. High-contrast typography in Google Sans / Roboto Flex. |

### 2.2 Color Palette & Visual Tokens

```
================================================================================
TOKEN NAME                 HEX CODE    USAGE
================================================================================
--md-sys-color-bg          #131314     Night Canvas Background (OLED dark slate)
--md-sys-color-surface     #1E1F20     Floating Cards / Containers
--md-sys-color-surface-hi  #2B2B2C     High-priority Containers (Turn Banner / Dialogs)
--md-sys-color-primary     #A8C7FA     Google M3 Accent Blue (Buttons, Active Tabs)
--md-sys-color-outline     #444746     Subtle 1dp Borders / Card Dividers
--md-sys-color-on-surf     #E3E3E3     Primary High-Contrast Typography
--md-sys-color-on-sub      #C4C7C5     Secondary Subtitle Typography (60% opacity)

--gmaps-guidance-green     #0F9D58     Google Maps Primary Turn Direction Header
--gmaps-route-blue         #4285F4     Active Route Polyline Primary
--gmaps-route-alt          #5F6368     Alternative Route Polyline (De-emphasized)

--gemini-aurora-start      #4285F4     Gemini AI Blue (Model pill / Steer Active)
--gemini-aurora-mid        #7C4DFF     Gemini AI Violet (Active Neural Model)
--gemini-aurora-end        #00E5FF     Gemini AI Cyan (Longitudinal / Radar Active)
--gemini-glow-shadow       #334285F4   12dp Soft Gaussian Glow on active badges
================================================================================
```

### 2.3 Typography & Driver-Glanceability Hierarchy

- **Instruction Distance:** Google Sans / Roboto Bold, `34sp` (instantly readable at arm's length).
- **Maneuver Road Name:** Google Sans Medium, `22sp` (high contrast `#E3E3E3`).
- **Digital Speedometer ($v_{\text{Ego}}$):** Google Sans Flex / Bold, `64sp` (center-weighted).
- **Speed Limit Digits:** Highway Gothic / DIN Heavy, `32sp` (black digits on crisp white sign).
- **Chips & Button Labels:** Google Sans Medium, `14sp` (capsule padding `16dp` horizontal).

---

## 3. Feature 1: Turn-by-Turn Navigation & Map Engine

### 3.1 Capability Scope
- Display active navigation route, step-by-step maneuvers, turn distance, ETA, and remaining trip distance.
- Draw smooth route polylines and alternative routes on a custom Android Auto map surface.
- Allow searching POIs and addresses directly from the head unit with strict quota conservation (button-only submission, local `SearchCache` first, Mapbox SearchBox / Photon fallback, or comma `/api/navigation/search`).
- 1-tap route selection, route clearing, and favorite toggling.
- **Primary Map Engine (Comma-First on Wi-Fi/LAN):** Render raster map tiles directly from the comma via `GET /api/navigation/map/tiles/{z}/{x}/{y}.png?theme={theme}` over local LAN/hotspot (zero cellular data usage, zero Mapbox quota/costs, ultra-low latency).
- **BLE & Cellular Fallback:** Over Bluetooth LE bridge (`TransportType.BLUETOOTH`), `/api/navigation/map/tiles/*` returns `501 Not Implemented` due to bandwidth constraints. When on BLE, disconnected from comma, or when a tile is uncached (404/503), the renderer falls back to online sources (Mapbox Static API / OpenFreeMap / CARTO raster tiles).

### 3.2 Android Auto Screen Wireframes (Google Maps & Gemini Aesthetic)

#### 3.2.1 Active Navigation Map View (Widescreen Head Unit: 16:9 / 21:9)
```
+---------------------------------------------------------------------------------------------+
| +---------------------------------------------------------+                                 |
| | [ ^ 500 ft ]  Turn Right onto N Scottsdale Rd           |  [ 14:28 ETA | 12 mi | 18 min X]|
| |               Then merge onto Loop 101 N                |  <- G-Maps Floating ETA Capsule |
| +---------------------------------------------------------+                                 |
|   <- G-Maps Floating Green Header (#0F9D58, 20dp Radii)                                     |
|                                                                                             |
|   +--------------------+                                                                    |
|   |    SPEED LIMIT     |                        Route Polyline                              |
|   |      [  45  ]      |                             ||                                     |
|   |   +5 SLC OFFSET    |                            //                                      |
|   +--------------------+                           //  <-- Alt Route (+3 min)               |
|                                                   //                                        |
|   +--------------------+              ====[ Car Marker ]====>                               |
|   |      [  52  ]      |                         ||                                         |
|   |        MPH         |                         ||                                         |
|   | (• STEER ACTIVE •) |                                                                    |
|   +--------------------+                                                                    |
|     ^ Gemini Glow Pill                                                                      |
|                                                                             [ (Q) Recenter ]|
|                                                                             [  (+) Zoom In ]|
|                                                                             [  (-) Zoom Out]|
|                                                                             <- Floating FABs|
+---------------------------------------------------------------------------------------------+
| [ (•) TR14 AI Model ]   [ + Home ]   [ + Work ]   [ + Airport ]   [ Search Places ] [⚙ Pref]|
| ^ Gemini Aurora Pill    <- Google Drive M3 Assist Chips (32dp) -> <- G-Maps Action Capsule -|
+---------------------------------------------------------------------------------------------+
```

#### 3.2.2 Destination Search & Recents Overlay (Google Drive / Gemini Search Style)
```
+---------------------------------------------------------------------------------------------+
| < Back to Map    [ 🔍 Search destination or address...                       ] [ 🎙️ Voice ]|
|                  <- Google Drive M3 Search Capsule with Surface Elevation (#1E1F20)          |
+---------------------------------------------------------------------------------------------+
|  QUICK CHIPS:  [ 🏠 Home ]  [ 🏢 Work ]  [ ⛽ Gas Nearby ]  [ ☕ Coffee ]  [ ★ Starred ]    |
|  <- M3 Horizontal Filter Chips with subtle outline stroke (#444746)                          |
|                                                                                             |
|  Recent Destinations:                                                                       |
|  +---------------------------------------------------------------------------------------+  |
|  | 🕒  Phoenix Sky Harbor Int'l Airport (Terminal 4)                        [ Route Now ]|  |
|  |     3400 E Sky Harbor Blvd, Phoenix, AZ • 18 mi • 22 min                              |  |
|  +---------------------------------------------------------------------------------------+  |
|  | 🕒  Trader Joe's - Scottsdale Promenade                                  [ Route Now ]|  |
|  |     16251 N Scottsdale Rd, Scottsdale, AZ • 4.2 mi • 8 min                            |  |
|  +---------------------------------------------------------------------------------------+  |
|  | 🕒  Starbucks Drive-Thru                                                 [ Route Now ]|  |
|  |     7000 E Mayo Blvd, Phoenix, AZ • 1.8 mi • 4 min                                    |  |
|  +---------------------------------------------------------------------------------------+  |
+---------------------------------------------------------------------------------------------+
| [ 🗑️ Clear Recents ]                                               [ ⌨️ On-Screen Keypad ]  |
+---------------------------------------------------------------------------------------------+
```

### 3.3 API Integration & Data Flow
1. **Poll Navigation State:**  
   `GET /api/navigation/status?routeKey={cachedKey}` (1 Hz)  
   Returns `enabled`, `status` (`"disabled" | "needsKey" | "noDestination" | "waitingForLocation" | "routing" | "routeUnavailable" | "guiding" | "arrived"`), `destination`, `instruction` (`text`, `maneuverType`, `maneuverModifier`, `distanceMeters`, `remainingDistanceMeters`, `remainingDurationSeconds`), `route` (list of `{latitude, longitude}` points), `alternatives`, `selectedRoute`, `location`, `revision`, and `routeKey` (when `routeKey` matches the active route, `route` and `alternatives` are omitted and `routeUnchanged: true` is returned).
2. **Search Destinations:**  
   - **App-side Cache-First Search (Primary):** Button-only submit checks `SearchCache` first; cache misses query Mapbox SearchBox (`/search/searchbox/v1/suggest` + `/retrieve`) with free OpenStreetMap Photon (`photon.komoot.io`) fallback.
   - **Comma Geocoder Endpoint:** `POST /api/navigation/search` with payload `{"query": "Phoenix Sky Harbor", "autocomplete": true}` returning `{"results": [...]}`.
3. **Execute Navigation Actions:**  
   `POST /api/navigation/action` (returns `200 OK` with fresh status snapshot, or `409 Conflict` if `revision` is stale, triggering one status re-fetch and retry):
   - **Set Destination:**
     ```json
     {
       "action": "select",
       "destination": {
         "name": "Phoenix Sky Harbor",
         "latitude": 33.4352,
         "longitude": -112.0101,
         "address": "3400 E Sky Harbor Blvd, Phoenix, AZ"
       },
       "revision": "f395aa3603c74187a95a48cfc0e2ff32"
     }
     ```
   - **Select Alternative Route:** `{"action": "selectRoute", "index": 1, "revision": "..."}`
   - **Clear Route:** `{"action": "clear", "revision": "..."}`
   - **Add / Remove Favorite:** `{"action": "favorite", "destination": {...}, "revision": "..."}` / `{"action": "removeFavorite", "id": "...", "revision": "..."}`
4. **Comma-First Tile Engine (Primary vs. Fallback Pipeline):**  
   The map rendering pipeline adheres to a strict **comma-primary** policy when connected over IP (LAN / Hotspot):
   - **Primary (Comma Local Storage):**  
     Request tiles directly from `GET /api/navigation/map/tiles/{z}/{x}/{y}.png?theme={dark|light}` via local Wi-Fi / hotspot.
     - Latency: < 15ms over LAN / hotspot.
     - Data Cost: 0 MB cellular data, 0 Mapbox quota consumption.
     - Offline Capable: Always available even in zero-cell backcountry roads.
     - Theme Sync: Automatically matches vehicle day/night headlight status.
     - *BLE Note:* Skipped when `activeTransport == TransportType.BLUETOOTH` because the BLE bridge returns `501 Not Implemented` for raster tiles.
   - **Fallback (Cellular / Internet):**  
     If on BLE, if the comma request times out (>600ms), returns 404 (uncached bounding tile), or the comma client is disconnected, the renderer falls back to cellular online sources (Mapbox Static API or CARTO `a-d.basemaps.cartocdn.com` raster tiles).
   - **Cache Hierarchy:**  
     `Phone Memory (LruCache) -> Phone Disk Cache -> Comma Galaxy (:8082) [PRIMARY on IP] -> Cellular Network (Mapbox/CARTO) [FALLBACK]`.

### 3.4 Implementation Details (Kotlin)

```kotlin
// Data models matching Galaxy /api/navigation/status
data class GalaxyNavStatus(
    val enabled: Boolean,
    val status: String,
    val destination: NavDestination?,
    val instruction: GalaxyNavInstruction?,
    val route: List<NavPoint>?,
    val alternatives: List<NavAlternative>?,
    val selectedRoute: Int = 0,
    val routeUnchanged: Boolean = false,
    val revision: String,
    val routeKey: String?
)

data class GalaxyNavInstruction(
    val text: String,
    val maneuverType: String,
    val maneuverModifier: String,
    val distanceMeters: Double,
    val remainingDistanceMeters: Double,
    val remainingDurationSeconds: Double
)

class NavigationRepository(private val client: SgGalaxyClient) {
    fun pollNavStatus(): GalaxyNavStatus? {
        val resp = client.fetchNavStatus(NavStateManager.sgRouteKey) ?: return null
        val json = resp.json
        NavStateManager.sgNavStatus = json
        json.optString("revision", "").ifEmpty { null }?.let { NavStateManager.sgRevision = it }
        resp.routeKey?.let { NavStateManager.sgRouteKey = it }
        return parseNavStatus(json)
    }

    fun setDestination(dest: NavDestination): Boolean {
        return NavigationManager().sendToCommaSg(
            baseUrl = client.baseUrl,
            dest = dest,
            revision = NavStateManager.sgRevision
        )
    }
}

/**
 * Comma-primary tile provider with cellular fallback.
 */
class MapTileProvider(
    private val commaBaseUrl: String,
    private val memoryCache: android.util.LruCache<String, Bitmap>
) {
    fun getTile(z: Int, x: Int, y: Int, isNight: Boolean): Bitmap? {
        val theme = if (isNight) "dark" else "light"
        val key = "$z/$x/$y-$theme"
        memoryCache.get(key)?.takeIf { !it.isRecycled }?.let { return it }

        // 1. PRIMARY: Fetch from comma device over LAN/hotspot (skip on BLE where tiles return 501)
        if (NavStateManager.activeTransport != NavStateManager.TransportType.BLUETOOTH) {
            try {
                val url = URL("$commaBaseUrl/api/navigation/map/tiles/$z/$x/$y.png?theme=$theme")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 600
                    readTimeout = 600
                    GalaxyCloudSession.currentLocalToken()?.let { token ->
                        setRequestProperty("Cookie", "${GalaxyCloudSession.COOKIE_NAME}=$token")
                    }
                }
                try {
                    if (conn.responseCode == 200) {
                        val bytes = conn.inputStream.use { it.readBytes() }
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) {
                            memoryCache.put(key, bitmap)
                            return bitmap
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
                // Comma unavailable or tile not cached locally; fall through to cellular fallback
            }
        }

        // 2. FALLBACK: Fetch over cellular (CARTO basemaps)
        return fetchCellularFallbackTile(z, x, y, isNight)?.also { bitmap ->
            memoryCache.put(key, bitmap)
        }
    }

    private fun fetchCellularFallbackTile(z: Int, x: Int, y: Int, isNight: Boolean): Bitmap? {
        val baseStyle = if (isNight) "dark_all" else "light_all"
        val subdomain = "abcd"[Math.floorMod(x + y, 4)]
        val fallbackUrl = "https://$subdomain.basemaps.cartocdn.com/$baseStyle/$z/$x/$y.png"
        val conn = (URL(fallbackUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 3000
            readTimeout = 3000
            setRequestProperty("User-Agent", "ParamAuto/1.0")
        }
        return try {
            if (conn.responseCode == 200) {
                conn.inputStream.use { BitmapFactory.decodeStream(it) }
            } else null
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
```

---

## 4. Feature 2: Real-Time HUD Overlay & Live Telemetry Gauge Cluster

### 4.1 Capability Scope
- Display real-time vehicle speed ($v_{\text{Ego}}$) directly on the map surface in miles per hour or km/h.
- Show active openpilot status pill styled with **Google Gemini luminous glow effects**:
  - `(• STEER ACTIVE •)` (Luminous Gemini Cyan `#00E5FF`) when lateral assist is active.
  - `(• CRUISE ACTIVE •)` (Luminous Gemini Blue `#4285F4`) when adaptive cruise is active.
  - `(• OPENPILOT •)` (Gemini Aurora Gradient `#4285F4` -> `#7C4DFF`) when full longitudinal + lateral control is engaged.
  - `( STANDBY )` (M3 Dark Grey `#38393A`) when disengaged.
- Display the official road speed limit sign alongside the configured Speed Limit Controller (SLC) offset (e.g. `SPEED LIMIT 45  [+5]`).
  - *Known SG Gap Note:* Live accepted speed limit is not currently exposed by any SG typed GET endpoint (`PreviousSpeedLimit` existed only in legacy `/api/params/all`). When connected to an SG build without a known limit, the sign gracefully displays `--` (unavailable) rather than guessing or faking a limit.
- Visual steering torque bar showing real-time driver assist lateral force.

### 4.2 HUD Overlay Wireframe (Gemini & M3 Styling)

```
+--------------------------------------------------------------------+
|                         [ TOP TURN BANNER ]                        |
+--------------------------------------------------------------------+
|                                                                    |
|    +-------------------+                                           |
|    |    SPEED LIMIT    |  <- Official MUTCD Sign (2dp stroke)      |
|    |      [  45  ]     |     (Shows "--" if unavailable on SG)     |
|    |   +5 SLC OFFSET   |  <- Google M3 Blue Tint Badge (#1A2A44)   |
|    +-------------------+                                           |
|                                                                    |
|    +-------------------+                                           |
|    |       [ 50 ]      |  <- Large Speed Display (Google Sans 64sp)|
|    |        MPH        |                                           |
|    |  +-------------+  |                                           |
|    |  |• STEER ACTIVE| |  <- Gemini Glowing Capsule (Cyan/Blue)    |
|    |  +-------------+  |                                           |
|    |   [||||||.....]   |  <- Subtle Lateral Torque Meter           |
|    +-------------------+                                           |
|                                                                    |
+--------------------------------------------------------------------+
```

### 4.3 API Integration & Data Flow
1. **Drive State Check:**  
   `GET /api/device/state` (1 Hz)  
   Provides coarse ignition state (`"parked"`, `"standby"`, `"driving"`, or `null`).
2. **High-Rate Live Telemetry:**  
   `GET /api/plots/live` (1–2 Hz)  
   Payload fields used:
   - `speedMps`: raw speed in m/s ($1\text{ m/s} = 2.23694\text{ mph}$), or `null` when stale/offroad.
   - `speedSource`: `"carState" | "deviceMotion" | "unavailable"`.
   - `controlsActive`: Master engagement state (`Boolean?`).
   - `lateralControlActive`: Steering assist state (`Boolean?`).
   - `longitudinalControlActive`: Gas/brake radar cruise state (`Boolean?`).
   - `desiredLongitudinalAccel`, `actualLongitudinalAccel` (or `desiredAccelMps2` / `actualAccelMps2`): longitudinal acceleration targets.
   - `lateralPid`, `longitudinalPid`: Steering torque error and effort.
   - `controlsFresh`, `poseFresh`: Signal health flags.
3. **Speed Limit Controller Offset:**  
   `GET /api/settings/pages/slc` (Polled on startup and every 60s, backing off to 10m if rows omit keys)  
   Extracts `Offset1` through `Offset7` corresponding to the 7 SLC speed bands:
   - `Offset1`: $0\text{–}24\text{ mph}$ (`limitMph < 25`)
   - `Offset2`: $25\text{–}34\text{ mph}$ (`limitMph < 35`)
   - `Offset3`: $35\text{–}44\text{ mph}$ (`limitMph < 45`)
   - `Offset4`: $45\text{–}54\text{ mph}$ (`limitMph < 55`)
   - `Offset5`: $55\text{–}64\text{ mph}$ (`limitMph < 65`)
   - `Offset6`: $65\text{–}74\text{ mph}$ (`limitMph < 75`)
   - `Offset7`: $75+\text{ mph}$ (`limitMph >= 75`)

### 4.4 Implementation Details (Kotlin)

```kotlin
data class GalaxyPlotsLive(
    val speedMps: Double?,
    val controlsActive: Boolean,
    val lateralControlActive: Boolean,
    val longitudinalControlActive: Boolean,
    val controlsFresh: Boolean,
    val poseFresh: Boolean
)

class CarSurfaceHudDrawer {
    private val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E3E3E3") // M3 on-surface high contrast
        textSize = 64f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    // Gemini Aurora Gradient Pill Paint
    private val geminiPillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        maskFilter = android.graphics.BlurMaskFilter(12f, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }

    fun draw(canvas: Canvas, telemetry: GalaxyPlotsLive, slcLimitMph: Int?, slcOffsetMph: Int) {
        val speedMph = ((telemetry.speedMps ?: 0.0) * 2.23694).roundToInt().coerceAtLeast(0)

        // 1. Draw Speed Limit Sign with M3 container (shows "--" when limit is 0 / unavailable on SG)
        drawSpeedLimitSign(canvas, x = 60f, y = 140f, limit = slcLimitMph ?: 0, offset = slcOffsetMph)

        // 2. Draw Digital Speedometer
        canvas.drawText("$speedMph", 120f, 280f, speedPaint)

        // 3. Draw Gemini Luminous Engagement Pill
        val (pillColor, glowColor, pillText) = when {
            telemetry.controlsActive && telemetry.lateralControlActive -> Triple(
                Color.parseColor("#1A3828"), Color.parseColor("#00E5FF"), "• OPENPILOT •"
            )
            telemetry.lateralControlActive -> Triple(
                Color.parseColor("#172B4D"), Color.parseColor("#00E5FF"), "• STEER ACTIVE •"
            )
            telemetry.longitudinalControlActive || telemetry.controlsActive -> Triple(
                Color.parseColor("#172B4D"), Color.parseColor("#4285F4"), "• CRUISE ACTIVE •"
            )
            else -> Triple(
                Color.parseColor("#38393A"), Color.TRANSPARENT, "STANDBY"
            )
        }

        // Outer glow
        if (glowColor != Color.TRANSPARENT) {
            glowPaint.color = glowColor
            canvas.drawRoundRect(58f, 298f, 182f, 337f, 20f, 20f, glowPaint)
        }

        // Pill body & label
        geminiPillPaint.color = pillColor
        canvas.drawRoundRect(60f, 300f, 180f, 335f, 18f, 18f, geminiPillPaint)
        canvas.drawText(pillText, 120f, 322f, pillTextPaint)
    }

    private fun drawSpeedLimitSign(canvas: Canvas, x: Float, y: Float, limit: Int, offset: Int) {
        // Renders official MUTCD sign with limit (or "--" if <= 0) and "+$offset SLC OFFSET" badge
    }
}
```

---

## 5. Feature 3: Remote Settings, Personality Profiles & Quick Favorites

### 5.1 Capability Scope
- Adjust comma driving personality profiles (`Aggressive`, `Standard`, `Relaxed`) directly from the phone or car screen (while parked) without reaching for the comma device.
- Adjust Speed Limit Controller offset curves and follow distance margins.
- Two-phase preview and confirm pipeline to guarantee zero corrupted or conflicting parameters.
- Quick 1-tap destination shortcuts (`Bookmarks` for Home/Office/Starred, `POST /api/navigation/action` with `action: "favorite"`, and comma quick-access control tiles via `GET|POST /api/favorites/slots`) with **Google Drive assist chip styling**.

### 5.2 In-Car Settings & Profile Selector Wireframe (Google Drive & M3 Style)

```
+---------------------------------------------------------------------------------------------+
| < Back to Map                  DRIVING PREFERENCES & CONTROLS                               |
+---------------------------------------------------------------------------------------------+
|  Driving Personality Profile (Material 3 Segmented Filter Buttons):                         |
|  +---------------------------+ +---------------------------+ +---------------------------+  |
|  |  ( ) Aggressive           | |  (•) Standard (Active)    | |  ( ) Relaxed              |  |
|  |  Closer follow, tight gap | |  Balanced cruising        | |  Smooth accel, 1.8s gap   |  |
|  +---------------------------+ +---------------------------+ +---------------------------+  |
|  <- M3 Elevated Card Surfaces (#1E1F20) with 16dp radii and 1dp outline stroke              |
+---------------------------------------------------------------------------------------------+
|  Speed Limit Controller (SLC) Offset Profile:                                               |
|  Base Offset:  [ ( - )  M3 Button ]     +5 MPH (Active)     [ ( + ) M3 Button ]             |
|  Curve Offset: [ ( - )  M3 Button ]     +3 MPH (Active)     [ ( + ) M3 Button ]             |
+---------------------------------------------------------------------------------------------+
|  Quick Destination Shortcuts (Google Drive Assist Chips):                                   |
|  [ 🏠 Home ]           [ 🏢 Office ]           [ 🏋️ Gym ]          [ ➕ Add Shortcut ]      |
|  <- Capsule chips with icon, 32dp height, subtle hover glow                                  |
+---------------------------------------------------------------------------------------------+
|  [ 💾 Apply & Save (Preview -> Confirm) ]                         [ ✕ Discard Changes ]     |
+---------------------------------------------------------------------------------------------+
```

### 5.3 API Integration & Two-Phase Write Flow
Galaxy strictly rejects direct parameter mutations to avoid invalid safety parameters. All changes execute through a 2-phase handshake (and most `/api/settings/confirm` mutations require `state == "parked"`):

```
ParamAuto (Phone)                                Comma Galaxy Server
       |                                                        |
       |  1. POST /api/settings/preview                         |
       |     {"page": "profiles", "view": "...", "values": ...} |
       |------------------------------------------------------->|
       |                                                        | Validates changes,
       |                                                        | generates preview diff
       |                                                        | and temporary token.
       |  2. Returns 200 OK                                     |
       |     {"diff": {...}, "valid": true, "view": "rev_XYZ"}  |
       |<-------------------------------------------------------|
       |                                                        |
       |  3. POST /api/settings/confirm                         |
       |     {"view": "rev_XYZ", "confirmed": true,             |
       |      "intent": "save"}                                 |
       |------------------------------------------------------->|
       |                                                        | Commits to persistent
       |                                                        | openpilot Params!
       |  4. Returns 200 OK {"success": true}                   |
       |<-------------------------------------------------------|
```

### 5.4 Implementation Details (Kotlin)

```kotlin
class SettingsRepository(private val client: SgGalaxyClient) {

    fun updatePersonalityProfile(page: String, profileName: String, currentDeviceState: String?): Result<Boolean> {
        if (currentDeviceState != "parked") {
            return Result.failure(IllegalStateException("Vehicle must be parked to commit settings."))
        }
        return try {
            // 1. Fetch current view token
            val pageData = client.getJson("/api/settings/pages/$page")
                ?: return Result.failure(Exception("Failed to fetch settings page $page"))
            val currentView = pageData.optString("view", "").ifEmpty { null }
                ?: return Result.failure(Exception("Missing view token"))

            // 2. Preview mutation
            val previewPayload = JSONObject().apply {
                put("page", page)
                put("view", currentView)
                put("values", JSONObject().put("PersonalityProfile", profileName))
            }
            val (_, preview) = client.postJson("/api/settings/preview", previewPayload)
            if (preview?.optBoolean("valid", false) != true) {
                return Result.failure(Exception("Invalid profile parameter value"))
            }
            val confirmToken = preview.optString("view", "").ifEmpty { null }
                ?: return Result.failure(Exception("Missing confirm view token"))

            // 3. Confirm mutation
            val confirmPayload = JSONObject().apply {
                put("view", confirmToken)
                put("confirmed", true)
                put("intent", "save")
            }
            val (code, result) = client.postJson("/api/settings/confirm", confirmPayload)
            Result.success(code == 200 && result?.optBoolean("success", false) == true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
```

---

## 6. Feature 4: AI Model Management & Alert Sound Customization

### 6.1 Capability Scope
- Display the currently loaded neural network driving model name and version on the dashboard with **Gemini Aurora Badge** (e.g. `✨ TR14 👀📡`, `Regret Driven Framework V4`).
- Allow selecting and activating alternative downloaded models (e.g. switching between bundled models and community models like TR14/TR15/TR16).
- Model switching is strictly **locked to vehicle Parked state** (`state == "parked"`) to ensure driving safety.
- Customize audible alert sound packs (e.g. Default StarPilot chimes, Duck, Tesla, Old Engage Sounds).
- Display Driver Monitoring Neural Network (FLM) calibration status (`status`, `fresh`, `ageSeconds`, and block progress).

### 6.2 Model Manager & Sound Wireframe (Parked Screen - Gemini Style)

```
+---------------------------------------------------------------------------------------------+
| < Back                       ✨ AI MODEL LABORATORY & AUDIO                 [ 🅿️ PARKED OK ]|
+---------------------------------------------------------------------------------------------+
|  Active Neural Driving Model:                                                               |
|  +---------------------------------------------------------------------------------------+  |
|  | ✨ TR14 👀📡 (v8 • TR Series)                                         [ • ACTIVE • ] |  |
|  |    Released 2025-06-30 • Small model • GPU available                                  |  |
|  +---------------------------------------------------------------------------------------+  |
|  <- Gemini Gradient Border (#4285F4 -> #7C4DFF), Deep Slate Container (#1E1F20)              |
|                                                                                             |
|  Available Downloaded Models:                                                               |
|  +---------------------------------------------------------------------------------------+  |
|  | (•) TR14 👀📡 (v8)            - TR Series • Installed                         [ LOADED ]|  |
|  +---------------------------------------------------------------------------------------+  |
|  | ( ) Regret Driven V4          - Bundled current driving model                 [ SELECT ]|  |
|  +---------------------------------------------------------------------------------------+  |
|  | ( ) LeTR 👀📡                 - Community driving model                       [ SELECT ]|  |
|  +---------------------------------------------------------------------------------------+  |
+---------------------------------------------------------------------------------------------+
|  System Audio Theme: [ 🦆 Duck  ▼ ]                             [ ⬇️ Download Sound Pack ] |
+---------------------------------------------------------------------------------------------+
|  Driver Monitoring (FLM):  Status: Collecting  •  Age: 185m  •  Blocks: 0 / 8 required      |
+---------------------------------------------------------------------------------------------+
```

### 6.3 API Integration & Data Flow
1. **Model Catalog & Status:**  
   `GET /api/models/status` & `GET /api/models/manager`  
   Provides catalog and detailed model items (`value`, `label`, `version`, `series`, `released`, `installed`, `selectable`, `downloadAvailable`, `unavailableReason`).
2. **Model Activation (Parked Gate):**  
   Check `GET /api/device/state` -> if `state != "parked"`, the UI disables selection and displays an M3 warning banner: `"Shift into Park to switch driving models"`.  
   When parked, send `POST /api/models/active` with `{"model": "tr14223"}`.
3. **Sound Packs:**  
   `GET /api/sounds` returns `{"parked": true, "packs": [{"id": "duck", "name": "Duck", "installed": true}, ...], "job": null}`.  
   Download new packs (Parked only) via `POST /api/sounds/download` with `{"pack": "duck"}`.
4. **Driver Monitoring Calibration:**  
   `GET /api/flm/training-results` returns `current` object with `fresh`, `ageSeconds`, `enabled`, `status` (e.g. `"collecting"`), `progress` (`samples`, `blocks`, `requiredBlocks`), and `application` (`applied`, `reason`).

### 6.4 Implementation Details (Kotlin)

```kotlin
data class GalaxyModelItem(
    val value: String,
    val label: String,
    val version: String,
    val series: String,
    val installed: Boolean,
    val selectable: Boolean,
    val downloadAvailable: Boolean,
    val unavailableReason: String?
)

class ModelManagerRepository(private val client: SgGalaxyClient) {

    fun getInstalledModels(): List<GalaxyModelItem> {
        val json = client.getJson("/api/models/manager") ?: return emptyList()
        val arr = json.optJSONArray("models") ?: return emptyList()
        val out = mutableListOf<GalaxyModelItem>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val item = GalaxyModelItem(
                value = m.optString("value", ""),
                label = m.optString("label", ""),
                version = m.optString("version", ""),
                series = m.optString("series", ""),
                installed = m.optBoolean("installed", false),
                selectable = m.optBoolean("selectable", false),
                downloadAvailable = m.optBoolean("downloadAvailable", false),
                unavailableReason = m.optString("unavailableReason", "").ifEmpty { null }
            )
            if (item.value.isNotEmpty() && (item.installed || item.selectable)) {
                out.add(item)
            }
        }
        return out
    }

    fun activateModel(modelId: String, currentDeviceState: String?): Result<Boolean> {
        // Enforce Parked safety constraint
        if (currentDeviceState != "parked") {
            return Result.failure(IllegalStateException("Vehicle must be parked to change driving models."))
        }
        return try {
            val payload = JSONObject().put("model", modelId)
            val (code, response) = client.postJson("/api/models/active", payload)
            Result.success(code in 200..299 && response?.optBoolean("error", false) != true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
```

---

## 7. Android Automotive Expert Engineering Directives

Based on Google Automotive OS (AAOS) and Android Auto Tech Lead standards, the following three implementation directives are mandatory across the rendering and client pipelines:

### 7.1 Hardware Headlight & Ambient Tunnel Synchronization
- **Problem:** Clock-based or solar sunrise/sunset calculations fail when driving through highway tunnels, underground parking garages, or heavy storm cover.
- **Directive:** The rendering engine must hook directly into the vehicle's physical headlight status reported by `androidx.car.app.CarContext` (in `AppTheme.SYSTEM` mode) or `ThemeManager.isNightMode(carContext)`:
  ```kotlin
  // Reactive ambient day/night state from vehicle CAN bus / ThemeManager
  val isNightMode = carContext.isDarkMode || ThemeManager.isNightMode(carContext)

  // Pass directly to comma tile provider:
  val tileTheme = if (isNightMode) "dark" else "light"
  tileProvider.getTile(z, x, y, isNight = isNightMode)
  ```
- **Behavior:** When the vehicle enters a dark tunnel midday, the head unit triggers `isDarkMode = true` in <100ms. The tile engine instantly switches to dark tiles, and the HUD swaps to the low-glare OLED palette, preventing sudden white-screen night blindness.

### 7.2 Strict Back-to-Front 5-Layer Canvas Architecture (`CarMapRenderer.kt`)
- **Problem:** Asynchronous network tile loading, route geometry recalculations, and rapid HUD telemetry polling can cause visual tearing, tile flickering, or HUD elements getting occluded.
- **Directive:** `CarMapRenderer.render()` must enforce a strict 5-layer z-order rendering pipeline:

```
  Top (Z=5)    +-------------------------------------------------------------+
               |  LAYER 5: HUD & Overlay Layer (Always on top, never clipped)|
               |  - Floating Turn Banner (#0F9D58, 20dp radii)               |
               |  - Digital Speedometer (v_Ego) & Gemini Engagement Pill    |
               |  - Speed Limit Sign + SLC Delta Offset Badge                |
               |  - Floating Map Controls (Recenter Pill, Status Pills)      |
               +-------------------------------------------------------------+
                                              |
  (Z=4)        +-------------------------------------------------------------+
               |  LAYER 4: Vehicle Position & Orientation Layer              |
               |  - LERP-interpolated 3D Car Chevron & Street Name Pill      |
               +-------------------------------------------------------------+
                                              |
  (Z=3)        +-------------------------------------------------------------+
               |  LAYER 3: Route Geometry Layer                              |
               |  - Alternative Route Polylines (De-emphasized slate)        |
               |  - Active Route Polyline (#4285F4 / #00E5FF) with glow      |
               +-------------------------------------------------------------+
                                              |
  (Z=2)        +-------------------------------------------------------------+
               |  LAYER 2: Map Base Layer (Stitched 256x256 grid or Static)  |
               |  - Primary: /api/navigation/map/tiles/... (Local Comma)     |
               |  - Fallback: Cellular Mapbox Static / CARTO raster tiles    |
               +-------------------------------------------------------------+
                                              |
  Base (Z=1)   +-------------------------------------------------------------+
               |  LAYER 1: Base Canvas Background Surface                    |
               |  - Solid Slate (#131314 night / #F8F9FA day) + 3D Grid      |
               +-------------------------------------------------------------+
```

### 7.3 60 FPS Hardware Display Interpolation & Telemetry Smoothing (LERP)
- **Problem:** Comma navigation GPS positions arrive at 1 Hz (`/api/navigation/status`), and raw plots arrive at 1–2 Hz (`/api/plots/live`). Snapping vehicle coordinates and speed every second produces jarring, jerky motion on 60 Hz/90 Hz head unit displays.
- **Directive:** Decouple data polling from canvas rendering using Linear Interpolation (LERP) and dead-reckoning:
  1. **Position & Bearing Interpolation:** Run a display-synchronized `ValueAnimator` / `Choreographer` loop that smoothly glides the car marker between discrete 1 Hz coordinate updates:
     $$\text{pos}_{t} = \text{pos}_{\text{prev}} + (\text{pos}_{\text{target}} - \text{pos}_{\text{prev}}) \times \alpha$$
     $$\text{bearing}_{t} = \text{bearing}_{\text{prev}} + \text{angularDiff}(\text{bearing}_{\text{target}}, \text{bearing}_{\text{prev}}) \times \alpha$$
  2. **Speedometer Smoothing:** Filter instantaneous $v_{\text{Ego}}$ speed spikes using an Exponential Moving Average (EMA) or 5-sample median window before rendering to prevent rapid flicker of digital speed digits.

```kotlin
// Example 60 FPS car marker smoothing animator
class VehicleMarkerAnimator(private val onFrame: (LatLng, Float) -> Unit) {
    private var startPos = LatLng(0.0, 0.0)
    private var targetPos = LatLng(0.0, 0.0)
    private var startBearing = 0f
    private var targetBearing = 0f
    private var currentPos = LatLng(0.0, 0.0)
    private var currentBearing = 0f
    private var initialized = false

    private val animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000L // Synchronized with 1 Hz nav polling interval
        interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener { va ->
            val fraction = va.animatedFraction
            val curLat = startPos.latitude + (targetPos.latitude - startPos.latitude) * fraction
            val curLon = startPos.longitude + (targetPos.longitude - startPos.longitude) * fraction
            val curBrg = (startBearing + normalizeDegrees(targetBearing - startBearing) * fraction + 360f) % 360f
            currentPos = LatLng(curLat, curLon)
            currentBearing = curBrg
            onFrame(currentPos, currentBearing)
        }
    }

    fun animateTo(newPos: LatLng, newBearing: Float) {
        if (!initialized) {
            initialized = true
            startPos = newPos
            targetPos = newPos
            currentPos = newPos
            startBearing = newBearing
            targetBearing = newBearing
            currentBearing = newBearing
            onFrame(newPos, newBearing)
            return
        }
        // Start from the currently interpolated position so mid-flight updates never jump
        startPos = currentPos
        targetPos = newPos
        startBearing = currentBearing
        targetBearing = newBearing
        animator.cancel()
        animator.start()
    }

    private fun normalizeDegrees(diff: Float): Float = ((diff + 180f) % 360f + 360f) % 360f - 180f
}
```

---

## 8. Implementation Roadmap & Milestones

| Phase | Milestone | Primary Modules Modified | Deliverables |
|---|---|---|---|
| **Phase 1** | **M3 Design System & Live HUD** | `ThemeManager.kt`, `HudOverlay.kt`, `NavStateManager.kt` | Material 3 dark slate palette, Gemini aurora glow shaders, 1–2 Hz `/api/plots/live` telemetry poller, HUD speed & SLC offset sign. |
| **Phase 2** | **Turn-by-Turn Nav & Comma Tiles** | `CarMapRenderer.kt`, `NavigationManager.kt`, `OfflineTiles.kt` | Comma-primary tile engine (`/api/navigation/map/tiles/...`), cellular fallback tile stitcher, Google Maps style floating header banner, and route polyline rendering. |
| **Phase 3** | **Search & Drive Assist Chips** | `SearchScreen.kt`, `TopSearchBar.kt`, `Bookmarks.kt` | Google Drive style search capsule, horizontal assist chips (Home, Work), 1-tap route action via `/api/navigation/action`, and shortcut slots via `/api/favorites/slots`. |
| **Phase 4** | **Two-Phase Settings & Profiles** | `SettingsDialogs.kt`, `AndroidAutoScreenView.kt` | M3 segmented driving profile buttons (Aggressive/Standard/Relaxed) and SLC offset adjustment via preview/confirm handshake. |
| **Phase 5** | **Parked Model & Audio Setup** | `ModelManager.kt` (New), `NavScreen.kt` | Parked-only model manager drawer with Gemini gradient badge, active model status, and sound pack selection. |

---

## 9. Verification & Safety Guidelines

1. **Transport & Session Security:**
   - Local connections (LAN / Hotspot) obtain a `galaxy_session` cookie automatically via `GET /api/auth/session`.
   - Remote Galaxy Cloud connections (`https://galaxy.firestar.link/<slug>`) authenticate via `POST /api/auth/login` using the user's saved Galaxy password (`GalaxyCloudSession`). Cloud session cookies are never sent to local endpoints.
2. **Session Persistence & Auto-Retry:** HTTP requests maintain the `galaxy_session` cookie across polling calls and automatically re-authenticate and retry once on `401 Unauthorized` (session expiry ~30 min).
3. **Parked Safety Interlocks:** Any endpoint classified as state-sensitive (`/api/models/active`, `/api/software/action`, `/api/settings/confirm`, `/api/sounds/download`) must verify `GET /api/device/state` returns `state == "parked"` before dispatching the request.
4. **Android Auto Driver Distraction Rules (Google Automotive Design Guidelines):**
   - Maximum 6 items per list while vehicle is in motion.
   - Touch targets $\ge 48\text{ dp}$ (minimum $56\text{ dp}$ for primary actions).
   - High contrast night palette (OLED dark `#131314`) to prevent driver night-blindness.
   - Complex dialogs, model managers, and keypad inputs locked behind parked state.
