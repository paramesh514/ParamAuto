package com.parampilot.auto

/**
 * Which StarPilot channel the connected comma runs (Addendum 128,
 * Param 2026-11-21). The app must handle both channels: functionality
 * backed by an API that exists only on the testing channel is enabled
 * only when the comma is positively identified as testing, and hidden
 * or disabled otherwise. When the branch has never been read — a
 * first install, or no software status fetched yet — the comma is
 * treated as STABLE (Param's rule: "If branch not available for first
 * time install consider comma on stable").
 *
 * The branch comes from the software status the slow poll already
 * fetches (the installed branch); CommaClient records each fresh
 * reading in NavStateManager.commaBranch and CommaBranchStore.
 * Pure logic — the gating rules are unit-tested.
 */
enum class CommaBranch { TESTING, STABLE, UNKNOWN }

fun parseCommaBranch(raw: String?): CommaBranch =
    when (raw?.trim()?.lowercase()) {
        "testing" -> CommaBranch.TESTING
        "stable" -> CommaBranch.STABLE
        else -> CommaBranch.UNKNOWN
    }

/** The channel capability decisions use: UNKNOWN resolves to STABLE. */
fun effectiveCommaBranch(branch: CommaBranch): CommaBranch =
    if (branch == CommaBranch.TESTING) CommaBranch.TESTING else CommaBranch.STABLE

/** True only when the comma is positively on the testing channel. */
fun testingApiEnabled(branch: CommaBranch): Boolean =
    effectiveCommaBranch(branch) == CommaBranch.TESTING

/** User-facing channel label (Comma health & caches). */
fun commaChannelLabel(branch: CommaBranch): String = when (branch) {
    CommaBranch.TESTING -> "Testing"
    CommaBranch.STABLE -> "Stable"
    CommaBranch.UNKNOWN -> "Stable (assumed — not yet read)"
}
