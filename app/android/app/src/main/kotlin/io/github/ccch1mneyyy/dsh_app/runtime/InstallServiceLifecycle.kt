package io.github.ccch1mneyyy.dsh_app.runtime

/** The service owns completion only after its foreground setup actually succeeded. */
internal class InstallServiceLifecycle {
    private var active = 0L
    val hasActive: Boolean get() = active != 0L
    fun begin(ticket: Long, foreground: () -> Unit, ready: () -> Unit) {
        foreground()
        active = ticket
        ready()
    }
    fun finish(ticket: Long, done: () -> Unit): Boolean {
        if (active == 0L || ticket != active) return false
        active = 0L
        done()
        return true
    }
}
