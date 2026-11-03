package io.github.ccch1mneyyy.dsh_app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class RootCleanupConfirmationTest {
    @Test fun stopFailureAndNotBoundDoNotConfirmCleanup() {
        val cleanup = RootCleanupConfirmation()

        cleanup.recordStop("deleteFailed")
        cleanup.recordExit(-1)
        cleanup.recordStop("notBound")

        assertFalse(cleanup.confirmed)
    }

    @Test fun localPreparationCancelledBeforeIpcDoesNotSubmitAndConfirmsNoContainer() {
        val gate = DshRuncClient.RootStartGate()
        val localConfigPrepared = true
        var submissions = 0

        val cancellation = gate.cancel()
        assertEquals(DshRuncClient.RootStartGate.CancelResult.NOT_SUBMITTED, cancellation)
        assertFalse(gate.submit({ localConfigPrepared }) { submissions++ })
        assertEquals(0, submissions)
        assertEquals("notSubmitted", gate.cleanupStatus(cancellation))

        val cleanup = RootCleanupConfirmation()
        cleanup.recordStop(gate.cleanupStatus(cancellation))
        assertTrue(cleanup.confirmed)
    }

    @Test fun ipcExceptionRemainsUnknownAndCannotBeTreatedAsPreSubmitCancellation() {
        val gate = DshRuncClient.RootStartGate()
        assertThrows(IllegalStateException::class.java) {
            gate.submit({ true }) { throw IllegalStateException("binder start uncertain") }
        }

        val cancellation = gate.cancel()
        assertEquals(DshRuncClient.RootStartGate.CancelResult.SUBMISSION_ATTEMPTED, cancellation)
        assertEquals("notBound", gate.cleanupStatus(cancellation))
        val cleanup = RootCleanupConfirmation()
        cleanup.recordStop("notBound")
        assertFalse(cleanup.confirmed)
    }

    @Test fun successfulWaitExitConfirmsCleanup() {
        val cleanup = RootCleanupConfirmation()

        cleanup.recordExit(137)

        assertTrue(cleanup.confirmed)
    }

    @Test fun lateStopSuccessConfirmsSameCleanupAfterWaiterFailure() {
        val cleanup = RootCleanupConfirmation()
        cleanup.recordExit(-1)

        assertFalse(cleanup.confirmed)
        cleanup.recordStop("ok")
        assertTrue(cleanup.confirmed)
    }

    @Test fun anotherOwnerConfirmationCannotClearPendingOwner() {
        val pending = Any()
        val other = Any()

        assertTrue(clearPendingIfSameOwner(pending, other, confirmed = true) === pending)
        assertTrue(clearPendingIfSameOwner(pending, pending, confirmed = false) === pending)
        assertTrue(clearPendingIfSameOwner(pending, pending, confirmed = true) == null)
    }
}
