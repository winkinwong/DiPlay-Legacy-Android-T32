package com.shilapi.xcertplay

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The 2026-10-21 freeze-death left no crash record, no shutdown line and no task-removal line; these
 * files are the only remaining witnesses on Android 4.4. The report must carry the heartbeat age and
 * any memory trim so the next export can say "killed" or "crashed".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProcessVitalsTest {
    @get:Rule val folder = TemporaryFolder()

    private val logs: File get() = folder.newFolder("logs")

    @Test fun aFirstStartSaysThereWasNoPreviousProcess() {
        val directory = logs
        ProcessVitals.startIn(directory, 1_000_000)
        val vitals = File(directory, ProcessVitals.VITALS_FILE_NAME).readText()
        assertTrue("start line recorded", vitals.contains("process start"))
        assertTrue("no previous heartbeat on a first start", vitals.contains("previousHeartbeat=none"))
    }

    @Test fun theNextStartMeasuresTheGapSinceTheLastHeartbeat() {
        val directory = logs
        ProcessVitals.startIn(directory, 1_000_000)
        ProcessVitals.startIn(directory, 1_005_000)
        val vitals = File(directory, ProcessVitals.VITALS_FILE_NAME).readText()
        assertEquals("both starts recorded", 2, vitals.split("process start").size - 1)
        assertTrue("gap equals the time between the runs", vitals.contains("previousHeartbeatAgeMs=5000"))
    }

    @Test fun memoryTrimEvidenceReachesTheReport() {
        val directory = logs
        ProcessVitals.recordTrimIn(directory, 15, 2_000_000)
        val report = ProcessVitals.reportIn(directory, 3_000_000)
        assertTrue("critical trim named", report.contains("memory trim level=15 (RUNNING_CRITICAL)"))
    }

    @Test fun unknownTrimLevelsStayVisibleAsNumbers() {
        val directory = logs
        ProcessVitals.recordTrimIn(directory, 999, 2_000_000)
        assertTrue(ProcessVitals.reportIn(directory, 3_000_000).contains("memory trim level=999 (unknown)"))
    }

    @Test fun theReportCarriesTheHeartbeatAge() {
        val directory = logs
        ProcessVitals.startIn(directory, 1_000_000)
        val report = ProcessVitals.reportIn(directory, 3_000_000)
        assertTrue("age bounds when the process stopped writing", report.contains("last heartbeat ageMs=2000000 ago"))
    }

    @Test fun anEmptyDirectoryIsHonest() {
        val report = ProcessVitals.reportIn(logs, 1_000_000)
        assertTrue(report.contains("no heartbeat recorded"))
        assertTrue(report.contains("no vitals recorded"))
    }

    @Test fun everyReportLineSurvivesRedaction() {
        val directory = logs
        ProcessVitals.startIn(directory, 1_000_000)
        ProcessVitals.recordTrimIn(directory, 10, 1_500_000)
        ProcessVitals.reportIn(directory, 2_000_000).lineSequence().forEach {
            assertEquals("line survives redaction: $it", it, DiagnosticRedactor.redact(it))
        }
    }
}
