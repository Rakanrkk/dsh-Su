package io.github.ccch1mneyyy.dsh_app.rootprobe

import io.github.ccch1mneyyy.dsh_app.phone.Json
import java.io.File

internal object OwnedRuncStateRecovery {
    data class CommandResult(val exitCode: Int, val output: String)

    fun recover(
        runcPath: String,
        stateRoot: File,
        parse: (String) -> Set<String>? = ::parseList,
        run: (List<String>) -> CommandResult,
    ): Boolean {
        if (!stateRoot.isDirectory) return true
        val root = stateRoot.canonicalPath
        val list = run(listOf(runcPath, "--root", root, "list", "--format", "json"))
        if (list.exitCode != 0) return false
        val listed = parse(list.output) ?: return false
        val directories = stateRoot.listFiles()?.filter(File::isDirectory) ?: return false
        val directoryIds = directories.map { it.name }.toSet()
        if (directories.any { !isOwnedId(it.name) }) return false
        if (listed.any { !isOwnedId(it) } || listed != directoryIds) return false

        for (id in listed) {
            val deleted = run(listOf(runcPath, "--root", root, "delete", "--force", id))
            if (deleted.exitCode != 0 || File(stateRoot, id).exists()) return false
        }
        val finalList = run(listOf(runcPath, "--root", root, "list", "--format", "json"))
        val remainingIds = finalList.takeIf { it.exitCode == 0 }?.let { parse(it.output) } ?: return false
        val remainingDirectories = stateRoot.listFiles() ?: return false
        return remainingIds.isEmpty() && remainingDirectories.isEmpty()
    }

    internal fun parseList(output: String): Set<String>? {
        val decoded = try {
            Json.parse(output)
        } catch (_: Exception) {
            return null
        }
        if (decoded == null) return emptySet()
        val rows = decoded as? List<*> ?: return null
        return buildSet {
            for (value in rows) {
                val row = value as? Map<*, *> ?: return null
                val id = row["id"] as? String ?: return null
                val status = row["status"] as? String ?: return null
                if (id.isBlank() || status.isBlank() || !add(id)) return null
            }
        }
    }

    private fun isOwnedId(id: String): Boolean = id.matches(Regex("dsh-[0-9a-f]{8}-[0-9a-f]{3}"))
}

internal object RootProbeStartResult {
    const val RECOVERY_REJECTED = "notSubmitted:ownedRecovery"

    fun <T : Any> runIfRecovered(recovered: Boolean, run: () -> T): T? =
        if (recovered) run() else null

    fun cleanupStatus(response: String?): String? =
        if (response == RECOVERY_REJECTED) "notSubmitted" else null
}

internal object RootProbeExitWait {
    fun <T> run(
        timeoutMs: Long,
        capture: () -> T?,
        await: (T, Long) -> Boolean,
        finish: (T) -> String,
    ): String {
        val current = capture() ?: return "notRunning"
        if (!await(current, timeoutMs)) return "timeout"
        return finish(current)
    }
}
