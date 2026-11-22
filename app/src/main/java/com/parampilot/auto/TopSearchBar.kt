package com.parampilot.auto

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Google Maps Material 3 floating search bar with quick shortcut chips and
 * clean vector-icon suggestion rows.
 */
class TopSearchBar(
    private val context: Context,
    private val onSearchQuery: (String) -> Unit,
    private val onDestinationSelected: (NavDestination) -> Unit,
    private val onConfigureHomeRequested: () -> Unit = {},
    private val onConfigureOfficeRequested: () -> Unit = {},
    private val onStarDestinationClicked: (NavDestination) -> Unit = {},
    private val onVoiceSearchRequested: () -> Unit = {}
) {

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()

    private lateinit var searchIconView: PlaceVectorIconView
    private lateinit var searchInput: EditText
    private lateinit var clearBtn: TextView
    private lateinit var micBtn: PlaceVectorIconView
    private lateinit var queuedRow: LinearLayout
    private lateinit var queuedLabel: TextView
    private var queuedDestShown: NavDestination? = null
    private var queuedShownSync: Boolean = false
    private var onQueuedCancel: (() -> Unit)? = null
    private lateinit var quickChipsRow: LinearLayout
    private lateinit var resultsCard: ScrollView
    private lateinit var resultsList: LinearLayout
    private lateinit var searchLoadingRow: LinearLayout

    /** True while a network search is in flight: the spinner row sits
     * above any interim (cached / starred) rows so they are not
     * mistaken for the final results (Param, 2026-11-21). */
    private var searchLoading: Boolean = false
    private lateinit var searchBarLayout: LinearLayout

    private var isUserTyping: Boolean = false
    private var isProgrammaticUpdate: Boolean = false
    private lateinit var rootContainer: LinearLayout

    private var lastRenderedResults: List<NavDestination> = emptyList()

    private val searchHandler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null

    private fun cancelPendingSearch() {
        pendingSearch?.let { searchHandler.removeCallbacks(it) }
        pendingSearch = null
    }

    private fun scheduleSearch(query: String) {
        cancelPendingSearch()
        val runnable = Runnable { onSearchQuery(query) }
        pendingSearch = runnable
        searchHandler.postDelayed(runnable, SEARCH_DEBOUNCE_MS)
    }

    fun attachTo(root: FrameLayout) {
        rootContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(12), dp(42), dp(12), 0)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP
            }
        }

        // 1. Google Maps Pill Search Bar (50dp height, 25dp capsule radius)
        val searchBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = HudOverlay.makeCardBg(context, HudOverlay.COLOR_SURFACE_DARK, 25, HudOverlay.COLOR_BORDER_DARK, 1)
            elevation = 8f * density
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(50)
            )
            setPadding(dp(14), 0, dp(12), 0)
            setOnClickListener {
                searchInput.requestFocus()
                if (searchInput.text.isNullOrEmpty()) {
                    showInitialSuggestions()
                }
            }
        }
        searchBarLayout = searchBar

        searchIconView = PlaceVectorIconView(context, PlaceVectorIconView.IconKind.SEARCH, withCircle = false).apply {
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                setMargins(0, 0, dp(10), 0)
            }
        }
        searchBar.addView(searchIconView)

        searchInput = EditText(context).apply {
            hint = "Search here"
            setHintTextColor(HudOverlay.COLOR_TEXT_MUTED)
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            background = null
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)

            setOnClickListener {
                if (text.isNullOrEmpty()) {
                    showInitialSuggestions()
                }
            }

            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && text.isNullOrEmpty()) {
                    showInitialSuggestions()
                }
            }

            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_GO) {
                    cancelPendingSearch()
                    val q = text.toString().trim()
                    if (q.isNotEmpty()) onSearchQuery(q)
                    hideKeyboard()
                    true
                } else false
            }

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (isProgrammaticUpdate) return
                    val hasText = !s.isNullOrEmpty()
                    isUserTyping = hasFocus() && hasText
                    clearBtn.visibility = if (hasText) View.VISIBLE else View.GONE
                    if (isUserTyping && s!!.length >= 2) {
                        scheduleSearch(s.toString().trim())
                    } else if (s.isNullOrEmpty()) {
                        cancelPendingSearch()
                        if (hasFocus()) {
                            showInitialSuggestions()
                        } else {
                            hideResults()
                        }
                    } else {
                        cancelPendingSearch()
                    }
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        searchBar.addView(searchInput)

        clearBtn = TextView(context).apply {
            text = "✕"
            textSize = 15f
            setTextColor(HudOverlay.COLOR_TEXT_MUTED)
            setPadding(dp(10), dp(8), dp(8), dp(8))
            visibility = View.GONE
            setOnClickListener {
                searchInput.setText("")
                searchInput.requestFocus()
                showInitialSuggestions()
            }
        }
        searchBar.addView(clearBtn)

        // Voice search (Feature 18): hands off to the system speech UI via
        // MainActivity; hidden entirely when no recognizer is installed.
        micBtn = PlaceVectorIconView(context, PlaceVectorIconView.IconKind.MIC, withCircle = false).apply {
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply {
                setMargins(dp(2), 0, 0, 0)
            }
            visibility = if (VoiceSearch.isAvailable(context)) View.VISIBLE else View.GONE
            setOnClickListener { onVoiceSearchRequested() }
        }
        searchBar.addView(micBtn)
        rootContainer.addView(searchBar)

        // 1c. Offline queue chip (Feature 10): the destination waiting to
        // be sent when the comma reconnects; tapping cancels the queue.
        queuedRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = HudOverlay.makeCardBg(context, HudOverlay.COLOR_SURFACE_DARK, 16, HudOverlay.COLOR_ACCENT_AMBER, 1)
            elevation = 6f * density
            setPadding(dp(12), dp(7), dp(8), dp(7))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(2), dp(8), dp(2), 0)
            }
            visibility = View.GONE
            setOnClickListener { onQueuedCancel?.invoke() }
        }
        queuedLabel = TextView(context).apply {
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFFE3E3E3.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        queuedRow.addView(queuedLabel)
        queuedRow.addView(TextView(context).apply {
            text = "✕"
            textSize = 14f
            setTextColor(HudOverlay.COLOR_ACCENT_AMBER)
            setPadding(dp(10), dp(4), dp(8), dp(4))
            setOnClickListener { onQueuedCancel?.invoke() }
        })
        rootContainer.addView(queuedRow)

        // 2. Google Maps Quick Shortcut Chips Row (Home, Work, Saved) below Search Bar
        quickChipsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(2), dp(8), dp(2), 0)
            }
        }
        rootContainer.addView(quickChipsRow)
        rebuildQuickChips()

        // 3. Google Maps Suggestions Dropdown Sheet
        resultsList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        // Search-in-flight row (Param, 2026-11-21): a spinner +
        // "Searching…" pinned above the result rows while the network
        // search runs, so fast cached/starred rows are visibly interim.
        // It lives OUTSIDE resultsList (which showResults clears) in a
        // shared column inside the card.
        searchLoadingRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(16), dp(10), dp(16), dp(4))
            addView(
                ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                        setMargins(0, 0, dp(10), 0)
                    }
                }
            )
            addView(
                TextView(context).apply {
                    text = "Searching…"
                    setTextColor(HudOverlay.COLOR_TEXT_MUTED)
                    textSize = 13f
                }
            )
        }
        val resultsColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(searchLoadingRow)
            addView(resultsList)
        }
        resultsCard = ScrollView(context).apply {
            background = HudOverlay.makeCardBg(context, HudOverlay.COLOR_SURFACE_DARK, 20, HudOverlay.COLOR_BORDER_DARK, 1)
            elevation = 10f * density
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp(8), 0, 0)
            }
            addView(resultsColumn)
        }
        rootContainer.addView(resultsCard)

        root.addView(rootContainer)
    }

    private fun rebuildQuickChips() {
        if (!::quickChipsRow.isInitialized) return
        quickChipsRow.removeAllViews()
        val isNight = ThemeManager.isNightMode(context)
        val chipBg = if (isNight) HudOverlay.COLOR_SURFACE_DARK else Color.WHITE
        val chipBorder = if (isNight) HudOverlay.COLOR_BORDER_DARK else HudOverlay.COLOR_BORDER_LIGHT
        val chipText = if (isNight) 0xFFE3E3E3.toInt() else HudOverlay.COLOR_TEXT_PRIMARY_LIGHT

        fun makeQuickChip(label: String, kind: PlaceVectorIconView.IconKind, onClick: () -> Unit): LinearLayout {
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = HudOverlay.makeCardBg(context, chipBg, 18, chipBorder, 1)
                elevation = 4f * density
                setPadding(dp(12), dp(6), dp(14), dp(6))
                layoutParams = LinearLayout.LayoutParams(-2, dp(34)).apply {
                    setMargins(0, 0, dp(8), 0)
                }
                addView(PlaceVectorIconView(context, kind, withCircle = false).apply {
                    applyTheme(isNight)
                    layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                        setMargins(0, 0, dp(6), 0)
                    }
                })
                addView(TextView(context).apply {
                    text = label
                    textSize = 13f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(chipText)
                    setSingleLine(true)
                    includeFontPadding = false
                })
                setOnClickListener { onClick() }
            }
        }

        val isHomeConfigured = Bookmarks.isHomeConfigured(context)
        val homeDest = Bookmarks.getHome(context)
        quickChipsRow.addView(makeQuickChip("Home", PlaceVectorIconView.IconKind.HOME) {
            if (isHomeConfigured && homeDest != null) {
                selectDestinationDirect(homeDest)
            } else {
                hideResults()
                hideKeyboard()
                onConfigureHomeRequested()
            }
        })

        val isOfficeConfigured = Bookmarks.isOfficeConfigured(context)
        val officeDest = Bookmarks.getOffice(context)
        quickChipsRow.addView(makeQuickChip("Work", PlaceVectorIconView.IconKind.WORK) {
            if (isOfficeConfigured && officeDest != null) {
                selectDestinationDirect(officeDest)
            } else {
                hideResults()
                hideKeyboard()
                onConfigureOfficeRequested()
            }
        })

        val starred = Bookmarks.getStarred(context)
        if (starred.isNotEmpty()) {
            val firstSaved = starred.first()
            quickChipsRow.addView(makeQuickChip(firstSaved.name.take(14), PlaceVectorIconView.IconKind.STAR_FILLED) {
                selectDestinationDirect(firstSaved)
            })
        }
    }

    fun showInitialSuggestions() {
        // The suggestions home replaces any search state — a spinner
        // left over from an abandoned query must not hang over it.
        searchLoading = false
        if (::searchLoadingRow.isInitialized) searchLoadingRow.visibility = View.GONE
        resultsList.removeAllViews()
        val isNight = ThemeManager.isNightMode(context)
        val textPrimary = if (isNight) 0xFFE3E3E3.toInt() else HudOverlay.COLOR_TEXT_PRIMARY_LIGHT
        val textMuted = if (isNight) HudOverlay.COLOR_TEXT_MUTED else HudOverlay.COLOR_TEXT_MUTED_LIGHT
        val accentBlue = if (isNight) HudOverlay.COLOR_GOOGLE_BLUE_DARK else HudOverlay.COLOR_GOOGLE_BLUE_LIGHT

        val gps = NavStateManager.currentGps

        // The comma's own places (Feature 8): favorites lead, recents
        // follow, deduplicated against each other. The phone Recent
        // section below filters against this combined list too, so a
        // place never appears twice in the dropdown.
        val commaFavorites = NavStateManager.commaFavorites
        val commaRecents = NavStateManager.commaRecents.filter { r ->
            commaFavorites.none { it.isSameAs(r) }
        }
        val commaPlaces = commaFavorites + commaRecents

        // 1. Home Row
        val isHomeConfigured = Bookmarks.isHomeConfigured(context)
        val homeDest = Bookmarks.getHome(context)
        val homeSubtitle = if (isHomeConfigured && homeDest != null) {
            val distStr = shortcutSubtitle(gps, homeDest)
            val addr = homeDest.placeName.ifEmpty { "Saved address" }
            if (distStr != null) "$addr · $distStr" else addr
        } else {
            "Set location"
        }

        resultsList.addView(
            createShortcutRow(
                kind = PlaceVectorIconView.IconKind.HOME,
                title = "Home",
                subtitle = homeSubtitle,
                isConfigured = isHomeConfigured,
                isNight = isNight,
                textPrimary = textPrimary,
                textMuted = textMuted,
                accentColor = accentBlue,
                onRowClick = {
                    if (isHomeConfigured && homeDest != null) {
                        selectDestinationDirect(homeDest)
                    } else {
                        hideResults()
                        hideKeyboard()
                        onConfigureHomeRequested()
                    }
                },
                onEditClick = {
                    hideResults()
                    hideKeyboard()
                    onConfigureHomeRequested()
                }
            )
        )

        // 2. Work Row
        val isOfficeConfigured = Bookmarks.isOfficeConfigured(context)
        val officeDest = Bookmarks.getOffice(context)
        val officeSubtitle = if (isOfficeConfigured && officeDest != null) {
            val distStr = shortcutSubtitle(gps, officeDest)
            val addr = officeDest.placeName.ifEmpty { "Saved address" }
            if (distStr != null) "$addr · $distStr" else addr
        } else {
            "Set location"
        }

        resultsList.addView(
            createShortcutRow(
                kind = PlaceVectorIconView.IconKind.WORK,
                title = "Work",
                subtitle = officeSubtitle,
                isConfigured = isOfficeConfigured,
                isNight = isNight,
                textPrimary = textPrimary,
                textMuted = textMuted,
                accentColor = accentBlue,
                onRowClick = {
                    if (isOfficeConfigured && officeDest != null) {
                        selectDestinationDirect(officeDest)
                    } else {
                        hideResults()
                        hideKeyboard()
                        onConfigureOfficeRequested()
                    }
                },
                onEditClick = {
                    hideResults()
                    hideKeyboard()
                    onConfigureOfficeRequested()
                }
            )
        )

        // 3. Saved Places
        val starred = Bookmarks.getStarred(context)
        if (starred.isNotEmpty()) {
            resultsList.addView(createSectionHeader("Saved", textMuted))
            for (dest in starred) {
                resultsList.addView(
                    createDestinationRow(
                        dest = dest,
                        kind = PlaceVectorIconView.IconKind.STAR_FILLED,
                        isStarred = true,
                        isNight = isNight,
                        textPrimary = textPrimary,
                        textMuted = textMuted,
                        onRowClick = { selectDestinationDirect(dest) },
                        onStarClick = { onStarDestinationClicked(dest) }
                    )
                )
            }
        }

        // 3b. From your comma — the device's own favorites, then recents.
        // Rows carry real coordinates, so a tap flows through the normal
        // device-confirmed send like any other destination.
        if (commaPlaces.isNotEmpty()) {
            resultsList.addView(createSectionHeader("From your comma", textMuted))
            for (dest in commaFavorites) {
                resultsList.addView(
                    createDestinationRow(
                        dest = dest,
                        kind = PlaceVectorIconView.IconKind.STAR_FILLED,
                        isStarred = false,
                        isNight = isNight,
                        textPrimary = textPrimary,
                        textMuted = textMuted,
                        onRowClick = { selectDestinationDirect(dest) },
                        onStarClick = { onStarDestinationClicked(dest) }
                    )
                )
            }
            for (dest in commaRecents) {
                resultsList.addView(
                    createDestinationRow(
                        dest = dest,
                        kind = PlaceVectorIconView.IconKind.PIN,
                        isStarred = false,
                        isNight = isNight,
                        textPrimary = textPrimary,
                        textMuted = textMuted,
                        onRowClick = { selectDestinationDirect(dest) },
                        onStarClick = { onStarDestinationClicked(dest) }
                    )
                )
            }
        }

        // 4. Recent Places (deduplicated against Home, Work, Saved, and comma places)
        val recent = DestinationHistory.recent(context, 8).filter { r ->
            val matchesHome = isHomeConfigured && homeDest != null && (r.isSameAs(homeDest) || r.name.equals("Home", ignoreCase = true))
            val matchesWork = isOfficeConfigured && officeDest != null && (r.isSameAs(officeDest) || r.name.equals("Office", ignoreCase = true) || r.name.equals("Work", ignoreCase = true))
            val matchesStarred = starred.any { it.isSameAs(r) }
            val matchesComma = commaPlaces.any { it.isSameAs(r) }
            !matchesHome && !matchesWork && !matchesStarred && !matchesComma
        }.take(4)
        if (recent.isNotEmpty()) {
            resultsList.addView(createSectionHeader("Recent", textMuted))
            for (dest in recent) {
                val isDestStarred = Bookmarks.isStarred(context, dest)
                resultsList.addView(
                    createDestinationRow(
                        dest = dest,
                        kind = PlaceVectorIconView.IconKind.RECENT,
                        isStarred = isDestStarred,
                        isNight = isNight,
                        textPrimary = textPrimary,
                        textMuted = textMuted,
                        onRowClick = { selectDestinationDirect(dest) },
                        onStarClick = { onStarDestinationClicked(dest) }
                    )
                )
            }
        }

        resultsCard.visibility = View.VISIBLE
        quickChipsRow.visibility = View.GONE
    }

    private fun createShortcutRow(
        kind: PlaceVectorIconView.IconKind,
        title: String,
        subtitle: String,
        isConfigured: Boolean,
        isNight: Boolean,
        textPrimary: Int,
        textMuted: Int,
        accentColor: Int,
        onRowClick: () -> Unit,
        onEditClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isClickable = true
            isFocusable = true

            val iconView = PlaceVectorIconView(context, kind, withCircle = true).apply {
                applyTheme(isNight)
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                    setMargins(0, 0, dp(12), 0)
                }
            }
            addView(iconView)

            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)

                val titleTv = TextView(context).apply {
                    text = title
                    textSize = 15f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(textPrimary)
                    setSingleLine(true)
                }
                addView(titleTv)

                val subTv = TextView(context).apply {
                    text = subtitle
                    textSize = 13f
                    setTextColor(if (isConfigured) textMuted else accentColor)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(1), 0, 0)
                }
                addView(subTv)
            }
            addView(textCol)

            if (isConfigured) {
                val editTv = TextView(context).apply {
                    text = "Edit"
                    textSize = 12f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(textMuted)
                    setPadding(dp(10), dp(6), dp(6), dp(6))
                    setOnClickListener { onEditClick() }
                }
                addView(editTv)
            }

            setOnClickListener { onRowClick() }
        }
    }

    private fun createSectionHeader(title: String, color: Int): TextView {
        return TextView(context).apply {
            text = title
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(color)
            setPadding(dp(16), dp(10), dp(16), dp(4))
        }
    }

    private fun createDestinationRow(
        dest: NavDestination,
        kind: PlaceVectorIconView.IconKind,
        isStarred: Boolean,
        isNight: Boolean,
        textPrimary: Int,
        textMuted: Int,
        onRowClick: () -> Unit,
        onStarClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(10))
            isClickable = true
            isFocusable = true

            val iconView = PlaceVectorIconView(context, kind, withCircle = true).apply {
                applyTheme(isNight)
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                    setMargins(0, 0, dp(12), 0)
                }
            }
            addView(iconView)

            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)

                val titleTv = TextView(context).apply {
                    text = dest.name
                    textSize = 15f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(textPrimary)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                addView(titleTv)

                val distText = dest.distanceM?.let { distM ->
                    if (distM < 300) "${(distM * 3.28084).roundToInt()} ft"
                    else "%.1f mi".format(distM * Units.METERS_TO_MILES)
                }
                val cleanSub = dest.placeName.takeIf { it.isNotEmpty() && it != dest.name }
                val combinedSub = when {
                    distText != null && cleanSub != null -> "$distText · $cleanSub"
                    distText != null -> distText
                    cleanSub != null -> cleanSub
                    else -> ""
                }

                if (combinedSub.isNotEmpty()) {
                    val subTv = TextView(context).apply {
                        text = combinedSub
                        textSize = 13f
                        setTextColor(textMuted)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, dp(1), 0, 0)
                    }
                    addView(subTv)
                }
            }
            addView(textCol)

            val starIcon = PlaceVectorIconView(
                context,
                if (isStarred) PlaceVectorIconView.IconKind.STAR_FILLED else PlaceVectorIconView.IconKind.STAR_OUTLINE,
                withCircle = false
            ).apply {
                applyTheme(isNight)
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                    setMargins(dp(4), 0, dp(2), 0)
                }
                setPadding(dp(6), dp(6), dp(6), dp(6))
                isClickable = true
                isFocusable = true
                setOnClickListener { onStarClick() }
            }
            addView(starIcon)

            setOnClickListener { onRowClick() }
        }
    }

    private fun selectDestinationDirect(dest: NavDestination) {
        NavStateManager.markDestinationSelected(dest)
        val feederTarget = NavStateManager.commaIp
        if (NavStateManager.usePhoneLocationToComma && !feederTarget.isNullOrBlank()) {
            PhoneGpsFeeder.start(feederTarget)
        }
        cancelPendingSearch()
        val exact = dest.name.ifEmpty { dest.placeName }
        isProgrammaticUpdate = true
        isUserTyping = false
        searchInput.setText(exact)
        clearBtn.visibility = View.VISIBLE
        searchInput.clearFocus()
        hideKeyboard()
        hideResults()
        resultsList.removeAllViews()
        isProgrammaticUpdate = false
        onDestinationSelected(dest)
    }

    /**
     * Shows/hides the search-in-flight spinner (Param, 2026-11-21).
     * While loading, the results card stays open even with no rows
     * yet, so the user sees that fresh results are still coming.
     */
    fun setSearchLoading(loading: Boolean) {
        searchLoading = loading
        if (!::searchLoadingRow.isInitialized) return
        searchLoadingRow.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading && isUserTyping) {
            resultsCard.visibility = View.VISIBLE
            quickChipsRow.visibility = View.GONE
        }
    }

    fun showResults(destinations: List<NavDestination>) {
        lastRenderedResults = destinations
        if (!isUserTyping) {
            hideResults()
            resultsList.removeAllViews()
            return
        }
        resultsList.removeAllViews()
        if (destinations.isEmpty()) {
            // A search still in flight keeps the card open with just
            // the spinner — an empty card here would read as "no
            // results" while the real answer is still coming.
            if (searchLoading) {
                resultsCard.visibility = View.VISIBLE
                quickChipsRow.visibility = View.GONE
            } else {
                hideResults()
            }
            return
        }

        val isNight = ThemeManager.isNightMode(context)
        val textPrimary = if (isNight) 0xFFE3E3E3.toInt() else HudOverlay.COLOR_TEXT_PRIMARY_LIGHT
        val textMuted = if (isNight) HudOverlay.COLOR_TEXT_MUTED else HudOverlay.COLOR_TEXT_MUTED_LIGHT

        for (dest in destinations) {
            val isStarred = Bookmarks.isStarred(context, dest)
            val row = createDestinationRow(
                dest = dest,
                kind = PlaceVectorIconView.IconKind.PIN,
                isStarred = isStarred,
                isNight = isNight,
                textPrimary = textPrimary,
                textMuted = textMuted,
                onRowClick = { selectDestinationDirect(dest) },
                onStarClick = { onStarDestinationClicked(dest) }
            )
            resultsList.addView(row)
        }
        resultsCard.visibility = View.VISIBLE
        quickChipsRow.visibility = View.GONE
    }

    /** Feature 10: the offline-queue chip; null hides it. Tapping it (or its ✕) cancels via [onCancel]. */
    fun setQueuedDestination(dest: NavDestination?, outOfSync: Boolean, onCancel: () -> Unit) {
        if (!::queuedRow.isInitialized) return
        onQueuedCancel = onCancel
        if (dest == null) {
            queuedDestShown = null
            queuedRow.visibility = View.GONE
            return
        }
        val label = DestinationQueue.label(dest, outOfSync, NavStateManager.commaNetworks)
        if (queuedDestShown?.isSameAs(dest) == true && queuedShownSync == outOfSync &&
            queuedLabel.text.toString() == label && queuedRow.visibility == View.VISIBLE
        ) return
        queuedDestShown = dest
        queuedShownSync = outOfSync
        queuedLabel.text = label
        queuedRow.visibility = View.VISIBLE
    }

    fun refreshSuggestions() {
        rebuildQuickChips()
        if (searchInput.text.isNullOrEmpty()) {
            if (resultsCard.visibility == View.VISIBLE || searchInput.hasFocus()) {
                showInitialSuggestions()
            }
        } else if (lastRenderedResults.isNotEmpty() && resultsCard.visibility == View.VISIBLE) {
            showResults(lastRenderedResults)
        }
    }

    fun isShowingSuggestions(): Boolean {
        return ::resultsCard.isInitialized && resultsCard.visibility == View.VISIBLE
    }

    fun isTouchInside(rawX: Float, rawY: Float): Boolean {
        if (!isShowingSuggestions()) return false
        val outRect = Rect()
        val loc = IntArray(2)

        if (::searchBarLayout.isInitialized && searchBarLayout.visibility == View.VISIBLE) {
            searchBarLayout.getLocationOnScreen(loc)
            outRect.set(loc[0], loc[1], loc[0] + searchBarLayout.width, loc[1] + searchBarLayout.height)
            if (outRect.contains(rawX.toInt(), rawY.toInt())) return true
        }

        if (::resultsCard.isInitialized && resultsCard.visibility == View.VISIBLE) {
            resultsCard.getLocationOnScreen(loc)
            outRect.set(loc[0], loc[1], loc[0] + resultsCard.width, loc[1] + resultsCard.height)
            if (outRect.contains(rawX.toInt(), rawY.toInt())) return true
        }

        return false
    }

    fun collapse(activeDest: NavDestination? = null) {
        cancelPendingSearch()
        hideResults()
        hideKeyboard()
        if (::searchInput.isInitialized) {
            isProgrammaticUpdate = true
            if (activeDest != null) {
                val display = activeDest.name.ifEmpty { activeDest.placeName }
                searchInput.setText(display)
                clearBtn.visibility = View.VISIBLE
            } else {
                searchInput.setText("")
                clearBtn.visibility = View.GONE
            }
            isProgrammaticUpdate = false
            searchInput.clearFocus()
        }
    }

    fun hideResults() {
        // Dismissing the results ends any search-in-flight display too.
        searchLoading = false
        if (::searchLoadingRow.isInitialized) searchLoadingRow.visibility = View.GONE
        resultsCard.visibility = View.GONE
        if (::quickChipsRow.isInitialized) {
            quickChipsRow.visibility = View.VISIBLE
        }
    }

    fun setDestination(dest: NavDestination?) {
        if (dest != null) {
            cancelPendingSearch()
            val display = dest.name.ifEmpty { dest.placeName }
            isProgrammaticUpdate = true
            isUserTyping = false
            searchInput.setText(display)
            clearBtn.visibility = View.VISIBLE
            searchInput.clearFocus()
            hideResults()
            hideKeyboard()
            resultsList.removeAllViews()
            isProgrammaticUpdate = false
        }
    }

    fun setVisible(vis: Boolean) {
        rootContainer.visibility = if (vis) View.VISIBLE else View.GONE
    }

    /**
     * Runs a query that arrived from outside the bar (Feature 11 share):
     * shows the bar, puts the text in the field as if typed, and runs the
     * normal search path.
     */
    fun submitExternalQuery(query: String) {
        val q = query.trim()
        if (q.isEmpty() || !::searchInput.isInitialized) return
        setVisible(true)
        searchInput.requestFocus()
        isProgrammaticUpdate = false
        isUserTyping = true
        searchInput.setText(q)
        searchInput.setSelection(q.length)
        clearBtn.visibility = View.VISIBLE
        if (q.length >= 2) scheduleSearch(q) else showInitialSuggestions()
    }

    fun clearSearch() {
        cancelPendingSearch()
        isUserTyping = false
        searchInput.setText("")
        hideResults()
        hideKeyboard()
    }

    /**
     * True while the user has a search in progress — text in the
     * field that they typed and haven't submitted or cleared. A
     * route ending checks this before resetting the search state,
     * so an in-progress search for the next destination survives
     * the previous trip's end (Param, 2026-11-20).
     */
    fun isSearchInProgress(): Boolean =
        ::searchInput.isInitialized && isUserTyping &&
            !searchInput.text.isNullOrBlank()

    fun hideKeyboard() {
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
        } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
    }

    fun applyTheme(night: Boolean) {
        val barBg = if (night) HudOverlay.COLOR_SURFACE_DARK else Color.WHITE
        val border = if (night) HudOverlay.COLOR_BORDER_DARK else HudOverlay.COLOR_BORDER_LIGHT
        val textCol = if (night) 0xFFE3E3E3.toInt() else HudOverlay.COLOR_TEXT_PRIMARY_LIGHT
        val hintCol = if (night) HudOverlay.COLOR_TEXT_MUTED else HudOverlay.COLOR_TEXT_MUTED_LIGHT
        searchBarLayout.background = HudOverlay.makeCardBg(context, barBg, 25, border, 1)
        searchIconView.applyTheme(night)
        if (::micBtn.isInitialized) micBtn.applyTheme(night)
        searchInput.setTextColor(textCol)
        searchInput.setHintTextColor(hintCol)
        resultsCard.background = HudOverlay.makeCardBg(context, barBg, 20, border, 1)
        refreshSuggestions()
    }

    private fun shortcutSubtitle(gps: GpsPosition?, dest: NavDestination): String? {
        if (gps == null || !dest.hasCoordinates) return null
        val miles = GuidanceMath.haversineM(gps.latitude, gps.longitude, dest.latitude, dest.longitude) / Units.METERS_PER_MILE
        val etaMin = (miles / 25.0 * 60.0).roundToInt().coerceAtLeast(1)
        return "$etaMin min · ${"%.1f".format(miles)} mi"
    }


    private companion object {
        const val SEARCH_DEBOUNCE_MS = 350L
    }
}

