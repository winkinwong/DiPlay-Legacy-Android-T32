package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskRemovalPolicyTest {
    @Test fun bydFirmwareTearsTheSessionDownBeforeTheForceStop() {
        assertEquals(TaskRemovalPolicy.Action.TEAR_DOWN, TaskRemovalPolicy.actionFor(isBydFirmware = true))
    }

    @Test fun otherHeadUnitsKeepTheSessionForAFastReattach() {
        assertEquals(TaskRemovalPolicy.Action.KEEP_ALIVE, TaskRemovalPolicy.actionFor(isBydFirmware = false))
    }
}
