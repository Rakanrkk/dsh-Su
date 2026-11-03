package io.github.ccch1mneyyy.dsh_app.runtime

import org.junit.Assert.*
import org.junit.Test

class RootStartupGateTest {
    private class Queue {
        val work = ArrayDeque<() -> Unit>()
        fun add(action: () -> Unit) { work.add(action) }
        fun run() = work.removeFirst().invoke()
    }
    private class Startup {
        val gate = RootStartupGate()
        val worker = Queue()
        val main = Queue()
        val effects = mutableListOf<String>()
        var granted = true
        fun request(kind: RootStartupGate.Kind = RootStartupGate.Kind.INSTALL): RootStartupGate.Ticket {
            val ticket = gate.begin(kind)
            gate.prepare(ticket, worker::add, main::add, { current ->
                effects += "prepare"
                current() && granted
            }) { ok ->
                if (ok) gate.dispatch(ticket) { effects += "intent:${ticket.id}" }
                else effects += "rejected:${ticket.id}"
            }
            return ticket
        }
        fun service(ticket: RootStartupGate.Ticket) {
            if (gate.consume(ticket.id, ticket.kind)) {
                effects += "foreground"
                effects += if (ticket.kind == RootStartupGate.Kind.INSTALL) "unpack" else "process"
            }
        }
    }
    @Test fun pendingPreparationDoesNotDispatchForegroundOrUnpack() {
        val s = Startup()
        val ticket = s.request()
        s.service(ticket)
        assertTrue(s.effects.isEmpty())
        s.worker.run()
        s.service(ticket)
        assertEquals(listOf("prepare"), s.effects)
        s.main.run()
        s.service(ticket)
        assertEquals(listOf("prepare", "intent:${ticket.id}", "foreground", "unpack"), s.effects)
        s.service(ticket)
        assertEquals(1, s.effects.count { it == "unpack" })
    }
    @Test fun deniedAttemptCanRetryWithoutChangingRuntimeGeneration() {
        val s = Startup()
        s.granted = false
        val denied = s.request()
        s.worker.run(); s.main.run(); s.service(denied)
        assertFalse(s.effects.contains("foreground"))
        s.granted = true
        val retry = s.request()
        assertNotEquals(denied.id, retry.id)
        s.worker.run(); s.main.run(); s.service(retry)
        assertEquals(1, s.effects.count { it == "foreground" })
    }
    @Test fun stopBeforeWorkerPreventsEvenPreparation() {
        val s = Startup()
        val ticket = s.request()
        s.gate.invalidate()
        s.worker.run(); s.main.run(); s.service(ticket)
        assertEquals(listOf("rejected:${ticket.id}"), s.effects)
    }
    @Test fun stopAfterRootButBeforeMainCallbackCannotDispatch() {
        val s = Startup()
        val ticket = s.request()
        s.worker.run(); s.gate.invalidate(); s.main.run(); s.service(ticket)
        assertEquals(listOf("prepare", "rejected:${ticket.id}"), s.effects)
    }
    @Test fun resetAfterDispatchRejectsQueuedService() {
        val s = Startup()
        val ticket = s.request()
        s.worker.run(); s.main.run(); s.gate.invalidate(); s.service(ticket)
        assertFalse(s.effects.contains("foreground"))
    }
    @Test fun oldIntentDoesNotBorrowNewPreparedTicket() {
        val s = Startup()
        val old = s.request()
        s.worker.run(); s.main.run()
        s.gate.invalidate()
        val fresh = s.request()
        s.worker.run(); s.main.run()
        s.service(old)
        assertFalse(s.effects.contains("foreground"))
        s.service(fresh)
        assertEquals(1, s.effects.count { it == "foreground" })
        assertFalse(s.gate.consume(0, RootStartupGate.Kind.INSTALL))
        assertFalse(s.gate.consume(fresh.id, RootStartupGate.Kind.START))
    }
}
