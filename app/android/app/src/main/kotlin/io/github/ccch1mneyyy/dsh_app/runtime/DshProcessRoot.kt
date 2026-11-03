package io.github.ccch1mneyyy.dsh_app.runtime

object DshProcessRoot {
    /** Parses a caller "host:guest" bind; a missing guest defaults to the host path. */
    fun bind(entry: String): RootLaunch.Bind {
        val parts = entry.split(":", limit = 2)
        val host = parts.getOrNull(0)?.trimEnd('/').orEmpty().ifEmpty { "/" }
        val guest = parts.getOrNull(1)?.trimEnd('/') ?: host
        return RootLaunch.Bind(host, guest)
    }
}
