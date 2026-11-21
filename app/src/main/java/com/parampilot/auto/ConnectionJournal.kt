package com.parampilot.auto

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's connection journal (Param, 2026-11-19: "App to comma
 * connection has glitches. What is the best way you can get log
 * feedback and fix them").
 *
 * A rolling, timestamped record of connection EVENTS — heartbeat loss
 * and recovery, transport switches, HTTP failures, Cloud re-logins,
 * BLE connect/disconnect — kept in a small in-memory ring and, once
 * [install] has run (from the Application class), appended to
 * `connection_journal.log` in the same ADB-accessible external files
 * directory as the freeze reports (internal filesDir mirror), so the
 * evidence survives the moment and can be pulled later:
 *   adb pull /sdcard/Android/data/com.bogala.paramauto/files/connection_journal.log
 *
 * Why not logcat: it rotates away within hours and cannot be pulled
 * after the fact; a glitch reported at dinner would have no evidence
 * left by the time anyone looked.
 *
 * Recording is transition-based, never per-poll: the heartbeat records
 * only when it is lost and when it recovers (with the outage length),
 * and a [RepeatCollapser] folds consecutive identical events into one
 * "repeated N more times" line, so a long outage produces a handful
 * of lines, not thousands. record() never throws and never touches
 * the network; file writes are best-effort and capped (the file is
 * trimmed to whole lines when it outgrows [FILE_CAP_BYTES]).
 */
object ConnectionJournal {

    const val CAT_APP = "APP"
    const val CAT_HEARTBEAT = "HEARTBEAT"
    const val CAT_TRANSPORT = "TRANSPORT"
    const val CAT_HTTP = "HTTP"
    const val CAT_BLE = "BLE"
    const val CAT_CLOUD = "CLOUD"

    const val JOURNAL_FILE_NAME = "connection_journal.log"
    const val MAX_RING_LINES = 600
    const val FILE_CAP_BYTES = 512 * 1024

    private const val TAG = "ConnectionJournal"

    data class Event(val timeMs: Long, val category: String, val detail: String)

    data class Stats(val events: Long, val lastEventMs: Long, val bufferedLines: Int)

    // ---- Pure core (unit-tested) ----

