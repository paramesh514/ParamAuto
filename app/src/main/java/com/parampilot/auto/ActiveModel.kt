package com.parampilot.auto

import org.json.JSONObject

/**
 * Active driving model extraction (Feature 15) from the
 * GET /api/models/manager payload. The manager's per-model entries carry
 * value/label plus state flags; different Dom builds mark the active
 * entry with "active", "selected", or "current", and some expose a
 * top-level pointer instead. All spellings are tried; when none is
 * present the caller falls back to ModelManagerRepository.getActiveModel()
 * (/api/models/status). Returns the display label, else the model id.
 *
 * The current Dom manager snapshot carries the authoritative actives as
 * top-level fields (models/manager.py snapshot()): "activeSmallModel"
 * and "activeBigModel" are the two profile picks (the comma keeps one
 * active small model and one active big model), and "currentModel" is
 * the pick actually in effect (the big one when an external GPU is
 * present and a big model is chosen, otherwise the small one). Each
 * model entry also carries "small" (true unless the model requires the
 * external GPU). [actives], [badgeFor] and [sizeClass] surface those on
 * the Driving models page (Param, 2026-11-20).
 */
object ActiveModel {

    fun extract(managerJson: JSONObject?): String? {
        if (managerJson == null) return null

        // Top-level pointers first.
        for (key in listOf("activeModel", "active", "currentModel")) {
            val v = managerJson.optString(key, "").trim()
            if (v.isNotEmpty()) {
                // "currentModel" is a model id, not a display name:
                // resolve it to the entry's label when the payload
                // carries the models array too.
                if (key == "currentModel") {
                    labelForValue(managerJson, v)?.let { return it }
                }
                return v
            }
        }
        managerJson.optJSONObject("current")?.let { current ->
            val label = current.optString("label", "").trim()
                .ifEmpty { current.optString("model", "").trim() }
                .ifEmpty { current.optString("active", "").trim() }
            if (label.isNotEmpty()) return label
        }

        val models = managerJson.optJSONArray("models") ?: return null
        for (i in 0 until models.length()) {
            val m = models.optJSONObject(i) ?: continue
            val marked = m.optBoolean("active", false) ||
                m.optBoolean("selected", false) ||
                m.optBoolean("current", false)
            if (!marked) continue
            val name = m.optString("label", "").trim()
                .ifEmpty { m.optString("value", "").trim() }
            if (name.isNotEmpty()) return name
        }
        return null
    }

    /** The label of the model entry whose value is [value], or null. */
    fun labelForValue(managerJson: JSONObject?, value: String): String? {
        if (managerJson == null || value.isEmpty()) return null
        val models = managerJson.optJSONArray("models") ?: return null
        for (i in 0 until models.length()) {
            val m = models.optJSONObject(i) ?: continue
            if (m.optString("value", "") == value) {
                val label = m.optString("label", "").trim()
                if (label.isNotEmpty()) return label
            }
        }
        return null
    }

    /** The manager snapshot's three active-model pointers (ids). */
    data class ManagerActives(
        val activeSmall: String?,
        val activeBig: String?,
        val current: String?
    )

    fun actives(managerJson: JSONObject?): ManagerActives {
        if (managerJson == null) return ManagerActives(null, null, null)
        fun pointer(key: String): String? =
            managerJson.optString(key, "").trim().ifEmpty { null }
        return ManagerActives(
            activeSmall = pointer("activeSmallModel"),
            activeBig = pointer("activeBigModel"),
            current = pointer("currentModel")
        )
    }

    /**
     * The badge for a model row: "Active" for the model actually in
     * effect, otherwise "Active Big" / "Active Small" for the profile
     * pick that is not in effect, or null when the model is not an
     * active pick at all.
     */
    fun badgeFor(value: String, actives: ManagerActives): String? = when {
        value.isEmpty() -> null
        value == actives.current -> "Active"
        value == actives.activeBig -> "Active Big"
        value == actives.activeSmall -> "Active Small"
        else -> null
    }

    /** The comma's own size class for a model: small unless it needs the external GPU. */
    fun sizeClass(small: Boolean): String = if (small) "Small" else "Big"

    /**
     * The /api/models/active profile slot a model belongs to: the
     * comma requires the payload's profile to match the model's own
     * size class (Dom models/manager.py) — small models activate in
     * the "small" slot, big (external-GPU) models in "big".
     */
    fun profileFor(small: Boolean): String = if (small) "small" else "big"
}
