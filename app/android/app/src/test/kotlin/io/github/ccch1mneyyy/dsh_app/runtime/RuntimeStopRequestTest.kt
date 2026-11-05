package io.github.ccch1mneyyy.dsh_app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStopRequestTest {
    @Test fun completionIsDeferredAndDeliveredOnce() {
        val results = mutableListOf<Throwable?>()
        val request = RuntimeController.StopRequest(7, 41) { results += it }

        assertEquals(7, request.runGeneration)
        assertEquals(41L, request.startTicketOwner)
        assertTrue(request.belongsTo(7))
        assertFalse(request.belongsTo(8))
        assertTrue(request.belongsToStart(41))
        assertFalse(request.belongsToStart(42))
        assertTrue(results.isEmpty())

        request.finish(null)
        request.finish(IllegalStateException("late duplicate"))

        assertEquals(1, results.size)
        assertNull(results.single())
    }

    @Test fun cleanupFailureIsReturnedAsFailure() {
        val results = mutableListOf<Throwable?>()
        val request = RuntimeController.StopRequest(8, null) { results += it }
        val failure = IllegalStateException("runtime stop or root cleanup is unconfirmed")

        request.finish(failure)

        assertFalse(results.single() == null)
        assertEquals("runtime stop or root cleanup is unconfirmed", results.single()?.message)
    }

    @Test fun readyFromCancelledRunCannotRestoreRunningPhase() {
        assertTrue(acceptsRunReady(true, RuntimeController.Phase.STARTING, 12, 11, false))
        assertFalse(acceptsRunReady(true, RuntimeController.Phase.STARTING, 12, 12, false))
        assertFalse(acceptsRunReady(true, RuntimeController.Phase.STOPPING, 12, 11, false))
        assertFalse(acceptsRunReady(false, RuntimeController.Phase.STARTING, 12, 11, false))
    }

    @Test fun installCannotDispatchWhileLifecycleIsLiveOrCleanupUnconfirmed() {
        assertFalse(
            RuntimeController.installAllowed(RuntimeController.Phase.RUNNING, resetting = false, processActive = true),
        )
        assertFalse(
            RuntimeController.installAllowed(RuntimeController.Phase.STARTING, resetting = false, processActive = false),
        )
        assertFalse(
            RuntimeController.installAllowed(RuntimeController.Phase.STOPPING, resetting = false, processActive = false),
        )
        assertFalse(
            RuntimeController.installAllowed(RuntimeController.Phase.INSTALLING, resetting = false, processActive = false),
        )
        assertFalse(
            RuntimeController.installAllowed(RuntimeController.Phase.INSTALLED, resetting = true, processActive = false),
        )
        assertFalse(
            RuntimeController.installAllowed(RuntimeController.Phase.ERROR, resetting = false, processActive = true),
        )
        assertTrue(
            RuntimeController.installAllowed(RuntimeController.Phase.ERROR, resetting = false, processActive = false),
        )
    }
}