    fun formatTimestamp(timeMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timeMs))

    fun formatEvent(event: Event): String =
        "${formatTimestamp(event.timeMs)} [${event.category}] ${event.detail}"

    /**
     * Folds consecutive identical events: the first occurrence emits
     * its line; repeats emit nothing until a different event (or a
     * [flush]) closes the streak with one summary line.
     */
    class RepeatCollapser {
        private var last: Event? = null
        private var repeats = 0
        private var lastRepeatMs = 0L

        fun feed(event: Event): List<String> {
            val prev = last
            if (prev != null && prev.category == event.category &&
                prev.detail == event.detail
            ) {
                repeats++
                lastRepeatMs = event.timeMs
                return emptyList()
            }
            val out = mutableListOf<String>()
            if (prev != null && repeats > 0) out += summary(prev)
            out += ConnectionJournal.formatEvent(event)
            last = event
            repeats = 0
            lastRepeatMs = event.timeMs
            return out
        }

        /** The pending repeat summary, if a streak is still open. */
        fun flush(): List<String> {
            val prev = last ?: return emptyList()
            if (repeats == 0) return emptyList()
            val line = summary(prev)
            repeats = 0
            return listOf(line)
        }

        fun reset() {
            last = null
            repeats = 0
            lastRepeatMs = 0L
        }

        private fun summary(prev: Event): String =
            "${ConnectionJournal.formatTimestamp(lastRepeatMs)} " +
                "[${prev.category}] (previous event repeated $repeats more times)"
    }

    /** Drops whole lines from the front until [text] fits [capBytes]. */
    fun trimToCap(text: String, capBytes: Int): String {
        if (text.toByteArray(Charsets.UTF_8).size <= capBytes) return text
        val lines = text.split("\n")
        val kept = ArrayDeque<String>()
        var size = 0
        for (i in lines.indices.reversed()) {
            val lineBytes = lines[i].toByteArray(Charsets.UTF_8).size + 1
            if (size + lineBytes > capBytes) break
            kept.addFirst(lines[i])
            size += lineBytes
        }
        return kept.joinToString("\n")
    }

    // ---- Runtime ----

    private val lock = Any()
    private val ring = ArrayDeque<String>()
    private val collapser = RepeatCollapser()
    private var sink: FileSink? = null

    @Volatile
    private var eventCount = 0L

    @Volatile
    private var lastEventMs = 0L

    fun record(category: String, detail: String) {
        val event = Event(System.currentTimeMillis(), category, detail)
        val lines: List<String>
        synchronized(lock) {
            eventCount++
            lastEventMs = event.timeMs
            lines = collapser.feed(event)
            for (line in lines) {
                ring.addLast(line)
                while (ring.size > MAX_RING_LINES) ring.removeFirst()
            }
        }
        if (lines.isNotEmpty()) {
            try {
                sink?.append(lines)
            } catch (_: Exception) {
                // The journal must never take the connection down with it.
            }
        }
    }

    /** The buffered lines, oldest first, with any open repeat streak
     * summarized at the end. */
    fun recentLines(): List<String> = synchronized(lock) {
        ring.toList() + collapser.flush()
    }

    fun recentText(maxLines: Int = 300): String =
        recentLines().takeLast(maxLines).joinToString("\n")

    fun stats(): Stats = synchronized(lock) {
        Stats(eventCount, lastEventMs, ring.size)
    }

    fun clear() {
        synchronized(lock) {
            ring.clear()
            collapser.reset()
            eventCount = 0
            lastEventMs = 0
        }
        try {
            sink?.clear()
        } catch (_: Exception) {
        }
    }

    /**
     * Installs the file sink (call once, from the Application class).
     * Events recorded before this run are only in the ring; they are
     * dumped to the file here so nothing from process start is lost.
     */
    fun install(context: Context) {
        synchronized(lock) {
            if (sink != null) return
            val created = FileSink(context.applicationContext)
            sink = created
            val backlog = ring.toList()
            if (backlog.isNotEmpty()) {
                try {
                    created.append(backlog)
                } catch (_: Exception) {
                }
            }
        }
        record(CAT_APP, "journal started")
    }

    /** Appends journal lines to the external files dir (ADB-pullable)
     * and the internal mirror, trimming to whole lines over the cap. */
    private class FileSink(context: Context) {
        private val files: List<File> = buildList {
            try {
                context.getExternalFilesDir(null)?.let { dir ->
                    if (!dir.exists()) dir.mkdirs()
                    add(File(dir, JOURNAL_FILE_NAME))
                }
            } catch (_: Exception) {
            }
            try {
                val dir = context.filesDir
                if (dir != null) {
                    if (!dir.exists()) dir.mkdirs()
                    add(File(dir, JOURNAL_FILE_NAME))
                }
            } catch (_: Exception) {
            }
        }

        @Synchronized
        fun append(lines: List<String>) {
            if (lines.isEmpty()) return
            val text = lines.joinToString("\n") + "\n"
            for (file in files) {
                try {
                    file.appendText(text, Charsets.UTF_8)
                    if (file.length() > FILE_CAP_BYTES) {
                        val trimmed = ConnectionJournal.trimToCap(
                            file.readText(Charsets.UTF_8),
                            FILE_CAP_BYTES * 3 / 4
                        )
                        file.writeText(trimmed, Charsets.UTF_8)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "journal append failed: ${e.message}")
                }
            }
        }

        @Synchronized
        fun clear() {
            for (file in files) {
                try {
                    if (file.exists()) file.writeText("", Charsets.UTF_8)
                } catch (_: Exception) {
                }
            }
        }
    }
}
