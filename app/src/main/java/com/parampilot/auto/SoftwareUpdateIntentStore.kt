package com.parampilot.auto

import android.content.Context

/**
 * Persists the pending comma software install (Addendum 126) in its
 * own SharedPreferences file, so the request survives app restarts.
 * The decision logic lives in SoftwareUpdatePlan (layer 2); this is
 * only storage.
 */
object SoftwareUpdateIntentStore {

    private const val PREFS = "software_update_intent"
    private const val KEY_COMMIT = "base_commit"
    private const val KEY_BRANCH = "branch"
    private const val KEY_REQUESTED_AT = "requested_at_ms"
    private const val KEY_LAST_ATTEMPT = "last_attempt_ms"
    private const val KEY_ACCEPTED_AT = "accepted_at_ms"
    private const val KEY_WENT_DOWN = "comma_went_down"

    fun load(context: Context): SoftwareUpdatePending? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val requestedAt = prefs.getLong(KEY_REQUESTED_AT, 0L)
        if (requestedAt == 0L) return null
        return SoftwareUpdatePending(
            baseCommit = prefs.getString(KEY_COMMIT, "").orEmpty(),
            branch = prefs.getString(KEY_BRANCH, "").orEmpty(),
            requestedAtMs = requestedAt,
            lastAttemptMs = prefs.getLong(KEY_LAST_ATTEMPT, 0L),
            acceptedAtMs = prefs.getLong(KEY_ACCEPTED_AT, 0L),
            commaWentDown = prefs.getBoolean(KEY_WENT_DOWN, false)
        )
    }

    fun save(context: Context, pending: SoftwareUpdatePending) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_COMMIT, pending.baseCommit)
            .putString(KEY_BRANCH, pending.branch)
            .putLong(KEY_REQUESTED_AT, pending.requestedAtMs)
            .putLong(KEY_LAST_ATTEMPT, pending.lastAttemptMs)
            .putLong(KEY_ACCEPTED_AT, pending.acceptedAtMs)
            .putBoolean(KEY_WENT_DOWN, pending.commaWentDown)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
