package io.github.ccch1mneyyy.dsh_app.notify

/** A live lifetime gets one preparation; updates coalesce until POST is explicitly recovered. */
internal class LivePostGate {
    class Ticket internal constructor()
    private data class Owner(val phone: Long?, val host: String?, val session: String?)
    private val guard = Any()
    private var active: Ticket? = null
    private var owner: Owner? = null
    private var runSince: Long? = null
    private var ending = false
    private var attempted = false

    /** Approval ids and ending's missing clock are presentation changes within the same run. */
    fun select(content: LiveContent?, phoneTask: Long? = null, enabled: Boolean = true): Ticket? = synchronized(guard) {
        if (!enabled || (content == null && phoneTask == null)) {
            active = null
            owner = null
            runSince = null
            ending = false
            attempted = false
            return null
        }
        val nextOwner = if (phoneTask != null) Owner(phoneTask, null, null) else Owner(null, content?.host, content?.sessionId)
        val nextSince = if (phoneTask == null) content?.sinceMs else null
        val nextEnding = phoneTask == null && content?.ending == true
        val newRun = phoneTask == null &&
            ((nextSince != null && runSince != null && nextSince != runSince) || (ending && !nextEnding))
        if (active == null || owner != nextOwner || newRun) {
            active = Ticket()
            owner = nextOwner
            runSince = nextSince
            attempted = false
        } else if (runSince == null && nextSince != null) {
            runSince = nextSince
        }
        ending = nextEnding
        active
    }
    fun <T> locked(action: () -> T): T = synchronized(guard) { action() }
    fun current(ticket: Ticket): Boolean = synchronized(guard) { active === ticket }
    fun begin(ticket: Ticket): Boolean = synchronized(guard) {
        if (active !== ticket || attempted) false else { attempted = true; true }
    }
    fun prepare(
        ticket: Ticket,
        execute: (() -> Unit) -> Unit,
        ensure: (() -> Boolean) -> Boolean,
        deliver: (() -> Unit) -> Unit,
        ready: () -> Unit,
    ) {
        if (!begin(ticket)) return
        execute {
            val granted = current(ticket) && runCatching { ensure { current(ticket) } }.getOrDefault(false)
            if (granted) deliver { if (current(ticket)) ready() }
        }
    }
    fun commit(ticket: Ticket, post: () -> Boolean): Boolean = synchronized(guard) {
        if (active !== ticket) false else post()
    }
}
