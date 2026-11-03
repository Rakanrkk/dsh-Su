package io.github.ccch1mneyyy.dsh_app.phone.background

/** Fixed grants may be partially refused; backend liveness and POST readback decide startup. */
internal object RootStartupPreparation {
    fun <T> prepare(
        mayStart: () -> Boolean,
        connect: (() -> Boolean) -> T?,
        grantSelf: () -> Boolean,
        alive: (T) -> Boolean,
        notificationsGranted: () -> Boolean,
    ): T? {
        if (!mayStart()) return null
        val backend = connect(mayStart) ?: return null
        if (!mayStart() || !alive(backend)) return null
        runCatching { grantSelf() }
        return backend.takeIf { mayStart() && alive(it) && notificationsGranted() }
    }
}
