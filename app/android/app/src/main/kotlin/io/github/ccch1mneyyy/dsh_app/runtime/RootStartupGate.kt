package io.github.ccch1mneyyy.dsh_app.runtime

/** One startup attempt, retained through preparation and the service's one-shot consumption. */
internal class RootStartupGate {
    enum class Kind { INSTALL, START }
    data class Ticket(val id: Long, val kind: Kind)
    private val guard = Any()
    private var sequence = 0L
    private var current: Ticket? = null
    private var prepared = false
    private var dispatched = false
    private var consumed = false

    fun begin(kind: Kind): Ticket = synchronized(guard) {
        Ticket(++sequence, kind).also {
            current = it
            prepared = false
            dispatched = false
            consumed = false
        }
    }

    fun current(ticket: Ticket): Boolean = synchronized(guard) { current == ticket }
    fun invalidate(ticket: Ticket): Boolean = synchronized(guard) {
        if (current != ticket) return false
        current = null
        prepared = false
        true
    }
    fun invalidate() = synchronized(guard) { current = null; prepared = false }

    /** The production worker/main-thread seam; cancellation is rechecked after each handoff. */
    fun prepare(
        ticket: Ticket,
        execute: (() -> Unit) -> Unit,
        deliver: (() -> Unit) -> Unit,
        preparation: (() -> Boolean) -> Boolean,
        ready: (Boolean) -> Unit,
    ) {
        execute {
            val ok = current(ticket) && runCatching { preparation { current(ticket) } }.getOrDefault(false)
            deliver {
                val accepted = synchronized(guard) {
                    if (current != ticket) false else { prepared = ok; ok }
                }
                ready(accepted)
            }
        }
    }

    /** Dispatch and cancellation share this short boundary; no root work happens under it. */
    fun dispatch(ticket: Ticket, action: () -> Unit): Boolean = synchronized(guard) {
        if (current != ticket || !prepared || dispatched) return false
        dispatched = true
        action()
        true
    }

    fun consume(id: Long, kind: Kind): Boolean = synchronized(guard) {
        if (id <= 0 || current != Ticket(id, kind) || !prepared || !dispatched || consumed) return false
        consumed = true
        true
    }
}
