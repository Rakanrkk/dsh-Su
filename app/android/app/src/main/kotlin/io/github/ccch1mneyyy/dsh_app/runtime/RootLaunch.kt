package io.github.ccch1mneyyy.dsh_app.runtime

object RootLaunch {
    data class Bind(val host: String, val guest: String) {
        init {
            require(host.startsWith("/") && guest.startsWith("/")) { "RootLaunch: bind paths must be absolute" }
            require(host != "/" && guest != "/") { "RootLaunch: cannot bind the host or guest root" }
        }
    }
}
