package com.parampilot.auto

import android.util.Log
import org.json.JSONObject

/**
 * Repository for remote comma settings mutations and personality profiles (Section 5.3 & 5.4).
 * Enforces the two-phase handshake (preview -> confirm) and vehicle Parked state safety gate.
 */
class SettingsRepository(private val client: SgGalaxyClient) {

    /**
     * Receipts from the most recent [commitPageSettings] run (Feature 1):
     * one line per written key, produced by re-reading the page after the
     * confirm and formatting the row's NEW live value via [SettingsReceipts].
     * Empty when the last commit wrote nothing or has not run yet.
     */
    @Volatile
    var lastReceipts: List<String> = emptyList()
        private set

    companion object {
        private const val TAG = "SettingsRepository"
        private val OFFSET_KEY_REGEX = Regex("(?i)offset([1-7])")

        @Volatile
        private var cachedFavoriteSlots: JSONObject? = null
    }

    /**
     * Verifies that the vehicle is in Parked state (state == "parked") before dispatching
     * safety-sensitive mutations. Checks explicit parameter, then NavStateManager, and falls
     * back to remote GET /api/device/state.
     */
    fun isVehicleParked(explicitState: String? = null): Boolean {
        val state = explicitState?.lowercase()?.trim()
            ?: NavStateManager.deviceState?.lowercase()?.trim()
            ?: fetchRemoteDeviceState()
        return state == "parked"
    }

    fun fetchRemoteDeviceState(): String? {
        return try {
            val json = client.fetchDeviceState()
            val s = json?.optString("state", "")?.ifEmpty { null }
            if (s != null) {
                NavStateManager.setDeviceState(s)
            }
            s?.lowercase()?.trim()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query remote device state: ${e.message}")
            null
        }
    }

