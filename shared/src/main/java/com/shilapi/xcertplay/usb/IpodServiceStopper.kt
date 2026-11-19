package com.shilapi.xcertplay.usb

import android.content.Context
import android.app.ActivityManager
import java.io.File

/**
 * Stops the head unit's iPod service ([IPOD_PACKAGE]) when an iPhone is
 * attached over USB. On HSAE head units (observed on the 2020 X-Trail
 * NTDA3, 2026-10) the iPod service claims the USB interface on every
 * attach, and CarPlay only connects after the driver force-stops it in
 * the head unit's system settings. This guard automates that step.
 *
 * The stop is best-effort and never fails a connection: rooted head units
 * get a shell `am force-stop` (which also kills foreground services), all
 * other head units fall back to [ActivityManager.killBackgroundProcesses].
 */
internal class IpodServiceStopper(
    private val shell: ShellExecutor = ShellExecutor.Default,
    private val backgroundKiller: BackgroundProcessKiller? = null,
    private val sleeper: (Long) -> Unit = { millis -> Thread.sleep(millis) },
) {
    internal companion object {
        /** The HSAE iPod service that competes for the USB interface. */
        internal const val IPOD_PACKAGE = "com.hsae.ipod"

        /** Standard locations of the su binary on rooted Android devices. */
        internal val SU_PATHS = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su")

        private const val FORCE_STOP_COMMAND = "am force-stop $IPOD_PACKAGE"
    }

    internal interface ShellExecutor {
        fun run(vararg command: String): Boolean

        companion object Default : ShellExecutor {
            override fun run(vararg command: String): Boolean = runCatching {
                val process = Runtime.getRuntime().exec(command)
                process.waitFor()
                process.exitValue() == 0
            }.getOrDefault(false)
        }
    }

    /** Kills a package's background processes through the framework. */
    internal interface BackgroundProcessKiller {
        fun kill(packageName: String): Boolean
    }

    internal class ActivityManagerBackgroundKiller(context: Context) : BackgroundProcessKiller {
        @Suppress("DEPRECATION")
        private val manager = context.applicationContext
            .getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

        override fun kill(packageName: String): Boolean = runCatching {
            // killBackgroundProcesses has no result; a completed call is a success.
            manager?.killBackgroundProcesses(packageName)
            true
        }.getOrDefault(false)
    }

    /** Which stop mechanisms apply on this head unit. */
    internal data class StopPlan(val rootForceStop: Boolean, val killBackground: Boolean)

    /** Which mechanisms ran, and whether any of them succeeded. */
    internal data class StopResult(val mechanisms: List<String>) {
        val succeeded: Boolean get() = mechanisms.any { !it.endsWith("-failed") }
        val summary: String get() = mechanisms.joinToString(",")
    }

    internal fun plan(rootAvailable: Boolean): StopPlan =
        StopPlan(rootForceStop = rootAvailable, killBackground = true)

    internal fun rootAvailable(paths: List<String> = SU_PATHS): Boolean =
        paths.any { File(it).canExecute() }

    /**
     * One stop attempt. [context] may be null when a [BackgroundProcessKiller]
     * was injected at construction; it is only needed to reach the
     * ActivityManager otherwise.
     */
    internal fun stopOnce(context: Context?, plan: StopPlan): StopResult {
        val mechanisms = mutableListOf<String>()
        if (plan.rootForceStop) {
            val ok = shell.run("su", "-c", FORCE_STOP_COMMAND)
            mechanisms += if (ok) "root-force-stop" else "root-force-stop-failed"
        }
        if (plan.killBackground) {
            val killer = backgroundKiller
                ?: context?.let { ActivityManagerBackgroundKiller(it) }
                ?: throw IllegalStateException("a context or a background killer is required")
            val ok = runCatching { killer.kill(IPOD_PACKAGE) }.getOrDefault(false)
            mechanisms += if (ok) "kill-background" else "kill-background-failed"
        }
        return StopResult(mechanisms)
    }

    /**
     * Stops the iPod service, retrying [attempts] times with [delayMs] between
     * attempts. The service starts from the same USB-attach broadcast that
     * begins the CarPlay flow, so its process may not exist yet on the first
     * attempt; the retries catch a late start.
     */
    internal fun stopWithRetry(
        context: Context?,
        plan: StopPlan,
        attempts: Int = 3,
        delayMs: Long = 400L,
    ): StopResult {
        require(attempts >= 1) { "at least one attempt is required" }
        val mechanisms = mutableListOf<String>()
        var previousRound: String? = null
        repeat(attempts) { round ->
            val outcome = stopOnce(context, plan)
            mechanisms += outcome.mechanisms
            // A mechanism that worked ends the job; another round cannot improve on success.
            if (outcome.succeeded) return StopResult(mechanisms)
            // Identical failure twice in a row means the environment cannot do it at all. On this
            // head unit `su` is absent and killBackgroundProcesses is denied, so three rounds burned
            // 1.2 s of every reconnect for nothing (2026-11-19 log: the same
            // "root-force-stop-failed,kill-background" on all seven reconnects).
            val signature = outcome.summary
            if (signature == previousRound) return StopResult(mechanisms)
            previousRound = signature
            if (round < attempts - 1) sleeper(delayMs)
        }
        return StopResult(mechanisms)
    }
}
