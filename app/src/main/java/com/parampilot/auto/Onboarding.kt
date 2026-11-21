package com.parampilot.auto

import android.content.Context

/**
 * First-run onboarding state (Param, 2026-11-20): the two-step setup
 * (connect to the comma, then map keys) is offered once, when the app
 * is first installed. Completing it, skipping it, or simply closing
 * it all count as seen — the same pages stay one tap away in Settings
 * (Wi-Fi networks, Map keys & usage), so there is never a reason to
 * nag again on the next launch.
 */
object Onboarding {

    private const val PREFS = "paramauto_setup"
    private const val KEY_DONE = "onboarding_v1_done"

    fun shouldShow(context: Context): Boolean =
        !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DONE, false)

    fun markDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DONE, true).apply()
    }
}
