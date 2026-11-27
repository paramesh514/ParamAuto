package com.parampilot.auto

import android.util.Log
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Repository for AI driving models, audio themes, and FLM driver monitoring (Section 6).
 * Enforces the vehicle Parked state safety gate for all state-sensitive mutations.
 */
class ModelManagerRepository(private val client: SgGalaxyClient) {

    companion object {
        private const val TAG = "ModelManagerRepo"
        private const val PATH_MODELS_DOWNLOAD = "/api/models/download"
        private const val PATH_MODELS_DELETE = "/api/models/delete"

        /**
         * The training-results parse (Addendum 149; API audit
         * 2026-11-26). Current servers send
         * `current {status, fresh, ageSeconds, progress {samples,
         * blocks, requiredBlocks}}`; the older `training` object
         * and the flat shape stay accepted.
         */
        fun parseFlmStatus(json: JSONObject): GalaxyFlmStatus {
            val training = json.optJSONObject("training")
                ?: json.optJSONObject("current") ?: json
            val progress = training.optJSONObject("progress") ?: training
            return GalaxyFlmStatus(
                status = training.optString("status", "unknown"),
                fresh = training.optBoolean("fresh", false),
                ageSeconds = training.optLong("ageSeconds", 0L),
                samples = progress.optInt(
                    "samples", training.optInt("samples", 0)
                ),
                blocks = progress.optInt(
                    "blocks", training.optInt("blocks", 0)
                ),
                requiredBlocks = progress.optInt(
                    "requiredBlocks",
                    training.optInt("requiredBlocks", 8)
                )
            )
        }
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

    /** The most recent /api/models/manager payload, kept for its top-level fields. */
    @Volatile
    private var lastManagerJson: JSONObject? = null

    /**
     * The manager snapshot's active-model pointers (active small pick,
     * active big pick, and the pick actually in effect), parsed from the
     * most recent manager fetch. All null when no fetch has succeeded.
     */
    fun getManagerActives(): ActiveModel.ManagerActives =
        ActiveModel.actives(lastManagerJson)

    /**
     * The model download currently running on the comma, from the most
     * recent manager fetch (Addendum 135); null when none is running.
     */
    fun getDownloadState(): ModelDownloadState? = ModelDownload.parse(lastManagerJson)

    /**
     * Parses every model entry from /api/models/manager, keeping only entries
     * with a non-empty model id, in the device's own order.
     */
    private fun fetchAllModels(): List<GalaxyModelItem> {
        val json = client.getJson(SgGalaxyClient.PATH_MODELS_MANAGER) ?: return emptyList()
        lastManagerJson = json
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
                unavailableReason = m.optString("unavailableReason", "").ifEmpty { null },
                released = m.optString("released", ""),
                builtin = m.optBoolean("builtin", false),
                artifactSizeBytes = m.optLong("artifactSize", 0L),
                small = m.optBoolean("small", true)
            )
            if (item.value.isNotEmpty()) {
                out.add(item)
            }
        }
        return out
    }

    /**
     * Returns list of installed and available neural driving models from /api/models/manager.
     */
    fun getInstalledModels(): List<GalaxyModelItem> {
        return fetchAllModels().filter { it.installed || it.selectable }
    }

    /**
     * Returns every neural driving model known to the device from /api/models/manager,
     * with no installed/selectable filtering.
     */
    fun getAllModels(): List<GalaxyModelItem> {
        return fetchAllModels()
    }

    /**
     * Returns the most recently released models from /api/models/manager.
     * Models with a parseable "released" date (yyyy-MM-dd) come first, newest
     * first; models with a missing or unparseable date keep their device order
     * after the dated ones.
     */
    fun getLatestModels(limit: Int = 5): List<GalaxyModelItem> {
        val dated = mutableListOf<Pair<GalaxyModelItem, LocalDate>>()
        val undated = mutableListOf<GalaxyModelItem>()
        for (item in getAllModels()) {
            val date = parseReleaseDate(item.released)
            if (date != null) {
                dated.add(item to date)
            } else {
                undated.add(item)
            }
        }
        dated.sortByDescending { it.second }
        val sorted = dated.map { it.first } + undated
        return sorted.take(limit.coerceAtLeast(0))
    }

    private fun parseReleaseDate(raw: String): LocalDate? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        return try {
            LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Returns the currently active driving model identifier and label from /api/models/status.
     */
    fun getActiveModel(): Pair<String, String>? {
        val json = client.getJson(SgGalaxyClient.PATH_MODELS_STATUS) ?: return null
        val current = json.optJSONObject("current") ?: json
        val modelId = current.optString("model", "").ifEmpty {
            current.optString("active", "")
        }
        val label = current.optString("label", modelId)
        return if (modelId.isNotEmpty()) Pair(modelId, label) else null
    }

    /**
     * Activates a neural driving model via POST /api/models/active.
     * Strictly locked to vehicle Parked state (state == "parked") for driving safety.
     *
     * The comma's contract (Dom models/manager.py): the payload key
     * set must be exactly {"profile", "model"}, and the profile must
     * match the model's own size class — [small] models go in the
     * "small" slot, big (external-GPU) models in the "big" slot.
     * Sending {"model"} alone is rejected with HTTP 409 "Invalid
     * model selection" — which this function used to mask as
     * Result.success(false), so the UI claimed the change while the
     * comma kept the old pick (Param's report, 2026-11-20). A 2xx is
     * verified against a fresh manager read: the saved pick for the
     * profile must name [modelId]. The model itself loads on the
     * next drive — the server's own reply is "Model selected for
     * the next drive".
     */
    fun activateModel(modelId: String, small: Boolean, currentDeviceState: String? = null): Result<Boolean> {
        val state = currentDeviceState?.lowercase()?.trim()
            ?: fetchRemoteDeviceState()
            ?: NavStateManager.deviceState?.lowercase()?.trim()
        if (state != "parked") {
            return Result.failure(IllegalStateException("Vehicle must be parked to change driving models."))
        }
        return try {
            val profile = ActiveModel.profileFor(small)
            val payload = JSONObject().put("profile", profile).put("model", modelId)
            val (code, response) = client.postJson(SgGalaxyClient.PATH_MODELS_ACTIVE, payload)
            val serverError = response?.optString("error", "")?.ifEmpty { null }
            if (code !in 200..299 || serverError != null) {
                Log.w(TAG, "Model activation '$modelId' rejected: code=$code, error=$serverError")
                return Result.failure(Exception(serverError ?: "Comma answered HTTP $code"))
            }
            fetchAllModels()
            val actives = getManagerActives()
            val savedPick = if (small) actives.activeSmall else actives.activeBig
            if (savedPick != modelId) {
                Log.w(TAG, "Model activation not confirmed: expected $modelId in $profile, found $savedPick")
                return Result.failure(
                    Exception("The comma didn't confirm the change — it still has ${savedPick ?: "another model"} selected")
                )
            }
            Log.i(TAG, "Model activation '$modelId' confirmed in profile $profile")
            Result.success(true)
        } catch (e: Exception) {
            Log.w(TAG, "activateModel error: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Downloads a neural driving model via POST /api/models/download.
     * Strictly locked to vehicle Parked state.
     */
    fun downloadModel(modelId: String, currentDeviceState: String? = null): Result<Boolean> {
        if (!isVehicleParked(currentDeviceState)) {
            return Result.failure(IllegalStateException("Vehicle must be parked to download models."))
        }
        return try {
            val payload = JSONObject().put("model", modelId)
            val (code, response) = client.postJson(PATH_MODELS_DOWNLOAD, payload)
            val success = code in 200..299 && response?.optBoolean("error", false) != true
            Log.i(TAG, "Model download '$modelId': code=$code, success=$success")
            if (success) {
                Result.success(true)
            } else {
                val err = response?.optString("error", "")?.ifEmpty { null }
                if (err != null) Result.failure(Exception(err)) else Result.success(false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "downloadModel error: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Deletes a downloaded neural driving model via POST /api/models/delete.
     * Strictly locked to vehicle Parked state.
     */
    fun deleteModel(modelId: String, currentDeviceState: String? = null): Result<Boolean> {
        if (!isVehicleParked(currentDeviceState)) {
            return Result.failure(IllegalStateException("Vehicle must be parked to delete models."))
        }
        return try {
            val payload = JSONObject().put("model", modelId)
            val (code, response) = client.postJson(PATH_MODELS_DELETE, payload)
            val success = code in 200..299 && response?.optBoolean("error", false) != true
            Log.i(TAG, "Model delete '$modelId': code=$code, success=$success")
            if (success) {
                Result.success(true)
            } else {
                val err = response?.optString("error", "")?.ifEmpty { null }
                if (err != null) Result.failure(Exception(err)) else Result.success(false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "deleteModel error: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Returns available audio sound packs from GET /api/sounds.
     */
    fun getSoundPacks(): List<GalaxySoundPack> {
        val json = client.getJson(SgGalaxyClient.PATH_SOUNDS) ?: return emptyList()
        val packsArr = json.optJSONArray("packs") ?: return emptyList()
        val out = mutableListOf<GalaxySoundPack>()
        for (i in 0 until packsArr.length()) {
            val p = packsArr.optJSONObject(i) ?: continue
            val id = p.optString("id", "")
            val name = p.optString("name", id)
            val installed = p.optBoolean("installed", false)
            if (id.isNotEmpty()) {
                out.add(GalaxySoundPack(id, name, installed))
            }
        }
        return out
    }

    /**
     * Downloads an audible sound pack via POST /api/sounds/download.
     * Strictly locked to vehicle Parked state.
     */
    fun downloadSoundPack(packId: String, currentDeviceState: String? = null): Result<Boolean> {
        if (!isVehicleParked(currentDeviceState)) {
            return Result.failure(IllegalStateException("Vehicle must be parked to download sound packs."))
        }
        return try {
            val payload = JSONObject().put("pack", packId)
            val (code, response) = client.postJson(SgGalaxyClient.PATH_SOUNDS_DOWNLOAD, payload)
            Result.success(code in 200..299 && response?.optBoolean("error", false) != true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Returns Driver Monitoring (FLM) neural network calibration status from GET /api/flm/training-results.
     */
    fun getFlmCalibration(): GalaxyFlmStatus? {
        val json = client.getJson(SgGalaxyClient.PATH_FLM) ?: return null
        return parseFlmStatus(json)
    }
}
