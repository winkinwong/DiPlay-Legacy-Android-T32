package com.shilapi.xcertplay.usb

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule

class IpodServiceStopperTest {
    @get:Rule val folder = TemporaryFolder()

    private class FakeShell : IpodServiceStopper.ShellExecutor {
        val commands = mutableListOf<List<String>>()
        var succeed = true
        override fun run(vararg command: String): Boolean {
            commands += command.toList()
            return succeed
        }
    }

    private class FakeKiller : IpodServiceStopper.BackgroundProcessKiller {
        val killed = mutableListOf<String>()
        var succeed = true
        override fun kill(packageName: String): Boolean {
            killed += packageName
            return succeed
        }
    }

    private val shell = FakeShell()
    private val killer = FakeKiller()
    private val delays = mutableListOf<Long>()
    private val stopper = IpodServiceStopper(
        shell = shell,
        backgroundKiller = killer,
        sleeper = { millis -> delays += millis },
    )

    @After fun tearDown() {
        shell.commands.clear()
        killer.killed.clear()
        delays.clear()
    }

    @Test fun planWithRootEnablesForceStopAndBackgroundKill() {
        val plan = stopper.plan(rootAvailable = true)
        assertTrue(plan.rootForceStop)
        assertTrue(plan.killBackground)
    }

    @Test fun planWithoutRootOnlyKillsBackground() {
        val plan = stopper.plan(rootAvailable = false)
        assertFalse(plan.rootForceStop)
        assertTrue(plan.killBackground)
    }

    @Test fun rootAvailableRequiresAnExistingBinary() {
        val su = folder.newFile("su").apply { setExecutable(true) }
        assertTrue(stopper.rootAvailable(listOf(su.absolutePath)))
        assertFalse(stopper.rootAvailable(listOf("/no/such/su")))
        assertFalse(stopper.rootAvailable(emptyList()))
    }

    @Test fun stopOnceWithRootRunsSuForceStopThenBackgroundKill() {
        val result = stopper.stopOnce(context = null, stopper.plan(rootAvailable = true))
        assertEquals(
            listOf("su", "-c", "am force-stop ${IpodServiceStopper.IPOD_PACKAGE}"),
            shell.commands.single(),
        )
        assertEquals(listOf(IpodServiceStopper.IPOD_PACKAGE), killer.killed)
        assertTrue(result.mechanisms.contains("root-force-stop"))
        assertTrue(result.mechanisms.contains("kill-background"))
        assertTrue(result.succeeded)
    }

    @Test fun stopOnceWithoutRootSkipsShellAndKillsBackground() {
        val result = stopper.stopOnce(context = null, stopper.plan(rootAvailable = false))
        assertTrue(shell.commands.isEmpty())
        assertEquals(listOf(IpodServiceStopper.IPOD_PACKAGE), killer.killed)
        assertTrue(result.mechanisms.contains("kill-background"))
        assertFalse(result.mechanisms.any { it.contains("root") })
    }

    @Test fun stopOnceReportsFailedMechanisms() {
        shell.succeed = false
        killer.succeed = false
        val result = stopper.stopOnce(context = null, stopper.plan(rootAvailable = true))
        assertTrue(result.mechanisms.contains("root-force-stop-failed"))
        assertTrue(result.mechanisms.contains("kill-background-failed"))
        assertFalse(result.succeeded)
        assertEquals("root-force-stop-failed,kill-background-failed", result.summary)
    }

    @Test fun stopWithRetryStopsAsSoonAsAMechanismSucceeds() {
        // A successful stop needs no further rounds; retrying only delays the bring-up.
        stopper.stopWithRetry(context = null, stopper.plan(rootAvailable = true), attempts = 3, delayMs = 400)
        assertEquals(1, shell.commands.size)
        assertEquals(1, killer.killed.size)
        assertTrue(delays.isEmpty())
    }

    /**
     * On this head unit `su` is absent and killBackgroundProcesses is denied, so every round fails
     * identically. Two identical failures are enough to conclude it cannot work here, so the third
     * round (and its 400 ms) is skipped (2026-11-19 field log: the same
     * "root-force-stop-failed,kill-background" on all seven reconnects).
     */
    @Test fun stopWithRetryGivesUpWhenEveryRoundFailsTheSameWay() {
        shell.succeed = false
        killer.succeed = false
        val result = stopper.stopWithRetry(context = null, stopper.plan(rootAvailable = true), attempts = 3, delayMs = 400)
        assertFalse("nothing may report success", result.succeeded)
        assertEquals("two identical failures are conclusive", 2, shell.commands.size)
        assertEquals("only the first retry waits", listOf(400L), delays)
    }

    @Test fun stopWithRetrySingleAttemptNeverSleeps() {
        stopper.stopWithRetry(context = null, stopper.plan(rootAvailable = false), attempts = 1, delayMs = 400)
        assertEquals(1, killer.killed.size)
        assertTrue(delays.isEmpty())
    }

    @Test fun stopWithRetryRequiresAtLeastOneAttempt() {
        try {
            stopper.stopWithRetry(context = null, stopper.plan(rootAvailable = false), attempts = 0)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected: a zero-attempt stop is a configuration error
        }
    }

    @Test fun ipodPackageConstantNamesTheHsaeIpodService() {
        assertEquals("com.hsae.ipod", IpodServiceStopper.IPOD_PACKAGE)
    }

    @Test fun suPathsCoverTheStandardRootLocations() {
        assertEquals(
            listOf("/system/bin/su", "/system/xbin/su", "/sbin/su"),
            IpodServiceStopper.SU_PATHS,
        )
    }

    @Test fun defaultShellExecutorReportsFailureWithoutThrowing() {
        // su is absent on development machines and "exit 7" makes a
        // present su fail; either way the executor reports false instead
        // of throwing, so a head unit without root simply logs a failure.
        assertFalse(IpodServiceStopper.ShellExecutor.Default.run("su", "-c", "exit 7"))
    }
}