    /**
     * Core two-phase commit engine, speaking the device's row/intent protocol:
     * for each key, in order:
     * 1. GET /api/settings/pages/$page -> fresh rows + view token (a confirmed save
     *    mutates the page, so the view is never reused across keys).
     * 2. Resolve the target row by index (rows carry no key field; matched by label).
     * 3. POST /api/settings/preview {view, row, value} -> returns an intent token.
     * 4. POST /api/settings/confirm {intent, confirmed: true, page} -> {saved: true}
     *    plus the refreshed page, which is used to verify SLC writes landed.
     * Any failure aborts the remaining keys; keys already confirmed stay saved.
     */
    fun commitPageSettings(
        page: String,
        values: JSONObject,
        currentDeviceState: String? = null
    ): Result<Boolean> {
        val state = currentDeviceState?.lowercase()?.trim()
            ?: fetchRemoteDeviceState()
            ?: NavStateManager.deviceState?.lowercase()?.trim()
        if (state != "parked") {
            return Result.failure(IllegalStateException("Vehicle must be parked to commit settings."))
        }
        lastReceipts = emptyList()
        return try {
            val receipts = mutableListOf<String>()
            val keys = values.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val failure = commitSingleSetting(page, key, values.opt(key), receipts)
                if (failure != null) {
                    lastReceipts = receipts
                    return Result.failure(failure)
                }
            }
            lastReceipts = receipts
            Log.i(TAG, "Settings commit for page '$page' completed")
            Result.success(true)
        } catch (e: Exception) {
            Log.w(TAG, "commitPageSettings error on page '$page': ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Commits one key via preview -> confirm. Returns null on success (including the
     * skip-if-equal case), or the failure exception to abort the whole commit.
     * Successful writes append a read-back receipt to [receipts] (Feature 1).
     */
    private fun commitSingleSetting(
        page: String,
        key: String,
        target: Any?,
        receipts: MutableList<String>
    ): Exception? {
        // 1. Fresh page: the view token and row snapshot must be current.
        val pageData = client.getJson("/api/settings/pages/$page")
            ?: return Exception("Failed to fetch settings page $page")
        val view = pageData.optString("view", "").ifEmpty { null }
            ?: return Exception("Missing view token from page $page")
        val rows = pageData.optJSONArray("rows")
            ?: return Exception("Missing rows from settings page $page")

        // 2. Resolve the row for this key.
        val rowIndex = resolveRowIndex(page, rows, key, target)
        if (rowIndex < 0) {
            return Exception("Could not locate '$key' on the $page settings page")
        }
        val row = rows.optJSONObject(rowIndex)
            ?: return Exception("Could not locate '$key' on the $page settings page")

        // 3. The device must offer the row for editing.
        if (!row.optBoolean("available", true)) {
            val reason = row.optString("reason", "").trim()
            return Exception(
                if (reason.isNotEmpty()) "'$key' is unavailable: $reason"
                else "'$key' is unavailable on the $page settings page"
            )
        }

        // 4. Skip the write when the device already holds the target value.
        //    The value came from the fresh page read above, so it still earns
        //    a read-back receipt.
        if (valuesEqual(row.opt("value"), target)) {
            Log.i(TAG, "Skipping '$key' on page '$page': already at target value")
            val label = row.optString("label", "").trim().ifEmpty { key }
            receipts.add(SettingsReceipts.format(label, row.opt("value")))
            return null
        }

        // 5. Preview: body keys must be exactly {view, row, value}.
        val previewPayload = JSONObject().apply {
            put("view", view)
            put("row", rowIndex)
            put("value", target ?: JSONObject.NULL)
        }
        val (previewCode, preview) = client.postJson("/api/settings/preview", previewPayload)
        val intent = preview?.optString("intent", "")?.ifEmpty { null }
        if (previewCode !in 200..299 || intent == null) {
            val err = preview?.optString("error", "")?.ifEmpty { null } ?: "HTTP $previewCode"
            Log.w(TAG, "Preview for '$key' on page '$page' failed: $err (HTTP $previewCode)")
            return Exception("Preview failed for '$key': $err")
        }

        // 6. Confirm: {intent, confirmed, page} asks for the refreshed page back.
        val confirmPayload = JSONObject().apply {
            put("intent", intent)
            put("confirmed", true)
            put("page", page)
        }
        val (confirmCode, confirm) = client.postJson("/api/settings/confirm", confirmPayload)
        if (confirmCode !in 200..299 || confirm?.optBoolean("saved", false) != true) {
            val err = confirm?.optString("error", "")?.ifEmpty { null } ?: "HTTP $confirmCode"
            Log.w(TAG, "Confirm for '$key' on page '$page' failed: $err (HTTP $confirmCode)")
            return Exception("Confirm failed for '$key': $err")
        }
        Log.i(TAG, "Settings commit for '$key' on page '$page': saved")

        // 7. SLC writes are verified against the refreshed page returned by confirm.
        if (page == "slc") {
            val refreshed = confirm.optJSONObject("page")
            val band = OFFSET_KEY_REGEX.matchEntire(key.trim())?.groupValues?.get(1)?.toIntOrNull()?.minus(1)
            val targetNum = (target as? Number)?.toDouble()
            if (refreshed != null && band != null && targetNum != null) {
                val applied = SgGalaxyClient.parseSlcOffsets(refreshed)?.getOrNull(band)
                if (applied == null || kotlin.math.abs(applied - targetNum) > 0.01) {
                    Log.w(TAG, "SLC verify failed for '$key': target=$targetNum, device=${applied ?: "unreadable"}")
                    return Exception("Device did not apply $key")
                }
            }
        }
        // 8. Receipt (Feature 1): re-read the page and report the row's NEW
        //    live value. A mismatch is stated plainly, never dressed up as
        //    success; a failed re-read is reported as unverified.
        val pageAfter = try {
            client.getJson("/api/settings/pages/$page")
        } catch (_: Exception) {
            null
        }
        val rowsAfter = pageAfter?.optJSONArray("rows")
        val idxAfter = if (rowsAfter != null) resolveRowIndex(page, rowsAfter, key, target) else -1
        val rowAfter = if (idxAfter >= 0) rowsAfter?.optJSONObject(idxAfter) else null
        if (rowAfter != null) {
            val label = rowAfter.optString("label", "").trim().ifEmpty { key }
            val readBack = rowAfter.opt("value")
            val matches = valuesEqual(readBack, target) ||
                (target != null && readBack != null && readBack.toString() == target.toString())
            receipts.add(
                if (matches) SettingsReceipts.format(label, readBack)
                else SettingsReceipts.formatMismatch(label, readBack, target)
            )
        } else {
            receipts.add(SettingsReceipts.formatUnverified(key))
        }
        return null
    }

    /**
     * Resolves the row index for [key] within a settings page's rows array.
     * Rows carry no key field, so matching is by label:
     * 1. On the "slc" page, Offset1..Offset7 resolve FIRST by order among
     *    Dom's pinned range rows (band N = the Nth "{lo}–{hi} {unit}
     *    offset" row — current Dom inclusive ranges, older Dom "{lo}–<{hi}"
     *    exclusive ranges; both accepted), then by a row whose label names
     *    that band (literal "Offset N" or the band's speed-range substrings).
     * 2. A row whose label equals the key (ignoring case) or contains it.
     * 3. A row whose choices contain the target value's string form.
     * Returns -1 when no row matches.
     */
    private fun resolveRowIndex(page: String, rows: org.json.JSONArray, key: String, target: Any?): Int {
        if (page == "slc") {
            val band = OFFSET_KEY_REGEX.matchEntire(key.trim())?.groupValues?.get(1)?.toIntOrNull()?.minus(1)
            if (band != null) {
                // FIRST (Dom pinned shape): band N is the Nth pinned
                // range row ("{lo}–<{hi} {unit} offset") on the page.
                // The bounds come from the device's own document, so
                // resolution is by ORDER — ranges are never hardcoded.
                val pinned = SgGalaxyClient.slcOffsetRowIndices(rows)
                if (band in pinned.indices) return pinned[band]
                // Fall back to the legacy label logic when the pinned
                // pattern matches nothing on this page.
                for (r in 0 until rows.length()) {
                    val label = rows.optJSONObject(r)?.optString("label", "") ?: continue
                    if (bandFromLabel(label) == band) return r
                }
                return -1
            }
        }
        for (r in 0 until rows.length()) {
            val label = rows.optJSONObject(r)?.optString("label", "")?.trim() ?: continue
            if (label.equals(key, ignoreCase = true) || label.contains(key, ignoreCase = true)) return r
        }
        val targetText = target?.toString()?.trim().orEmpty()
        if (targetText.isNotEmpty()) {
            for (r in 0 until rows.length()) {
                val choices = rows.optJSONObject(r)?.optJSONArray("choices") ?: continue
                for (c in 0 until choices.length()) {
                    if (choices.optString(c, "").trim() == targetText) return r
                }
            }
        }
        return -1
    }

    /**
     * Maps an SLC row label to its 0-based offset band, mirroring the label logic of
     * SgGalaxyClient.bandFromKeyOrLabel: a literal "offset N" wins, otherwise the
     * speed-range substrings decide. Both Dom label formats are accepted — current
     * Dom (419d32286) inclusive ranges (0-25, 26-34, 35-43, 44-53, 54-63, 64-74,
     * 75-98) and older Dom exclusive ranges (0-25, 25-35, ..., 75+).
     */
    private fun bandFromLabel(label: String): Int? {
        val text = label.lowercase()
            .replace('\u2013', '-')
            .replace('\u2014', '-')
        val m = Regex("offset\\s*([1-7])").find(text)
        if (m != null) return m.groupValues[1].toInt() - 1
        if (text.contains("0") && text.contains("25")) return 0
        if ((text.contains("25") || text.contains("26")) && (text.contains("34") || text.contains("35"))) return 1
        if (text.contains("35") && (text.contains("43") || text.contains("44") || text.contains("45"))) return 2
        if ((text.contains("44") || text.contains("45")) && (text.contains("53") || text.contains("54") || text.contains("55"))) return 3
        if ((text.contains("54") || text.contains("55")) && (text.contains("63") || text.contains("64") || text.contains("65"))) return 4
        if ((text.contains("64") || text.contains("65")) && (text.contains("74") || text.contains("75"))) return 5
        if (text.contains("74") || text.contains("75") || text.contains("99") || text.contains("75+")) return 6
        return null
    }

    /** True when the device's current row value already equals the target. */
    private fun valuesEqual(current: Any?, target: Any?): Boolean {
        if (target is Number) {
            val currentNum = when (current) {
                is Number -> current.toDouble()
                is String -> current.trim().toDoubleOrNull()
                else -> null
            }
            return currentNum != null && kotlin.math.abs(currentNum - target.toDouble()) < 0.001
        }
        if (target is String) {
            return current?.toString()?.trim() == target.trim()
        }
        return false
    }

    /**
     * Updates driving personality profile (Aggressive, Standard, Relaxed).
     * Strictly locked to vehicle Parked state (state == "parked") for driver safety.
     */
    fun updatePersonalityProfile(
        page: String = "profiles",
        profileName: String,
        currentDeviceState: String? = null
    ): Result<Boolean> {
        if (!isVehicleParked(currentDeviceState)) {
            return Result.failure(IllegalStateException("Vehicle must be parked to commit settings."))
        }
        val values = JSONObject().apply { put("PersonalityProfile", profileName) }
        val res = commitPageSettings(page, values, currentDeviceState)
        if (res.isSuccess) {
            Log.i(TAG, "Personality profile updated to '$profileName'")
        }
        return res
    }

    /**
     * Updates Speed Limit Controller (SLC) offset curve bands (Offset1..7).
     * Locked to vehicle Parked state.
     */
    fun updateSlcOffsets(
        offsets: DoubleArray,
        currentDeviceState: String? = null
    ): Result<Boolean> {
        if (!isVehicleParked(currentDeviceState)) {
            return Result.failure(IllegalStateException("Vehicle must be parked to commit settings."))
        }
        val values = JSONObject()
        for (i in 0 until offsets.size.coerceAtMost(7)) {
            values.put("Offset${i + 1}", offsets[i])
        }
        val res = commitPageSettings("slc", values, currentDeviceState)
        if (res.isSuccess && res.getOrDefault(false)) {
            NavStateManager.sgOffsetsMph = offsets
        }
        return res
    }

    /** Fetches the currently active personality profile name from comma settings. */
    fun getCurrentPersonalityProfile(page: String = "profiles"): String? {
        val json = client.getJson("/api/settings/pages/$page") ?: return null
        val profiles = listOf("Aggressive", "Standard", "Relaxed", "Traffic")
        val rows = json.optJSONArray("rows") ?: return null

        // Primary: the profile selector row offers the profile names as choices
        // and its current value is one of them.
        for (r in 0 until rows.length()) {
            val row = rows.optJSONObject(r) ?: continue
            val value = row.optString("value", "").trim()
            if (value !in profiles) continue
            val choices = row.optJSONArray("choices") ?: continue
            val offersProfiles = (0 until choices.length()).any {
                choices.optString(it, "").trim() in profiles
            }
            if (offersProfiles) return value
        }

        // Fallback: a row labelled as the personality setting with a current value.
        for (r in 0 until rows.length()) {
            val row = rows.optJSONObject(r) ?: continue
            val label = row.optString("label", "")
            val value = row.optString("value", "").trim()
            if (label.contains("personality", ignoreCase = true) && value.isNotEmpty()) {
                return value
            }
        }
        return null
    }

    /** Fetches quick destination shortcut slots from /api/favorites/slots. */
    fun getFavoriteSlots(forceRefresh: Boolean = false): JSONObject? {
        if (!forceRefresh && cachedFavoriteSlots != null) {
            return cachedFavoriteSlots
        }
        val slots = client.getJson(SgGalaxyClient.PATH_FAVORITES_SLOTS)
        if (slots != null) {
            cachedFavoriteSlots = slots
        }
        return slots ?: cachedFavoriteSlots
    }

    /** Saves a destination to a quick access shortcut slot on the comma device. */
    fun saveFavoriteSlot(slotIndex: Int, dest: NavDestination): Boolean {
        val payload = JSONObject().apply {
            put("slot", slotIndex)
            put("destination", JSONObject().apply {
                put("name", dest.name)
                put("latitude", dest.latitude)
                put("longitude", dest.longitude)
                put("address", dest.placeName)
            })
        }
        val (code, resp) = client.postJson(SgGalaxyClient.PATH_FAVORITES_SLOTS, payload)
        val success = code in 200..299 && resp?.optBoolean("error", false) != true
        if (success) {
            cachedFavoriteSlots = null
        }
        return success
    }
}
