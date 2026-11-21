package com.parampilot.auto

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent

/**
 * Voice search (Feature 18).
 *
 * The system speech UI (RecognizerIntent.ACTION_RECOGNIZE_SPEECH) does
 * the listening, so the app needs no RECORD_AUDIO permission and no
 * audio pipeline of its own: Android returns a ranked list of possible
 * transcriptions and [VoiceQuery.pick] takes the first usable one.
 */
object VoiceQuery {

    /** First non-blank result, trimmed; null when there is nothing usable. */
    fun pick(results: List<String>?): String? {
        if (results.isNullOrEmpty()) return null
        for (candidate in results) {
            val trimmed = candidate.trim()
            if (trimmed.isNotEmpty()) return trimmed
        }
        return null
    }
}

/** Availability probe for the system speech recognizer. */
object VoiceSearch {

    /**
     * True when some activity on this phone handles the speech
     * recognition intent. On Android 11+ this relies on the manifest's
     * <queries> declaration for the recognition action.
     */
    fun isAvailable(context: Context): Boolean {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        return try {
            context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    /** The intent MainActivity launches for a voice search round-trip. */
    fun createIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a destination")
    }
}
