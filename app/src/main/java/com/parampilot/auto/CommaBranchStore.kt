package com.parampilot.auto

import android.content.Context

/**
 * Persists the last comma channel read from a software status
 * (Addendum 128), so a restart keeps the known channel instead of
 * falling back to the stable assumption until the next status read.
 */
object CommaBranchStore {

    private const val PREFS = "comma_branch"
    private const val KEY_BRANCH = "branch"

    fun load(context: Context): CommaBranch =
        parseCommaBranch(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_BRANCH, null)
        )

    fun save(context: Context, branch: CommaBranch) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_BRANCH, branch.name)
            .apply()
    }
}