/**
 * Crisp Google Material 3 vector icons drawn on Canvas for search rows & chips.
 */
class PlaceVectorIconView(
    context: Context,
    private val kind: IconKind,
    private val withCircle: Boolean
) : View(context) {

    enum class IconKind {
        SEARCH, HOME, WORK, RECENT, PIN, STAR_FILLED, STAR_OUTLINE, MIC
    }

    private var isNight: Boolean = true
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF28292A.toInt()
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFF9AA0A6.toInt()
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF9AA0A6.toInt()
    }
    private val path = Path()

    fun applyTheme(night: Boolean) {
        isNight = night
        circlePaint.color = if (night) 0xFF28292A.toInt() else 0xFFF1F3F4.toInt()
        val baseCol = when (kind) {
            IconKind.HOME, IconKind.WORK -> if (night) 0xFF8AB4F8.toInt() else 0xFF1A73E8.toInt()
            IconKind.STAR_FILLED -> 0xFFFBBC04.toInt()
            else -> if (night) 0xFF9AA0A6.toInt() else 0xFF5F6368.toInt()
        }
        strokePaint.color = baseCol
        fillPaint.color = baseCol
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy)
        if (withCircle) {
            canvas.drawCircle(cx, cy, r, circlePaint)
        }
        val s = if (withCircle) r * 0.45f else r * 0.72f

        when (kind) {
            IconKind.SEARCH -> {
                val cr = s * 0.68f
                canvas.drawCircle(cx - s * 0.15f, cy - s * 0.15f, cr, strokePaint)
                canvas.drawLine(
                    cx + cr * 0.5f,
                    cy + cr * 0.5f,
                    cx + s * 0.75f,
                    cy + s * 0.75f,
                    strokePaint
                )
            }
            IconKind.HOME -> {
                path.reset()
                path.moveTo(cx - s, cy - s * 0.1f)
                path.lineTo(cx, cy - s * 0.9f)
                path.lineTo(cx + s, cy - s * 0.1f)
                canvas.drawPath(path, strokePaint)
                val bodyRect = RectF(cx - s * 0.68f, cy - s * 0.15f, cx + s * 0.68f, cy + s * 0.85f)
                canvas.drawRect(bodyRect, strokePaint)
            }
            IconKind.WORK -> {
                val boxRect = RectF(cx - s * 0.85f, cy - s * 0.45f, cx + s * 0.85f, cy + s * 0.80f)
                canvas.drawRoundRect(boxRect, s * 0.2f, s * 0.2f, strokePaint)
                val handleRect = RectF(cx - s * 0.35f, cy - s * 0.85f, cx + s * 0.35f, cy - s * 0.45f)
                canvas.drawRect(handleRect, strokePaint)
            }
            IconKind.RECENT -> {
                canvas.drawCircle(cx, cy, s * 0.85f, strokePaint)
                canvas.drawLine(cx, cy, cx, cy - s * 0.48f, strokePaint)
                canvas.drawLine(cx, cy, cx + s * 0.38f, cy + s * 0.22f, strokePaint)
            }
            IconKind.PIN -> {
                val headR = s * 0.62f
                val headY = cy - s * 0.22f
                canvas.drawCircle(cx, headY, headR, strokePaint)
                path.reset()
                path.moveTo(cx - headR * 0.75f, headY + headR * 0.5f)
                path.lineTo(cx, cy + s * 0.95f)
                path.lineTo(cx + headR * 0.75f, headY + headR * 0.5f)
                canvas.drawPath(path, strokePaint)
            }
            IconKind.MIC -> {
                // Capsule body, cradle arc, stem and base — a microphone.
                val bodyRect = RectF(cx - s * 0.34f, cy - s * 0.78f, cx + s * 0.34f, cy + s * 0.12f)
                canvas.drawRoundRect(bodyRect, s * 0.34f, s * 0.34f, strokePaint)
                val arcRect = RectF(cx - s * 0.58f, cy - s * 0.42f, cx + s * 0.58f, cy + s * 0.42f)
                canvas.drawArc(arcRect, 0f, 180f, false, strokePaint)
                canvas.drawLine(cx, cy + s * 0.42f, cx, cy + s * 0.78f, strokePaint)
                canvas.drawLine(cx - s * 0.38f, cy + s * 0.78f, cx + s * 0.38f, cy + s * 0.78f, strokePaint)
            }
            IconKind.STAR_FILLED, IconKind.STAR_OUTLINE -> {
                path.reset()
                val outerR = s * 0.9f
                val innerR = s * 0.38f
                for (i in 0 until 10) {
                    val rad = if (i % 2 == 0) outerR else innerR
                    val ang = Math.toRadians(-90.0 + i * 36.0)
                    val px = cx + (rad * cos(ang)).toFloat()
                    val py = cy + (rad * sin(ang)).toFloat()
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                if (kind == IconKind.STAR_FILLED) {
                    canvas.drawPath(path, fillPaint)
                } else {
                    canvas.drawPath(path, strokePaint)
                }
            }
        }
    }
}
