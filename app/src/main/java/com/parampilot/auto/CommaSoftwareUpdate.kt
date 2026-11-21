package com.parampilot.auto

import org.json.JSONObject

/**
 * Parsed view of the comma's software state from SecretGoodStarPilot's
 * typed Galaxy API (GET /api/software/status, POST /api/software/action).
 * All fields are parsed defensively: anything the device omits stays empty,
 * false, or (for the auto-download preference) null rather than being guessed.
 */
data class SoftwareUpdateStatus(
    val installedDisplayVersion: String,
    val installedVersion: String,
    val branch: String,
    val commitShort: String,
    val updaterState: String,
    val targetBranch: String,
    val updateAvailable: Boolean,
    val updateReady: Boolean,
    val lastCheckedAt: String,
    val progressText: String,
    val autoDownloads: Boolean? = null
)

/**
 * Comma software update manager: update check, download, install trigger
 * and reboot, via the SG software endpoints. POST /api/software/action
 * answers 200 with the full fresh status snapshot, so callers can render
 * device state straight from the response. Callers must invoke these
 * methods on background threads.
 */
class CommaSoftwareUpdate(private val client: SgGalaxyClient) {

    /** GET /api/software/status?history=0; null when the device can't be read. */
    fun fetchStatus(): SoftwareUpdateStatus? {
        val json = client.getJson(PATH_STATUS) ?: return null
        return parseStatus(json)
    }

    /**
     * POST /api/software/action with {action} plus "branch" when [branch]
     * is non-null (download/install carry the branch; check/reboot don't).
     * Returns (httpCode, responseJson?).
     */
    fun postAction(action: String, branch: String?): Pair<Int, JSONObject?> {
        val body = JSONObject().apply {
            put("action", action)
            if (branch != null) put("branch", branch)
        }
        return client.postJson(PATH_ACTION, body)
    }

    /**
     * POST /api/software/action with {action: "preferences",
     * automaticDownloads: <value>}. Returns (httpCode, responseJson?);
     * a 200 response carries the full fresh status snapshot.
     */
    fun postPreferences(automaticDownloads: Boolean): Pair<Int, JSONObject?> {
        val body = JSONObject().apply {
            put("action", "preferences")
            put("automaticDownloads", automaticDownloads)
        }
        return client.postJson(PATH_ACTION, body)
    }

    companion object {
        private const val PATH_STATUS = "/api/software/status?history=0"
        private const val PATH_ACTION = "/api/software/action"

        /** Parses both the GET status response and the POST action snapshot. */
        fun parseStatus(json: JSONObject): SoftwareUpdateStatus {
            val installed = json.optJSONObject("installed")
            val version = installed?.optString("version", "").orEmpty()
            val displayVersion = installed?.optString("displayVersion", "").orEmpty()
                .ifEmpty { version }
            val branch = installed?.optString("branch", "").orEmpty()
            val commitShort = installed?.optString("commit", "").orEmpty().take(8)

            val updater = json.optJSONObject("updater")
            val updaterState = updater?.optString("state", "").orEmpty()
            val targetBranch = updater?.optString("targetBranch", "").orEmpty()
            val updateAvailable = updater?.optBoolean("targetChangeFound", false) == true
            val updateReady = updater?.optBoolean("finalizedUpdateReady", false) == true
            val lastCheckedAt = updater?.optString("lastCheckedAt", "").orEmpty()

            val operations = json.optJSONObject("operations")
            val progress = operations?.optJSONObject("progress")
            val stage = progress?.optString("stage", "").orEmpty()
            val detail = progress?.optString("detail", "").orEmpty()
            val joined = listOf(stage, detail).filter { it.isNotBlank() }.joinToString(" — ")
            val progressText = joined.ifEmpty { updaterState }

            // The auto-download preference is not documented at a single
            // location in the status snapshot, so probe the plausible spots
            // in order. has() decides: an absent key must never read as an
            // explicit "off" the device never reported.
            val preferences = operations?.optJSONObject("preferences")
            val autoDownloads: Boolean? = when {
                preferences?.has("automaticDownloads") == true ->
                    preferences?.optBoolean("automaticDownloads")
                operations?.has("automaticDownloads") == true ->
                    operations?.optBoolean("automaticDownloads")
                updater?.has("automaticDownloads") == true ->
                    updater?.optBoolean("automaticDownloads")
                json.has("automaticDownloads") ->
                    json.optBoolean("automaticDownloads")
                else -> null
            }

            return SoftwareUpdateStatus(
                installedDisplayVersion = displayVersion,
                installedVersion = version,
                branch = branch,
                commitShort = commitShort,
                updaterState = updaterState,
                targetBranch = targetBranch,
                updateAvailable = updateAvailable,
                updateReady = updateReady,
                lastCheckedAt = lastCheckedAt,
                progressText = progressText,
                autoDownloads = autoDownloads
            )
        }

        /** Human-readable failure text: the device's own error wins. */
        fun errorText(code: Int, body: JSONObject?): String {
            val deviceError = body?.optString("error", "")?.ifEmpty { null }
            if (deviceError != null) return deviceError
            return when (code) {
                401 -> "Galaxy sign-in needed — set the Galaxy password in Settings"
                503 -> "Software updates are unavailable on the comma"
                else -> "HTTP $code"
            }
        }
    }
}
