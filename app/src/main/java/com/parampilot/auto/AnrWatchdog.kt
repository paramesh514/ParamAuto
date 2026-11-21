package com.parampilot.auto

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * ANR watchdog (2026-11-10, updated to write ADB-accessible freeze reports
 * without showing a UI popup dialog): a daemon thread posts a tick to the
 * main looper every [TICK_MS]; when the main thread has not run a tick for
 * [BLOCK_MS] while the process is foreground/visible, the main thread's
 * stack + app version + connection/navigation state are written directly
 * to disk (`anr_freeze_report.txt` and `anr_freeze_reports.log` under
 * external app files `/sdcard/Android/data/com.bogala.paramauto/files/`
 * with fallback to internal `filesDir`) so it can be pulled or inspected
 * via `adb` without interrupting the driver with a popup dialog.
 */
object AnrWatchdog {

    private const val TAG = "AnrWatchdog"
    private const val PREFS = "anr_report"
    private const val KEY_TEXT = "last_anr_text"
    private const val KEY_TIME = "last_anr_time"
    private const val KEY_SHOWN = "last_anr_shown_time"
    const val REPORT_FILE_NAME = "anr_freeze_report.txt"
    const val REPORT_LOG_FILE_NAME = "anr_freeze_reports.log"
    private const val TICK_MS = 2000L
    private const val BLOCK_MS = 5000L

    private val started = AtomicBoolean(false)
    private val lastTickMs = AtomicLong(0L)
    @Volatile private var reportedThisEpisode = false

    fun start(app: Application) {
        if (!started.compareAndSet(false, true)) return
        val mainHandler = Handler(Looper.getMainLooper())
        lastTickMs.set(System.currentTimeMillis())
        val ticker = object : Runnable {
            override fun run() {
                lastTickMs.set(System.currentTimeMillis())
                mainHandler.postDelayed(this, TICK_MS)
            }
        }
        mainHandler.postDelayed(ticker, TICK_MS)
        Thread {
            while (true) {
                try {
                    Thread.sleep(1000L)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                val now = System.currentTimeMillis()
                val tick = lastTickMs.get()
                if (!AnrReport.isBlocked(tick, now, BLOCK_MS)) {
                    reportedThisEpisode = false
                    continue
                }
                if (reportedThisEpisode) continue
                if (!isForegroundOrVisible(app)) continue
                reportedThisEpisode = true
                try {
                    capture(app, now - tick, now)
                } catch (e: Exception) {
                    Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }.apply {
            name = "anr-watchdog"
            isDaemon = true
            start()
        }
    }

    private fun isForegroundOrVisible(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        val me = am.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }
            ?: return false
        return me.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    private fun capture(app: Application, blockedForMs: Long, nowMs: Long) {
        val pkg = try {
            app.packageManager.getPackageInfo(app.packageName, 0)
        } catch (e: Exception) {
            null
        }
        val versionName = pkg?.versionName ?: "?"
        val versionCode = pkg?.let {
            if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt()
            else @Suppress("DEPRECATION") it.versionCode
        } ?: -1
        val stateLines = listOf(
            "transport: ${NavStateManager.activeTransport}",
            "commaFound: ${NavStateManager.commaFound}",
            "isGalaxyCloud: ${NavStateManager.isGalaxyCloud}",
            "deviceState: ${NavStateManager.deviceState}",
            "navigating: ${NavStateManager.activeDestination != null}",
            "usePhoneSignals: ${NavStateManager.usePhoneSignals}",
            "usePhoneStops: ${NavStateManager.usePhoneStops}",
            "usePhoneTraffic: ${NavStateManager.usePhoneTraffic}"
        )
        val stackLines = Looper.getMainLooper().thread.stackTrace.map { "  at $it" }
        // Background transitions are not freezes (Param, 2026-11-13):
        // a capture whose stack is the renderer stopping
        // (HardwareRenderer.setStopped via ViewRootImpl) with no app
        // frames anywhere is discarded before anything is saved or
        // written — no report at all for that signature.
        if (AnrReport.isBackgroundTransition(stackLines)) {
            Log.i(TAG, "Watchdog capture discarded: background transition (renderer stop, no app frames).")
            return
        }
        val text = AnrReport.format(
            versionName = versionName,
            versionCode = versionCode,
            capturedAtMs = nowMs,
            blockedForMs = blockedForMs,
            stateLines = stateLines,
            stackLines = stackLines
        )
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TEXT, text)
            .putLong(KEY_TIME, nowMs)
            .putLong(KEY_SHOWN, nowMs)
            .apply()
        val savedPath = writeReportToFile(app, text)
        Log.e(TAG, "Main thread blocked ${blockedForMs}ms; freeze report written to ${savedPath ?: "prefs"}.\n$text")
    }

    /**
     * Writes the freeze report to ADB-accessible storage:
     * - Primary: `/sdcard/Android/data/<pkg>/files/anr_freeze_report.txt`
     *   (plus appended history in `anr_freeze_reports.log`), accessible via
     *   `adb pull /sdcard/Android/data/com.bogala.paramauto/files/anr_freeze_report.txt`
     *   or `adb shell cat /sdcard/Android/data/com.bogala.paramauto/files/anr_freeze_report.txt`.
     * - Secondary mirror: internal `filesDir/anr_freeze_report.txt`.
     */
    private fun writeReportToFile(context: Context, text: String): String? {
        var primaryPath: String? = null
        val separator = "\n====================\n"
        try {
            val extDir = context.getExternalFilesDir(null)
            if (extDir != null) {
                if (!extDir.exists()) extDir.mkdirs()
                val latestFile = File(extDir, REPORT_FILE_NAME)
                latestFile.writeText(text, Charsets.UTF_8)
                val logFile = File(extDir, REPORT_LOG_FILE_NAME)
                logFile.appendText(text + separator, Charsets.UTF_8)
                primaryPath = latestFile.absolutePath
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed writing external freeze report: ${e.message}")
        }
        try {
            val intDir = context.filesDir
            if (intDir != null) {
                if (!intDir.exists()) intDir.mkdirs()
                val intLatest = File(intDir, REPORT_FILE_NAME)
                intLatest.writeText(text, Charsets.UTF_8)
                val intLog = File(intDir, REPORT_LOG_FILE_NAME)
                intLog.appendText(text + separator, Charsets.UTF_8)
                if (primaryPath == null) primaryPath = intLatest.absolutePath
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed writing internal freeze report: ${e.message}")
        }
        return primaryPath
    }

    /** The saved report if it hasn't been shown yet, else null. */
    fun pendingReport(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val time = prefs.getLong(KEY_TIME, 0L)
        if (time == 0L || time <= prefs.getLong(KEY_SHOWN, 0L)) return null
        return prefs.getString(KEY_TEXT, null)
    }

    /** No-op: freeze popup dialog is disabled; reports are written to ADB-accessible files on disk. */
    fun showPendingReport(@Suppress("UNUSED_PARAMETER") activity: Activity) {
        // Intentionally no-op: freeze reports are written to disk for ADB retrieval.
    }
}
