package com.parampilot.auto

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

/**
 * Custom Application class with global uncaught exception handling.
 * Ensures unexpected runtime exceptions in any thread are caught,
 * logged with full context, persisted for diagnostics, and surfaced
 * to the user gracefully rather than causing an unhandled crash.
 */
class ParamAuto : Application() {

    override fun onCreate() {
        super.onCreate()

        // ANR watchdog: captures the main thread's stack when it
        // blocks (Param's "isn't responding" reports, 2026-11-10).
        AnrWatchdog.start(this)

        // Connection journal (2026-11-19): connection events persisted
        // to an ADB-pullable file so app↔comma glitches leave evidence.
        ConnectionJournal.install(this)

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val errorMsg = throwable.localizedMessage ?: throwable.message ?: throwable.javaClass.simpleName
            Log.e(TAG, "Uncaught exception on thread '${thread.name}' (id=${thread.id}): $errorMsg", throwable)

            try {
                val isBackgroundRecoverable = Looper.myLooper() != Looper.getMainLooper() && throwable !is Error
                // Save crash report to SharedPreferences
                val prefs = getSharedPreferences(PREFS_CRASH, MODE_PRIVATE)
                val trace = Log.getStackTraceString(throwable)
                prefs.edit()
                    .putString(KEY_LAST_ERROR, errorMsg)
                    .putString(KEY_LAST_TRACE, trace)
                    .putString(KEY_THREAD_NAME, thread.name)
                    .putLong(KEY_LAST_TIME, if (isBackgroundRecoverable) 0L else System.currentTimeMillis())
                    .apply()

                // If not main thread and not a fatal Error, surface a user-friendly Toast on the main thread and avoid killing the app
                if (isBackgroundRecoverable) {
                    Handler(Looper.getMainLooper()).post {
                        try {
                            Toast.makeText(
                                applicationContext,
                                "Background issue: $errorMsg",
                                Toast.LENGTH_LONG
                            ).show()
                        } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
                    }
                    return@setDefaultUncaughtExceptionHandler
                }

                // If on main thread, show Toast before forwarding to default handler
                Handler(Looper.getMainLooper()).post {
                    try {
                        Toast.makeText(
                            applicationContext,
                            "ParamAuto encountered an issue: $errorMsg",
                            Toast.LENGTH_LONG
                        ).show()
                    } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
                }
                Thread.sleep(1200)
            } catch (e: Exception) {
                Log.e(TAG, "Error in crash handler: ${e.message}")
            }

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val TAG = "ParamAuto"
        const val PREFS_CRASH = "app_crash_report"
        const val KEY_LAST_ERROR = "last_error"
        const val KEY_LAST_TRACE = "last_trace"
        const val KEY_THREAD_NAME = "last_thread_name"
        const val KEY_LAST_TIME = "last_time"
    }
}
