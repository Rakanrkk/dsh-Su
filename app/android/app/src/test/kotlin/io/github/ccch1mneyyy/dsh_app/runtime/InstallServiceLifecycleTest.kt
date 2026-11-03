package io.github.ccch1mneyyy.dsh_app.runtime

import org.junit.Assert.*
import org.junit.Test

class InstallServiceLifecycleTest {
    @Test fun foregroundAndWakeLockCompleteBeforeStartingUnpack() {
        val gate = RootStartupGate()
        val lifecycle = InstallServiceLifecycle()
        val events = mutableListOf<String>()
        val ticket = gate.begin(RootStartupGate.Kind.INSTALL)
        gate.prepare(ticket, { it() }, { it() }, { true }) { ok ->
            assertTrue(ok)
            gate.dispatch(ticket) {
                assertTrue(gate.consume(ticket.id, ticket.kind))
                lifecycle.begin(ticket.id,
                    foreground = { events += "foreground"; events += "wakeLock" },
                    ready = { events += "unpack" },
                )
            }
        }
        assertTrue(lifecycle.finish(ticket.id) { events += "done" })
        assertEquals(listOf("foreground", "wakeLock", "unpack", "done"), events)
        assertFalse(lifecycle.finish(ticket.id) { fail("duplicate DONE") })
    }
    @Test fun preflightDenialAndCancelledIntentCannotCreateCompletionNotification() {
        val gate = RootStartupGate()
        val lifecycle = InstallServiceLifecycle()
        val ticket = gate.begin(RootStartupGate.Kind.INSTALL)
        gate.prepare(ticket, { it() }, { it() }, { false }) { ok -> assertFalse(ok) }
        assertFalse(gate.consume(ticket.id, ticket.kind))
        assertFalse(lifecycle.finish(ticket.id) { fail("preflight failure sent DONE") })
    }
    @Test fun oldDoneCannotFinishNewInstallAndFailedForegroundDoesNotOwnDone() {
        val lifecycle = InstallServiceLifecycle()
        lifecycle.begin(2, {}, {})
        assertFalse(lifecycle.finish(1) { fail("old DONE") })
        assertTrue(lifecycle.finish(2) {})
        assertThrows(IllegalStateException::class.java) {
            lifecycle.begin(3, { throw IllegalStateException("FGS failed") }, { fail("unpack before foreground") })
        }
        assertFalse(lifecycle.finish(3) { fail("never foregrounded") })
    }
}
