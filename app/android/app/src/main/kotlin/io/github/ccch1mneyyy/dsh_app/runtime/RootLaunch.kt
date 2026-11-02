package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.File
import java.util.UUID

/**
 * Root launch planner and protocol parser (slice A). Pure JVM; the supervisor
 * script (slice B) consumes the argv and the NUL-delimited stdin frame produced
 * here. See docs/design/root-runtime.md A/B contract v1 for the wire format.
 */
object RootLaunch {
    const val PROTOCOL = 1
    const val ASSET_SCRIPT = "root-runtime/supervisor.sh"
    const val EVENT_PREFIX = "[dsh-su] 1 "
    const val DEFAULT_SU = "su"

    object Error {
        const val ARGUMENT = "ARGUMENT"
        const val PROTOCOL = "PROTOCOL"
        const val ROOT_DENIED = "ROOT_DENIED"
        const val ALREADY_RUNNING = "ALREADY_RUNNING"
        const val CGROUP2_MISSING = "CGROUP2_MISSING"
        const val CGROUP_KILL_MISSING = "CGROUP_KILL_MISSING"
        const val CGROUP_CREATE = "CGROUP_CREATE"
        const val CGROUP_JOIN = "CGROUP_JOIN"
        const val CGROUP_READ = "CGROUP_READ"
        const val UNSHARE = "UNSHARE"
        const val PROPAGATION = "PROPAGATION"
        const val MOUNT = "MOUNT"
        const val L2S_PRESENT = "L2S_PRESENT"
        const val CHROOT = "CHROOT"
        const val KILL_TIMEOUT = "KILL_TIMEOUT"
        const val CLEANUP = "CLEANUP"
    }

    data class Bind(val host: String, val guest: String) {
        init {
            require(host.startsWith("/") && guest.startsWith("/")) { "RootLaunch: bind paths must be absolute" }
            require(host != "/" && guest != "/") { "RootLaunch: cannot bind the host or guest root" }
        }
    }

    data class Spec(
        val scriptPath: String,
        val packageName: String,
        val appUid: Int,
        val instance: String,
        val rootfs: File,
        val loader: String,
        val libraryPath: String,
        val cwd: String,
        val binds: List<Bind>,
        val guestArgv: List<String>,
        val suExecutable: String = DEFAULT_SU,
    )

    data class Event(val instance: String, val event: String, val fields: List<String>, val raw: String)

    data class MountEntry(val mountPoint: String, val optionalFields: List<String>, val fsType: String) {
        val shared: Boolean get() = optionalFields.any { it.startsWith("shared:") }
        val master: Boolean get() = optionalFields.any { it.startsWith("master:") }
    }

    private val RESERVED_GUESTS = listOf("/", "/dev", "/proc", "/sys", "/system", "/apex")
    private val RESERVED_HOSTS = listOf("/", "/dev")

    fun launch(spec: Spec): List<String> {
        validate(spec)
        val flags = buildList {
            add("/system/bin/sh")
            add(spec.scriptPath)
            add("run")
            add("--protocol"); add(PROTOCOL.toString())
            add("--package"); add(spec.packageName)
            add("--app-uid"); add(spec.appUid.toString())
            add("--instance"); add(spec.instance)
            add("--rootfs"); add(spec.rootfs.canonicalPath)
            add("--loader"); add(spec.loader)
            add("--library-path"); add(spec.libraryPath)
            add("--cwd"); add(spec.cwd)
            for (bind in spec.binds) { add("--bind"); add(bind.host); add(bind.guest) }
            add("--")
        }
        val command = flags.joinToString(" ") { shellQuote(it) }
        return listOf(spec.suExecutable, "-c", "exec $command")
    }

    fun shellQuote(value: String): String {
        require(0.toChar() !in value) { "RootLaunch: NUL cannot appear in a shell argument" }
        return "'" + value.replace("'", "'\\''") + "'"
    }

    fun frame(guestArgv: List<String>): ByteArray {
        require(guestArgv.isNotEmpty()) { "RootLaunch: empty guest argv" }
        guestArgv.forEach { require(0.toChar() !in it) { "RootLaunch: NUL inside guest argument" } }
        val header = "argv " + guestArgv.size.toString() + "\n"
        val headerBytes = header.toByteArray(Charsets.UTF_8)
        val body = ArrayList<Byte>()
        guestArgv.forEachIndexed { index, arg ->
            if (index > 0) body.add(0)
            arg.toByteArray(Charsets.UTF_8).forEach { body.add(it) }
        }
        body.add(0)
        return headerBytes + body.toByteArray()
    }

