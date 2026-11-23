package com.parampilot.auto

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Native Android Auto destination search screen.
 *
 * Quota discipline (Mapbox SearchBox has a tight session limit):
 * - Typing NEVER triggers a network search. While typing, only results that
 *   are already in the local [SearchCache] are shown (pure local filtering).
 * - A network search runs ONLY on explicit submit (Search button / IME search
 *   action), and the cache is checked first — a hit renders instantly with no
 *   network call and no loading spinner. Misses go to Mapbox SearchBox with
 *   the Photon fallback, and successful results are written to the cache.
 * - With an empty query, the screen opens on Home / Work shortcuts and the
 *   locally stored most-visited destinations — all zero-network.
 */
class SearchScreen(
    carContext: CarContext,
    private val client: CommaClient
) : Screen(carContext), SearchTemplate.SearchCallback {

    private val navManager = NavigationManager()
    private var searchQuery: String = ""
    private var isLoading: Boolean = false
    private val searchResults = mutableListOf<NavDestination>()

    // Auto-search after the text settles (voice fix, Param 2026-11-21):
    // Android Auto delivers a completed voice search as a text change
    // with no submission, so waiting for onSearchSubmitted left voice
    // searches with no results at all. When the text has been stable
    // for AUTO_SEARCH_DELAY_MS, the real search runs — the same
    // debounce discipline as the phone's search bar.
    private val autoSearchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingAutoSearch: Runnable? = null

    /** Incremented by every performSearch; a completion whose
     * generation is stale (a newer search superseded it) is dropped. */
    private var searchGeneration = 0

    /** Query whose explicit search came back empty (drives the "No results" row). */
    private var noResultsForQuery: String? = null

    /** The comma search's own error for the last empty completion
     * (Addendum 142): lets the empty row say the search FAILED
     * instead of claiming no results exist. */
    private var lastSearchCommaError: String? = null

    init {
        // Backstop: if this screen is torn down mid-entry (popped, session
        // ended), destination entry is over — stop feeding phone GPS.
        // (car-app 1.7 Screen has no onStop override; lifecycle is exposed
        // via getLifecycle().)
        lifecycle.addObserver(androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) PhoneGpsFeeder.stopEntrySession()
        })
    }

    override fun onSearchTextChanged(searchText: String) {
        // Local cached matches render instantly; the network search
        // follows automatically once the text settles (see
        // scheduleAutoSearch) — that is also what makes voice search
        // work, since voice arrives as a text change with no submit.
        // Typing a destination is when the comma needs the phone's position:
        // start feeding on the empty -> non-empty transition. The feeder's
        // 120 s cap and the stop() in onDestinationSelected bound the session.
        if (searchQuery.isEmpty() && searchText.isNotEmpty()) {
            // A fresh destination entry starts a fresh comma search session.
            NavigationManager.beginCommaSearchSession()
            val feederEndpoint = client.commaIp ?: NavStateManager.commaIp
            if (!feederEndpoint.isNullOrBlank()) PhoneGpsFeeder.start(feederEndpoint)
        } else if (searchQuery.isNotEmpty() && searchText.isEmpty()) {
            PhoneGpsFeeder.stopEntrySession()
            if (NavStateManager.isSgGalaxy) {
                val target = client.getEffectiveNavigationEndpoint()
                if (!target.isNullOrBlank()) {
                    Thread {
                        navManager.cancelCommaSearchSg(
                            SgGalaxyClient.baseUrlFor(target), NavStateManager.sgRevision
                        )
                    }.start()
                }
            }
        }
        searchQuery = searchText
        noResultsForQuery = null
        if (searchText.isEmpty()) isLoading = false
        searchResults.clear()
        if (searchText.length >= 2) {
            val starredMatches = Bookmarks.getStarred(carContext).filter {
                it.name.contains(searchText, ignoreCase = true) || it.placeName.contains(searchText, ignoreCase = true)
            }
            searchResults.addAll(starredMatches)
            val cached = SearchCache.filterCached(carContext, searchText).let { list ->
                if (NavStateManager.usePhoneSearch) list else list.filter { it.source == "comma" }
            }
            for (c in cached) {
                if (searchResults.none { it.latitude == c.latitude && it.longitude == c.longitude }) {
                    searchResults.add(c)
                }
            }
        }
        scheduleAutoSearch(searchText)
        invalidate()
    }

    /**
     * Runs the real search once the text has been stable for
     * [AUTO_SEARCH_DELAY_MS]. Voice input lands here: Android Auto
     * delivers the recognized text as a text change and never submits
     * it, so without this the voice search showed only local matches
     * (usually none) forever (Param, 2026-11-21).
     */
    private fun scheduleAutoSearch(text: String) {
        cancelPendingAutoSearch()
        if (text.length < 2) return
        val runnable = Runnable {
            pendingAutoSearch = null
            if (searchQuery == text) performSearch(text)
        }
        pendingAutoSearch = runnable
        autoSearchHandler.postDelayed(runnable, AUTO_SEARCH_DELAY_MS)
    }

    private fun cancelPendingAutoSearch() {
        pendingAutoSearch?.let { autoSearchHandler.removeCallbacks(it) }
        pendingAutoSearch = null
    }

    override fun onSearchSubmitted(searchTerm: String) {
        searchQuery = searchTerm
        if (searchTerm.isNotEmpty()) {
            performSearch(searchTerm)
        }
    }

    /** Explicit user-initiated search: cache first, network only on a miss. */
    private fun performSearch(query: String) {
        cancelPendingAutoSearch()
        val generation = ++searchGeneration
        val cached = SearchCache.lookup(carContext, query)?.takeIf { results ->
            NavStateManager.usePhoneSearch || (results.isNotEmpty() && results.all { it.source == "comma" })
        }
        if (cached != null) {
            // Cache hit: render instantly, no spinner, no network.
            searchResults.clear()
            searchResults.addAll(cached)
            noResultsForQuery = if (cached.isEmpty()) query else null
            lastSearchCommaError = null
            isLoading = false
            invalidate()
            return
        }

        isLoading = true
        invalidate()
        Thread {
            val token = client.mapboxToken ?: NavStateManager.mapboxToken
                ?: carContext.getSharedPreferences("comma_client", android.content.Context.MODE_PRIVATE).getString("mapbox_token", null)
            val fallbackHome = Bookmarks.getHome(carContext) ?: Bookmarks.defaultFallbackHome
            val gps = NavStateManager.currentGps
            val lat = gps?.latitude ?: fallbackHome.latitude
            val lng = gps?.longitude ?: fallbackHome.longitude

            var source = "photon"
            val commaEndpoint = client.commaIp ?: NavStateManager.commaIp

            val results: List<NavDestination> = if (!NavStateManager.usePhoneSearch) {
                source = "comma"
                if (!commaEndpoint.isNullOrBlank()) {
                    navManager.searchPlacesCommaSg(query, SgGalaxyClient.baseUrlFor(commaEndpoint))
                } else {
                    emptyList()
                }
            } else if (!token.isNullOrBlank()) {
                val r = navManager.searchPlaces(query, lat, lng, token)
                if (r.isNotEmpty()) {
                    source = "mapbox"
                    r
                } else {
                    navManager.searchPlacesPhoton(query, lat, lng)
                }
            } else {
                navManager.searchPlacesPhoton(query, lat, lng)
            }

            if (results.isNotEmpty()) {
                SearchCache.put(carContext, query, results, source)
                for (d in results) {
                    if (d.hasCoordinates) {
                        SearchCache.putResolved(carContext, d)
                        DestinationHistory.record(carContext, d)
                    }
                }
            }

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                // A newer search superseded this one while it ran —
                // its results own the screen now.
                if (generation == searchGeneration) {
                    if (source == "comma" && results.isEmpty()) {
                        NavigationManager.lastCommaSearchError?.let { err ->
                            CarToast.makeText(carContext, "Comma search: $err", CarToast.LENGTH_LONG).show()
                        }
                    }
                    searchResults.clear()
                    searchResults.addAll(results)
                    noResultsForQuery = if (results.isEmpty()) query else null
                    lastSearchCommaError =
                        if (results.isEmpty() && source == "comma") {
                            NavigationManager.lastCommaSearchError
                        } else {
                            null
                        }
                    isLoading = false
                    invalidate()
                }
            }
        }.start()
    }

    private fun onDestinationSelected(dest: NavDestination) {
        cancelPendingAutoSearch()
        // Queue lock (Param, 2026-11-21): while a destination is
        // queued, no NEW destination is accepted — the queued one
        // must be sent or cancelled first.
        val queuedNow = NavStateManager.queuedDestination
            ?: DestinationQueue.loadEntry(carContext)?.displayDestination()
        if (queuedNow != null && !queuedNow.name.equals(dest.name, ignoreCase = true)) {
            CarToast.makeText(
                carContext,
                "A destination is already queued — it must be sent or cancelled before choosing another",
                CarToast.LENGTH_LONG
            ).show()
            return
        }
        NavStateManager.markDestinationSelected(dest)
        val feederTarget = client.getEffectiveNavigationEndpoint()
        if (NavStateManager.usePhoneLocationToComma && !feederTarget.isNullOrBlank()) {
            PhoneGpsFeeder.start(feederTarget)
        }
        isLoading = true
        invalidate()
        Thread {
            val token = client.mapboxToken ?: NavStateManager.mapboxToken
                ?: carContext.getSharedPreferences("comma_client", android.content.Context.MODE_PRIVATE).getString("mapbox_token", null)
            // SG comma suggestion (temporary, no coordinates of its own):
            // the comma retrieves the place itself via selectPlace — never
            // resolve it through Mapbox. source="comma" is load-bearing:
            // Mapbox suggestions carry Mapbox ids in the same two fields.
            val target = client.getEffectiveNavigationEndpoint()
            val resolvedCandidate = SearchCache.applyResolved(dest)
            val isCommaSuggestion = NavStateManager.isSgGalaxy && resolvedCandidate.source == "comma" &&
                resolvedCandidate.mapboxId != null && resolvedCandidate.sessionToken != null && !resolvedCandidate.hasCoordinates
            val resolved: NavDestination
            val sendOk: Boolean
            if (isCommaSuggestion) {
                resolved = resolvedCandidate
                sendOk = navManager.selectCommaPlaceSg(
                    SgGalaxyClient.baseUrlFor(target), resolvedCandidate, NavStateManager.sgRevision
                )
            } else {
                // If this place was resolved before, its coordinates are in the
                // cache — reuse them and skip the billable retrievePlace call.
                val cachedResolved = if (resolvedCandidate.hasCoordinates) resolvedCandidate else {
                    SearchCache.findResolved(carContext, resolvedCandidate)?.takeIf { it.hasCoordinates }
                }
                var res: NavDestination = cachedResolved
                    ?: if (!resolvedCandidate.hasCoordinates && !token.isNullOrBlank()) {
                        navManager.retrievePlace(resolvedCandidate, token) ?: resolvedCandidate
                    } else {
                        resolvedCandidate
                    }
                var ok: Boolean? = null
                if (!res.hasCoordinates && NavStateManager.isSgGalaxy) {
                    // Same fallback as the phone (same logic in both
                    // displays): a selection the phone side cannot
                    // resolve — e.g. a cached Mapbox suggestion with no
                    // phone token set — is resolved by the comma's own
                    // search + selectPlace, never sent coordinate-less.
                    val matches = navManager.searchPlacesCommaSg(
                        resolvedCandidate.name, SgGalaxyClient.baseUrlFor(target)
                    )
                    val match = CommaPlaces.bestMatch(matches, resolvedCandidate.name)
                    if (match != null) {
                        res = match
                        ok = navManager.selectCommaPlaceSg(
                            SgGalaxyClient.baseUrlFor(target), match, NavStateManager.sgRevision
                        )
                    } else {
                        NavigationManager.noteSendError(
                            "could not resolve ${resolvedCandidate.name} — the comma found no match, and no phone Mapbox token is set"
                        )
                        ok = false
                    }
                }
                if (ok == null) {
                    // Send to comma first before recording history or changing active state (H17)
                    ok = if (NavStateManager.isSgGalaxy) {
                        navManager.sendToCommaSg(
                            SgGalaxyClient.baseUrlFor(target), res, NavStateManager.sgRevision
                        )
                    } else {
                        val cookie = client.getGalaxyCookie()
                        navManager.sendToComma(target, res, cookie)
                    }
                }
                resolved = res
                sendOk = ok == true
            }
            if (sendOk && !target.isNullOrBlank()) {
                PhoneGpsFeeder.startNavigationSession(target)
            } else if (!sendOk) {
                PhoneGpsFeeder.stop()
            }

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (sendOk) {
                    // A live send of the queued destination itself
                    // clears the queue — it is no longer waiting.
                    if (NavStateManager.queuedDestination?.isSameAs(resolved) == true) {
                        DestinationQueue.clear(carContext)
                        NavStateManager.queuedDestination = null
                        NavStateManager.queuedOutOfSync = false
                    }
                    DestinationHistory.record(carContext, resolved)
                    NavStateManager.activeDestination = resolved
                    NavStateManager.setNavClearedByUser(false)
                    NavStateManager.activeTurnCue = "Head toward ${resolved.name}"
                    NavStateManager.notifyChanged()

                    NavigationManager.startNavigationRefresh { client.getEffectiveNavigationEndpoint() }
                    try {
                        val carNavManager = carContext.getCarService(androidx.car.app.navigation.NavigationManager::class.java)
                        carNavManager.navigationStarted()
                    } catch (e: Exception) {
                        Log.w("SearchScreen", "Failed to start car navigation: ${e.message}")
                    }
                    CarToast.makeText(carContext, "Navigating to ${resolved.name}", CarToast.LENGTH_SHORT).show()
                    try { screenManager.pop() } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
                } else {
                    // Addendum 122/123: queue on ANY send failure —
                    // offline, or connected but not accepted (out of
                    // sync). The flush retries every 15 s while the
                    // comma is connected.
                    val outOfSync = NavStateManager.commaFound
                    var queued = false
                    if (resolved.hasCoordinates) {
                        DestinationQueue.save(carContext, resolved)
                        NavStateManager.queuedDestination = resolved
                        NavStateManager.queuedOutOfSync = outOfSync
                        NavStateManager.notifyChanged()
                        queued = true
                    } else if (isCommaSuggestion) {
                        val id = resolvedCandidate.mapboxId?.takeIf { it.isNotBlank() }
                        val query = searchQuery.trim().ifEmpty { null }
                        if (id != null || query != null) {
                            DestinationQueue.saveCommaPlace(carContext, resolvedCandidate.name, resolvedCandidate.placeName, id, query)
                            NavStateManager.queuedDestination = resolvedCandidate
                            NavStateManager.queuedOutOfSync = outOfSync
                            NavStateManager.notifyChanged()
                            queued = true
                        }
                    }
                    if (queued) {
                        CarToast.makeText(
                            carContext,
                            DestinationQueue.label(resolved, outOfSync, NavStateManager.commaNetworks),
                            CarToast.LENGTH_LONG
                        ).show()
                        try { screenManager.pop() } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
                    } else {
                        CarToast.makeText(
                            carContext,
                            "Destination NOT accepted by the comma — ${NavigationManager.lastSendError ?: "not connected"}",
                            CarToast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }.start()
    }

    /**
     * Subtitle for a Home/Work shortcut row: "~<eta> min · <dist> mi" with
     * the distance measured by haversine from the current GPS and the ETA a
     * plain estimate at an assumed 25 mph (hence the "~"). Returns null
     * when there is no GPS — the row then shows just its name, with no
     * invented numbers.
     */
    private fun shortcutSubtitle(gps: GpsPosition?, dest: NavDestination): String? {
        if (gps == null || !dest.hasCoordinates) return null
        val miles = GuidanceMath.haversineM(gps.latitude, gps.longitude, dest.latitude, dest.longitude) / Units.METERS_PER_MILE
        val etaMin = (miles / 25.0 * 60.0).roundToInt().coerceAtLeast(1)
        return "$etaMin min · ${"%.1f".format(miles)} mi"
    }


    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()

        // Search budget meter (Feature 6) — the same label the phone's
        // search bar shows, from the same shared usage state.
        val budgetLabel = MapboxUsage.labelFor(NavStateManager.mapboxUsage)
        if (budgetLabel != null && searchQuery.isEmpty()) {
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(budgetLabel)
                    .build()
            )
        }

        val queuedDest = NavStateManager.queuedDestination
        if (queuedDest != null && searchQuery.isEmpty()) {
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(DestinationQueue.label(queuedDest, NavStateManager.queuedOutOfSync, NavStateManager.commaNetworks))
                    .addText("Tap to cancel")
                    .setOnClickListener {
                        DestinationQueue.clear(carContext)
                        NavStateManager.queuedDestination = null
                        NavStateManager.queuedOutOfSync = false
                        NavStateManager.notifyChanged()
                        CarToast.makeText(carContext, "Queued destination cancelled", CarToast.LENGTH_SHORT).show()
                        invalidate()
                    }
                    .build()
            )
        }

        if (searchResults.isNotEmpty()) {
            for (dest in searchResults.take(6)) {
                val distStr = dest.distanceM?.let { m ->
                    val mi = m / Units.METERS_PER_MILE
                    if (mi < 10) " (${"%.1f".format(mi)} mi)" else " (${mi.toInt()} mi)"
                } ?: ""

                listBuilder.addItem(
                    Row.Builder()
                        .setTitle("${dest.name}$distStr")
                        .addText(dest.placeName.ifEmpty { "Location" })
                        .setOnClickListener { onDestinationSelected(dest) }
                        .build()
                )
            }
        } else if (!isLoading && noResultsForQuery != null && noResultsForQuery == searchQuery) {
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(
                        CarScreenNotice.searchEmptyText(
                            searchQuery,
                            PhoneConnectivity.hasInternet(carContext),
                            lastSearchCommaError
                        )
                    )
                    .build()
            )
        } else if (searchQuery.isEmpty() && !isLoading) {
            // Search home: shortcuts first, then most visited — all local.
            // Home/Work come from the canonical Bookmarks definitions; the
            // subtitle is a real haversine distance from the current GPS
            // (with a clearly-marked "~" ETA estimate), never a canned value.
            val isHomeConfigured = Bookmarks.isHomeConfigured(carContext)
            val homeDest = Bookmarks.getHome(carContext)
            val isOfficeConfigured = Bookmarks.isOfficeConfigured(carContext)
            val officeDest = Bookmarks.getOffice(carContext)
            val shortcutGps = NavStateManager.currentGps

            // Home Row
            if (isHomeConfigured && homeDest != null) {
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle("Home")
                        .apply { shortcutSubtitle(shortcutGps, homeDest)?.let { addText(it) } }
                        .setOnClickListener { onDestinationSelected(homeDest) }
                        .build()
                )
            } else {
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle("Home")
                        .addText("Set location on phone")
                        .setOnClickListener {
                            CarToast.makeText(carContext, "Set Home in ParamAuto on your phone", CarToast.LENGTH_SHORT).show()
                        }
                        .build()
                )
            }

            // Work Row
            if (isOfficeConfigured && officeDest != null) {
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle("Work")
                        .apply { shortcutSubtitle(shortcutGps, officeDest)?.let { addText(it) } }
                        .setOnClickListener { onDestinationSelected(officeDest) }
                        .build()
                )
            } else {
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle("Work")
                        .addText("Set location on phone")
                        .setOnClickListener {
                            CarToast.makeText(carContext, "Set Work in ParamAuto on your phone", CarToast.LENGTH_SHORT).show()
                        }
                        .build()
                )
            }

            // Quick Filter Category Rows
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("Gas stations")
                    .setOnClickListener {
                        searchQuery = "Gas station"
                        performSearch("Gas station")
                    }
                    .build()
            )
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("Coffee")
                    .setOnClickListener {
                        searchQuery = "Coffee"
                        performSearch("Coffee")
                    }
                    .build()
            )

            // Saved / Starred Places
            val starred = Bookmarks.getStarred(carContext)
            if (starred.isNotEmpty()) {
                for (dest in starred.take(4)) {
                    listBuilder.addItem(
                        Row.Builder()
                            .setTitle(dest.name)
                            .apply {
                                val sub = dest.placeName.takeIf { it.isNotEmpty() && it != dest.name }
                                if (sub != null) addText(sub)
                            }
                            .setOnClickListener { onDestinationSelected(dest) }
                            .build()
                    )
                }
            }

            // The comma's own places (Feature 8): favorites and recents
            val commaFavorites = NavStateManager.commaFavorites
            val commaRecents = NavStateManager.commaRecents.filter { r ->
                commaFavorites.none { it.isSameAs(r) }
            }
            val commaPlaces = (commaFavorites + commaRecents).filter { r ->
                val matchesHome = isHomeConfigured && homeDest != null && (r.isSameAs(homeDest) || r.name.equals("Home", ignoreCase = true))
                val matchesWork = isOfficeConfigured && officeDest != null && (r.isSameAs(officeDest) || r.name.equals("Office", ignoreCase = true) || r.name.equals("Work", ignoreCase = true))
                val matchesStarred = starred.any { it.isSameAs(r) }
                !matchesHome && !matchesWork && !matchesStarred
            }
            if (commaPlaces.isNotEmpty()) {
                for (dest in commaPlaces.take(3)) {
                    listBuilder.addItem(
                        Row.Builder()
                            .setTitle(dest.name)
                            .apply {
                                val sub = dest.placeName.takeIf { it.isNotEmpty() && it != dest.name } ?: "From comma"
                                addText(sub)
                            }
                            .setOnClickListener { onDestinationSelected(dest) }
                            .build()
                    )
                }
            }

            // Recent destinations (deduplicated against Home, Work, Starred, and comma places)
            val recent = DestinationHistory.recent(carContext, 6).filter { r ->
                val matchesHome = isHomeConfigured && homeDest != null && (r.isSameAs(homeDest) || r.name.equals("Home", ignoreCase = true))
                val matchesWork = isOfficeConfigured && officeDest != null && (r.isSameAs(officeDest) || r.name.equals("Office", ignoreCase = true) || r.name.equals("Work", ignoreCase = true))
                val matchesStarred = starred.any { it.isSameAs(r) }
                val matchesComma = commaPlaces.any { it.isSameAs(r) }
                !matchesHome && !matchesWork && !matchesStarred && !matchesComma
            }.take(3)
            if (recent.isNotEmpty()) {
                for (dest in recent) {
                    listBuilder.addItem(
                        Row.Builder()
                            .setTitle(dest.name)
                            .apply {
                                val sub = dest.placeName.takeIf { it.isNotEmpty() && it != dest.name }
                                if (sub != null) addText(sub)
                            }
                            .setOnClickListener { onDestinationSelected(dest) }
                            .build()
                    )
                }
            }
        }

        val builder = SearchTemplate.Builder(this)
            .setHeaderAction(Action.BACK)
            .setSearchHint("Search destination...")
            .setInitialSearchText(searchQuery)

        if (isLoading) {
            builder.setLoading(true)
        } else {
            builder.setItemList(listBuilder.build())
        }

        return builder.build()
    }

    companion object {
        /** How long the search text must be stable before the
         * auto-search runs (voice completion + typing pauses). */
        const val AUTO_SEARCH_DELAY_MS = 800L
    }
}
