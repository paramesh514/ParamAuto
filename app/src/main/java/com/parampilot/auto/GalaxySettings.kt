package com.parampilot.auto

import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The comma's Galaxy settings pages, as a pure model (Param,
 * 2026-11-20: bring the comma settings into the app).
 *
 * Contract pinned from Dom source (galaxy/settings.py `_projection`,
 * galaxy/server.py preview/confirm handlers, web/js/settings.js +
 * galaxy-setting-row.js — the web UI's own behavior):
 *
 * GET /api/settings/pages/<page> returns
 *   {page, title, subtitle, parked, vehicleIdentified, view,
 *    rows: [{label, value (always a string), choices: [str],
 *            step, minimum, maximum, unit, available,
 *            reason (the row's description text), page (subpage id
 *            or ""), action, confirm, repairValue, defaultValue,
 *            resetAvailable, displayScale, revision}]}
 * A row is edited in two phases inside ONE Galaxy session (a preview
 * intent is bound to the session that created it):
 *   POST /api/settings/preview {view, row, value}   — value edits
 *   POST /api/settings/preview {view, row, direction} — actions
 *     -> {intent, question, proposed}
 *   POST /api/settings/confirm {intent, confirmed: true, page}
 *     -> {saved: true, page: <the fresh page state>}
 *   POST /api/settings/reset-default {view, row} -> {intent, ...}
 * Views and intents live 60 seconds on the comma; a stale one is a
 * 409 ("Saved settings changed; refresh the page").
 *
 * Value edits (switch / select / slider) are previewed and confirmed
 * immediately, exactly like the web UI; action rows show the server's
 * own question first. The control each row gets mirrors the web UI's
 * settingControl() decision, in the same order.
 */
object GalaxySettings {

    data class Row(
        val label: String,
        val value: String,
        val choices: List<String>,
        val step: Double,
        val minimum: Double,
        val maximum: Double,
        val unit: String,
        val available: Boolean,
        val reason: String,
        val page: String,
        val action: Boolean,
        val confirm: Boolean,
        val repairValue: String?,
        val defaultValue: String?,
        val resetAvailable: Boolean,
        val displayScale: String?,
        val revision: String?
    )

    data class Page(
        val page: String,
        val title: String,
        val subtitle: String,
        val parked: Boolean,
        val vehicleIdentified: Boolean,
        val view: String,
        val rows: List<Row>
    )

    enum class Control { GROUP, ACTION, SWITCH, SLIDER, SELECT, READOUT }

    data class PageEntry(val id: String, val title: String, val blurb: String)

    /**
     * The Galaxy settings pages in the menu, grouped for the list.
     * The first two sections are the pages Param asked for
     * (2026-11-20); Addendum 147 (2026-11-26, Galaxy-parity mandate)
     * added every remaining top-level Galaxy page so nothing the
     * portal offers is missing here, and Addendum 150 (2026-11-27)
     * added the personality family (profiles + personalities) the
     * same way. Page ids are the server's own PAGES registry
     * (galaxy/settings.py, identical on the testing and stable
     * branches) — [GALAXY_PAGES] pins the full set and a test keeps
     * the two from drifting apart. Pages NOT in the menu: slc (the
     * Speed limit assist sheet), hub (the portal home, not a
     * settings page) and starport_options (StarPort management, its
     * own parity item). The four profile pages (traffic, aggressive,
     * standard, relaxed) open through drill-in rows on the
     * personalities page, and their twelve
     * acceleration/braking/following sub-pages render inline on the
     * parent profile page, exactly as the portal shows them.
     * Other sub-pages (torque/developer, profiles/developer, tuning,
     * per-sound editors) open through drill-in rows on their parent
     * pages.
     */
    val SECTIONS: List<Pair<String, List<PageEntry>>> = listOf(
        "DRIVING" to listOf(
            PageEntry("lane", "Lane", "Centering, strength & lane behavior"),
            PageEntry("lane_change", "Lane Change", "Lane change assist & timing"),
            PageEntry("curve", "Curve Speed", "Slowing for curves"),
            PageEntry("torque", "Torque", "Steering torque tuning"),
            PageEntry("torque/bluepilot", "Torque · BluePilot", "BluePilot torque style"),
            PageEntry("aol", "Always-On Lateral", "Lateral control while disengaged"),
            PageEntry("conditional", "Conditional", "Conditional driving modes & triggers"),
            PageEntry("conditional/cem", "Conditional · CEM", "Conditional engagement modes")
        ),
        "PERSONALITY" to listOf(
            PageEntry("profiles", "Long Planner", "Planner, force stops, global presets & saved profiles"),
            PageEntry("personalities", "Driving Personalities", "Tune Aggressive, Standard, Relaxed & Traffic")
        ),
        "CONTROLS & COMMA" to listOf(
            PageEntry("wheel", "Wheel Controls", "Steering-wheel button actions"),
            PageEntry("appearance", "Appearance", "On-road look & widgets"),
            PageEntry("display", "Display", "Brightness & display behavior"),
            PageEntry("power", "Power", "Power & shutdown behavior"),
            PageEntry("data", "Data", "Data & uploads"),
            PageEntry("localization", "Localization", "Language, units & formats"),
            PageEntry("vehicle", "Vehicle", "Vehicle selection & configuration")
        ),
        "DEVICE" to listOf(
            PageEntry("sounds", "Sounds", "Alert sounds & sound packs"),
            PageEntry("sentry", "Sentry", "Sentry mode & event recording"),
            PageEntry("pip", "Picture-in-Picture", "Projection window & layout rows"),
            PageEntry("vasm", "VASM", "On-screen annotation rows")
        ),
        "ADVANCED" to listOf(
            PageEntry("developer", "Developer", "Developer & debugging options")
        )
    )

    /**
     * Every page id in the Galaxy server's PAGES registry
     * (openpilot/starpilot/galaxy/settings.py), verified identical
     * on the testing and stable branches on 2026-11-26. The
     * completeness test classifies each id: in [SECTIONS], served by
     * a dedicated surface, a drill-in sub-page, or not a settings
     * page — so a future upstream page addition fails the suite
     * until it is classified here.
     */
    val GALAXY_PAGES: Set<String> = setOf(
        "hub", "developer", "localization", "starport_options", "vehicle",
        "slc", "lane", "lane_change", "profiles", "profiles/developer",
        "personalities", "traffic", "aggressive", "standard", "relaxed",
        "curve", "torque", "torque/developer", "tuning", "torque/bluepilot",
        "aol", "wheel", "appearance", "display", "data", "power", "sounds",
        "pip", "sentry", "vasm", "conditional", "conditional/cem"
    ) + setOf("traffic", "aggressive", "standard", "relaxed")
        .flatMap { name -> listOf("acceleration", "braking", "following").map { "$name/$it" } }
        .toSet()

    fun parsePage(json: JSONObject?): Page? {
        if (json == null) return null
        val view = json.optString("view", "")
        if (view.isEmpty()) return null
        val rowsJson = json.optJSONArray("rows") ?: return null
        val rows = ArrayList<Row>(rowsJson.length())
        for (i in 0 until rowsJson.length()) {
            val r = rowsJson.optJSONObject(i) ?: continue
            val choices = ArrayList<String>()
            val choicesJson = r.optJSONArray("choices")
            if (choicesJson != null) {
                for (j in 0 until choicesJson.length()) {
                    choices.add(choicesJson.optString(j, ""))
                }
            }
            rows.add(
                Row(
                    label = r.optString("label", ""),
                    value = valueString(r.opt("value")),
                    choices = choices,
                    step = r.optDouble("step", 0.0),
                    minimum = r.optDouble("minimum", 0.0),
                    maximum = r.optDouble("maximum", 0.0),
                    unit = r.optString("unit", ""),
                    available = r.optBoolean("available", false),
                    reason = r.optString("reason", ""),
                    page = r.optString("page", ""),
                    action = r.optBoolean("action", false),
                    confirm = r.optBoolean("confirm", false),
                    repairValue = r.optString("repairValue", "")
                        .ifEmpty { null },
                    defaultValue = if (r.has("defaultValue") &&
                        !r.isNull("defaultValue")
                    ) {
                        valueString(r.opt("defaultValue"))
                    } else {
                        null
                    },
                    resetAvailable = r.optBoolean("resetAvailable", false),
                    displayScale = r.optString("displayScale", "")
                        .ifEmpty { null },
                    revision = r.optString("revision", "").ifEmpty { null }
                )
            )
        }
        return Page(
            page = json.optString("page", ""),
            title = json.optString("title", ""),
            subtitle = json.optString("subtitle", ""),
            parked = json.optBoolean("parked", false),
            vehicleIdentified = json.optBoolean("vehicleIdentified", false),
            view = view,
            rows = rows
        )
    }

    private fun valueString(raw: Any?): String = when (raw) {
        null, JSONObject.NULL -> ""
        is String -> raw
        is Boolean -> if (raw) "On" else "Off"
        is Double -> formatNumber(raw)
        is Number -> formatNumber(raw.toDouble())
        else -> raw.toString()
    }

    /** JS-style number formatting: no trailing ".0", no float noise. */
    fun formatNumber(value: Double): String {
        if (!value.isFinite()) return ""
        if (value == floor(value) && Math.abs(value) < 1e15) {
            return value.toLong().toString()
        }
        return String.format(java.util.Locale.US, "%.8f", value)
            .trimEnd('0').trimEnd('.')
    }

    /** The web UI's settingControl(), in its own decision order. */
    fun control(row: Row): Control {
        if (row.page.isNotEmpty()) return Control.GROUP
        if (row.confirm || row.repairValue != null) return Control.ACTION
        if (row.choices.size == 2 && row.choices.contains("Off") &&
            row.choices.contains("On") && row.choices.contains(row.value)
        ) {
            return Control.SWITCH
        }
        if (numericBounds(row) != null &&
            ((row.value.isNotEmpty() && row.value.toDoubleOrNull() != null) ||
                (row.value == "Auto" && row.choices.contains("Auto")))
        ) {
            return Control.SLIDER
        }
        if (row.choices.isNotEmpty() && row.choices.contains(row.value)) {
            return Control.SELECT
        }
        return Control.READOUT
    }

    /** (min, max, step) when the row is a bounded numeric, else null. */
    fun numericBounds(row: Row): Triple<Double, Double, Double>? {
        val min = row.minimum
        val max = row.maximum
        val step = row.step
        return if (min.isFinite() && max.isFinite() && step.isFinite() &&
            max > min && step > 0
        ) {
            Triple(min, max, step)
        } else {
            null
        }
    }

    /** The web UI's snapNumeric(): clamp to the step grid. */
    fun snapNumeric(value: Double, min: Double, max: Double, step: Double): Double {
        var lastStep = floor((max - min) / step + 1e-9).toInt()
        if (round8(min + lastStep * step) > max) lastStep--
        val index = ((value - min) / step).roundToInt()
            .coerceIn(0, lastStep)
        return round8(min + index * step)
    }

    private fun round8(value: Double): Double =
        (value * 1e8).roundToLong() / 1e8

    // ---- Lane-centering strength scale (displayScale "lane-strength") ----

    /** Raw value -> the 1–10 display scale (5 is normal). */
    fun laneStrengthDisplay(value: Double, min: Double, max: Double): Double {
        if (!value.isFinite() || min >= 100 || max <= 100) return value
        val display = if (value <= 100) {
            1 + (value - min) * 4 / (100 - min)
        } else {
            5 + (value - 100) * 5 / (max - 100)
        }
        return (display * 100).roundToLong() / 100.0
    }

    /** The 1–10 display scale -> the raw value to save, or null. */
    fun laneStrengthSaved(display: Double, min: Double, max: Double): Int? {
        if (!display.isFinite() || display < 1 || display > 10) return null
        return if (display <= 5) {
            (min + (display - 1) * (100 - min) / 4).roundToInt()
        } else {
            (100 + (display - 5) * (max - 100) / 5).roundToInt()
        }
    }

    /**
     * The row as the web UI presents it: the lane-strength row is
     * shown on its 1–10 scale with the explanatory reason.
     */
    fun presented(row: Row): Row {
        if (row.displayScale != "lane-strength") return row
        val rawValue = row.value.toDoubleOrNull() ?: return row
        return row.copy(
            value = formatNumber(
                laneStrengthDisplay(rawValue, row.minimum, row.maximum)
            ),
            defaultValue = row.defaultValue?.toDoubleOrNull()?.let {
                formatNumber(laneStrengthDisplay(it, row.minimum, row.maximum))
            },
            minimum = 1.0,
            maximum = 10.0,
            step = if (row.step > 0) 1.0 else 0.0,
            unit = "",
            reason = if (row.available) {
                "1–10; 5 is normal. Lower values reduce centering " +
                    "and higher values strengthen it"
            } else {
                row.reason
            }
        )
    }

    /** True when the row still sits at its default (web showDefault). */
    fun isAtDefault(row: Row): Boolean {
        val initial = row.defaultValue ?: return false
        val current = row.value.toDoubleOrNull()
        val target = initial.toDoubleOrNull()
        return if (current != null && target != null) {
            current == target
        } else {
            row.value == initial
        }
    }

    /** "45" + unit "mph" -> "45 mph"; "Auto" stays "Auto". */
    fun valueWithUnit(row: Row): String =
        if (row.unit.isEmpty()) row.value else "${row.value} ${row.unit}"

    /** The action button's label, as the web UI words it. */
    fun actionLabel(row: Row): String {
        val repair = row.repairValue
        return when {
            repair == "Reset" ||
                row.label.contains("reset", ignoreCase = true) ||
                row.label.contains("default", ignoreCase = true) -> "Reset"
            repair != null -> "Set $repair"
            else -> row.label
        }
    }

    /**
     * Addendum 139: the slc page's rows OTHER than the band offsets
     * and their status lines — the settings the "Speed limit assist"
     * sheet adds under its band card (the controller toggle, sign
     * display, confirmation policy, adopt-early and pedal-override
     * choices, the source pickers, the reset action). The band
     * offsets classify as SLIDERs and the policy / zero-offset
     * lines as READOUTs; everything else the driver can change on
     * the page is a SWITCH, SELECT or ACTION. Returns (page row
     * index, row) pairs — the settings protocol addresses rows by
     * index.
     */
    fun slcAssistExtras(page: Page): List<Pair<Int, Row>> =
        page.rows.mapIndexedNotNull { index, row ->
            when (control(row)) {
                Control.SWITCH, Control.SELECT, Control.ACTION ->
                    index to row
                else -> null
            }
        }

    // ---- request bodies (exact key sets; the server rejects extras) ----

    fun previewValueBody(view: String, row: Int, value: Any): JSONObject {
        val body = JSONObject()
            .put("view", view)
            .put("row", row)
        when (value) {
            is String -> body.put("value", value)
            is Double -> if (value == floor(value) && value.isFinite()) {
                body.put("value", value.toLong())
            } else {
                body.put("value", value)
            }
            is Number -> body.put("value", value)
            else -> body.put("value", value.toString())
        }
        return body
    }

    fun previewDirectionBody(view: String, row: Int, direction: Int): JSONObject =
        JSONObject()
            .put("view", view)
            .put("row", row)
            .put("direction", direction)

    fun confirmBody(intent: String, page: String): JSONObject =
        JSONObject()
            .put("intent", intent)
            .put("confirmed", true)
            .put("page", page)

    fun resetDefaultBody(view: String, row: Int): JSONObject =
        JSONObject()
            .put("view", view)
            .put("row", row)
}
