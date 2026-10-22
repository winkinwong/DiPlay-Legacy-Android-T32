package com.shilapi.xcertplay

import android.content.Context
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Process liveness evidence for a head unit where the process can die without a trace.
 *
 * The 2026-10-21 field log ended mid-write during a 106 s video freeze with no crash record, no
 * shutdown line and no task-removal line; Android 4.4 offers no other source of truth for such a
 * death. Two cheap files separate the candidates:
 *  - a heartbeat rewritten every 10 s bounds WHEN the process stopped writing anything;
 *  - onTrimMemory lines prove the system was squeezing memory (an LMK kill) before the silence.
 * The exported report reads both back, so the next exported log answers "killed or crashed".
 */
object ProcessVitals {
    const val HEARTBEAT_FILE_NAME = "diplay_heartbeat"
    const val VITALS_FILE_NAME = "diplay_vitals.log"
    const val MAX_VITALS_BYTES = 64 * 1024L
    const val HEARTBEAT_INTERVAL_MS = 10_000L

    private val lock = Any()
    private var heartbeatThread: Thread? = null

    fun start(context: Context, nowMillis: Long = System.currentTimeMillis()) =
        startIn(File(context.filesDir, "logs"), nowMillis)

    internal fun startIn(directory: File, nowMillis: Long) {
        directory.mkdirs()
        val heartbeat = File(directory, HEARTBEAT_FILE_NAME)
        val ageMillis = readHeartbeat(heartbeat)?.let { nowMillis - it }
        append(
            directory,
            "process start pid=${Process.myPid()} at=${timestamp(nowMillis)} " +
                (ageMillis?.let { "previousHeartbeatAgeMs=$it" } ?: "previousHeartbeat=none"),
        )
        writeHeartbeat(heartbeat, nowMillis)
        startHeartbeatThread(heartbeat)
    }

    /** Memory pressure evidence; TRIM_MEMORY_RUNNING_CRITICAL before silence is what an LMK kill looks like. */
    fun recordTrim(context: Context, level: Int, nowMillis: Long = System.currentTimeMillis()) =
        recordTrimIn(File(context.filesDir, "logs"), level, nowMillis)

    internal fun recordTrimIn(directory: File, level: Int, nowMillis: Long) {
        append(directory, "memory trim level=$level (${trimName(level)}) at=${timestamp(nowMillis)}")
    }

    fun report(context: Context): String = reportIn(File(context.filesDir, "logs"), System.currentTimeMillis())

    internal fun reportIn(directory: File, nowMillis: Long): String {
        val text = runCatching {
            File(directory, VITALS_FILE_NAME).takeIf { it.isFile }?.readText().orEmpty()
        }.getOrDefault("")
        val heartbeatAge = readHeartbeat(File(directory, HEARTBEAT_FILE_NAME))?.let { nowMillis - it }
        return buildString {
            appendLine(heartbeatAge?.let { "last heartbeat ageMs=$it ago" } ?: "no heartbeat recorded")
            append(if (text.isBlank()) "no vitals recorded" else text.trimEnd())
        }
    }

    private fun startHeartbeatThread(heartbeat: File) = synchronized(lock) {
        if (heartbeatThread?.isAlive == true) return
        heartbeatThread = Thread({
            while (true) {
                runCatching { Thread.sleep(HEARTBEAT_INTERVAL_MS) }
                writeHeartbeat(heartbeat, System.currentTimeMillis())
            }
        }, "diplay-heartbeat").apply {
            isDaemon = true
            start()
        }
    }

    private fun readHeartbeat(file: File): Long? = runCatching {
        if (!file.isFile) return null
        file.readText().substringBefore(' ').trim().toLongOrNull()
    }.getOrNull()

    private fun writeHeartbeat(file: File, nowMillis: Long) {
        runCatching { file.writeText("$nowMillis pid=${Process.myPid()}") }
    }

    private fun append(directory: File, line: String) = synchronized(lock) {
        runCatching {
            val safe = DiagnosticRedactor.redact(line) ?: return
            val file = File(directory, VITALS_FILE_NAME)
            if (file.length() + line.length > MAX_VITALS_BYTES) file.writeText("")
            file.appendText(safe + "\n")
        }
        Unit
    }

    internal fun trimName(level: Int) = when (level) {
        5 -> "RUNNING_MODERATE"
        10 -> "RUNNING_LOW"
        15 -> "RUNNING_CRITICAL"
        20 -> "UI_HIDDEN"
        40 -> "BACKGROUND"
        60 -> "MODERATE"
        80 -> "COMPLETE"
        else -> "unknown"
    }

    private fun timestamp(nowMillis: Long) =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(nowMillis))
}
