package io.github.ccch1mneyyy.dsh_app.notify

import org.junit.Assert.*
import org.junit.Test

class LivePostGateTest {
    private fun content(kind: LiveContent.Kind, since: Long? = 100L, event: String? = null, host: String = "remote") =
        LiveContent(kind, "title", "text", "chip", "public", sinceMs = since,
            host = host, sessionId = "session", eventId = event)

    @Test fun deniedRunningWaitingRunningAndDoneShareOnePreparation() {
        val gate = LivePostGate()
        var attempts = 0
        var posts = 0
        assertNull(gate.select(null))
        val states = listOf(
            content(LiveContent.Kind.RUNNING),
            content(LiveContent.Kind.WAITING, event = "approval-1"),
            content(LiveContent.Kind.RUNNING),
            content(LiveContent.Kind.WAITING, event = "approval-2"),
            content(LiveContent.Kind.DONE, since = null),
        )
        var first: LivePostGate.Ticket? = null
        for (state in states) {
            val ticket = gate.select(state)!!
            if (first == null) first = ticket else assertSame(first, ticket)
            gate.prepare(ticket, { it() }, { attempts++; false }, { it() }) { posts++ }
        }
        assertEquals(1, attempts)
        assertEquals(0, posts)
        val nextRun = gate.select(content(LiveContent.Kind.RUNNING, since = 200L))!!
        assertNotSame(first, nextRun)
        gate.prepare(nextRun, { it() }, { attempts++; false }, { it() }) { posts++ }
        assertEquals(2, attempts)
    }
    @Test fun clockBecomingAvailableDoesNotRepeatFailedPreparation() {
        val gate = LivePostGate()
        val unknown = gate.select(content(LiveContent.Kind.WAITING, since = null, event = "approval"))!!
        assertTrue(gate.begin(unknown))
        val known = gate.select(content(LiveContent.Kind.RUNNING, since = 100L))!!
        assertSame(unknown, known)
        assertFalse(gate.begin(known))
    }
    @Test fun inactiveWhileWaitingAndNewHostCannotReviveOld() {
        val gate = LivePostGate()
        val old = gate.select(content(LiveContent.Kind.RUNNING))!!
        val work = ArrayDeque<() -> Unit>()
        val main = ArrayDeque<() -> Unit>()
        var posts = 0
        gate.prepare(old, { work.add(it) }, { true }, { main.add(it) }) { posts++ }
        work.removeFirst()()
        gate.select(null)
        val fresh = gate.select(content(LiveContent.Kind.RUNNING))!!
        main.removeFirst()()
        assertEquals(0, posts)
        assertFalse(gate.commit(old) { posts++; true })
        gate.prepare(fresh, { it() }, { true }, { it() }) { gate.commit(fresh) { posts++; true } }
        assertEquals(1, posts)
        val otherHost = gate.select(content(LiveContent.Kind.RUNNING, host = "other"))!!
        assertNotSame(fresh, otherHost)
        assertFalse(gate.commit(fresh) { throw AssertionError("old host posted") })
    }
    @Test fun refusedCommitDoesNotReportAPost() {
        val gate = LivePostGate()
        val ticket = gate.select(content(LiveContent.Kind.RUNNING))!!
        assertFalse(gate.commit(ticket) { false })
    }
    @Test fun disabledAndEmptyLifetimesNeverPrepare() {
        val gate = LivePostGate()
        assertNull(gate.select(content(LiveContent.Kind.RUNNING), enabled = false))
        assertNull(gate.select(null))
    }
}