    fun parseEvent(line: String, expectedInstance: String): Event? {
        if (!line.startsWith(EVENT_PREFIX)) return null
        val rest = line.removePrefix(EVENT_PREFIX)
        val parts = rest.split(' ')
        val instance = parts.getOrNull(0) ?: return null
        val event = parts.getOrNull(1) ?: return null
        if (instance != expectedInstance) return null
        val fields = if (parts.size > 2) parts.drop(2) else emptyList()
        if (event == "exit") {
            if (fields.size != 2) return null
            val status = fields[0].toIntOrNull() ?: return null
            if (status !in 0..255) return null
            if (fields[1] !in setOf("clean", "incomplete", "not-started")) return null
        }
        return Event(instance, event, fields, line)
    }

    fun isFinal(event: Event): Boolean = event.event == "exit" && event.fields.size == 2

    /** Normal success: status 0 and cleanup clean. Not sufficient for maintenance permit alone. */
    fun isCleanStop(event: Event): Boolean =
        isFinal(event) && event.fields[0] == "0" && event.fields[1] == "clean"

    /**
     * Cleanup confirmed means the reported supervisor status matches its real exit
     * code and the resource axis reported clean. A nonzero engine exit with a
     * matching status is still confirmed; only mismatched, incomplete or missing
     * final events leave recovery unknown.
     */
    fun isCleanupConfirmed(event: Event, actualExitCode: Int): Boolean {
        if (!isFinal(event)) return false
        val reported = event.fields[0].toIntOrNull() ?: return false
        return reported == actualExitCode && event.fields[1] == "clean"
    }

    fun parseMountInfo(text: String): List<MountEntry> {
        require(text.isNotEmpty()) { "RootLaunch: empty mountinfo" }
        return text.split("\n").filter { it.isNotEmpty() }.map { line ->
            val fields = line.split(' ')
            require(fields.size >= 9) { "RootLaunch: malformed mountinfo line" }
            val separator = fields.indexOf("-")
            require(separator >= 5) { "RootLaunch: mountinfo line missing separator" }
            val mountPoint = unescape(fields[4])
            val optional = fields.subList(6, separator)
            val fsType = fields[separator + 1]
            MountEntry(mountPoint, optional, fsType)
        }
    }
    fun hasPropagationMarks(text: String): Boolean = parseMountInfo(text).any { it.shared || it.master }

    fun mountsUnder(text: String, tree: String): Boolean {
        val root = tree.trimEnd('/')
        return parseMountInfo(text).any { entry ->
            entry.mountPoint == root || entry.mountPoint.startsWith(root + "/")
        }
    }

    private fun unescape(raw: String): String {
        val out = StringBuilder(raw.length)
        var at = 0
        while (at < raw.length) {
            val c = raw[at]
            if (c == '\\' && at + 3 < raw.length && raw[at + 1] in '0'..'7' && raw[at + 2] in '0'..'7' && raw[at + 3] in '0'..'7') {
                out.append(raw.substring(at + 1, at + 4).toInt(8).toChar())
                at += 4
            } else {
                out.append(c); at++
            }
        }
        return out.toString()
    }

    private fun validate(spec: Spec) {
        require(spec.packageName.isNotEmpty()) { "RootLaunch: empty package" }
        require(spec.appUid > 0) { "RootLaunch: invalid app uid" }
        require(UUID.fromString(spec.instance).toString() == spec.instance) { "RootLaunch: instance must be a canonical UUID" }
        require(spec.rootfs.isDirectory) { "RootLaunch: rootfs is not a directory" }
        require(spec.loader.startsWith('/')) { "RootLaunch: loader must be absolute" }
        require(spec.cwd.startsWith('/')) { "RootLaunch: cwd must be absolute" }
        require(spec.guestArgv.isNotEmpty()) { "RootLaunch: empty guest argv" }
        require(spec.guestArgv.first() == "/usr/bin/env") { "RootLaunch: guest argv must start at /usr/bin/env" }
        val guests = spec.binds.map { it.guest }
        require(guests.size == guests.toSet().size) { "RootLaunch: duplicate bind target" }
        for (reserved in RESERVED_GUESTS) {
            require(guests.none { it == reserved || it.startsWith(reserved + "/") }) { "RootLaunch: bind target " + reserved + " is reserved" }
        }
        for (reserved in RESERVED_HOSTS) {
            require(spec.binds.none { it.host == reserved || it.host.startsWith(reserved + "/") }) { "RootLaunch: bind source " + reserved + " is reserved" }
        }
    }
}
