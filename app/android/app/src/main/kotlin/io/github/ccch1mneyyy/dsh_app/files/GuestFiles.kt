package io.github.ccch1mneyyy.dsh_app.files

import android.content.Context
import android.util.Base64
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
import com.topjohnwu.superuser.io.SuFile
import com.topjohnwu.superuser.io.SuFileInputStream
import io.github.ccch1mneyyy.dsh_app.runtime.PhoneStorage
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Where a path of the local Linux environment lives on Android.
 *
 * proot maps the guest root onto the rootfs (`filesDir/runtime/rootfs`, see
 * RuntimeInstaller) and, with phone storage on, binds the shared storage at
 * `/sdcard` and `/storage` (PhoneStorage.binds). Symlinks usually hold guest
 * targets (`/usr/lib/…`), but `--link2symlink` uses host-absolute targets in
 * the rootfs's `/root/.l2s`. Rebase those targets before following each link
 * in the guest world, not by Android.
 */
object GuestFiles {
    private const val MAX_LINKS = 16

    fun rootfs(context: Context): File = File(context.filesDir, "runtime/rootfs")

    internal fun isRootfsPath(path: String): Boolean {
        if (!path.startsWith("/")) return false
        val resolved = ArrayList<String>()
        for (segment in segments(path)) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (resolved.isNotEmpty()) resolved.removeAt(resolved.size - 1)
                else -> resolved += segment
            }
        }
        return !isSharedGuestPath(guestPath(resolved))
    }

    /** Host file for an absolute guest [path]; null when it is not a readable regular file. */
    fun resolve(context: Context, path: String): File? =
        resolve(rootfs(context), path, PhoneStorage.hostRoot(), PhoneStorage.isGranted(context))

    internal fun resolve(rootfs: File, path: String, phoneRoot: String, storageGranted: Boolean = true): File? {
        if (!path.startsWith("/")) return null
        val rootPaths = listOf(rootfs.absolutePath, rootfs.canonicalPath)
        val pending = ArrayDeque(segments(path))
        val resolved = ArrayList<String>()
        var links = 0
        while (pending.isNotEmpty()) {
            val segment = pending.removeFirst()
            when (segment) {
                "", "." -> continue
                ".." -> { if (resolved.isNotEmpty()) resolved.removeAt(resolved.size - 1); continue }
            }
            resolved += segment
            val host = hostOf(rootfs, resolved, phoneRoot)
            val nio = host.toPath()
            if (!Files.isSymbolicLink(nio)) continue
            if (++links > MAX_LINKS) return null
            var target = try { Files.readSymbolicLink(nio).toString() } catch (_: Exception) { return null }
            resolved.removeAt(resolved.size - 1)
            if (target.startsWith("/")) {
                val root = rootPaths.firstOrNull { target == it || target.startsWith("$it/") }
                if (root != null) {
                    // PRoot l2s links name the host rootfs, not another guest /data/... path.
                    target = target.removePrefix(root)
                } else if (target.startsWith(phoneRoot)) {
                    val rest = pending.joinToString("/")
                    val file = if (rest.isEmpty()) File(target) else File(target, rest)
                    return file.takeIf { storageGranted && it.isFile && it.canRead() }
                }
                resolved.clear()
            }
            segments(target).reversed().forEach { pending.addFirst(it) }
        }
        val guest = guestPath(resolved)
        if (isSharedGuestPath(guest) && !storageGranted) return null
        val file = hostOf(rootfs, resolved, phoneRoot)
        return file.takeIf { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) && it.canRead() }
    }

    /**
     * Resolve a rootfs guest file with a selected root shell and copy it to an
     * App-readable cache file. The caller owns and closes [shell].
     */
    fun resolveRoot(context: Context, path: String, shell: Shell): File? {
        if (!path.startsWith("/")) return null
        val rootfs = rootfs(context)
        val rootPaths = listOf(rootfs.absolutePath, rootfs.canonicalPath)
        val resolved = resolveGuestSegments(path) { segments ->
            val guest = guestPath(segments)
            if (isSharedGuestPath(guest)) return@resolveGuestSegments null
            val candidate = suFile(hostOf(rootfs, segments, PhoneStorage.hostRoot()), shell)
            if (!candidate.isSymlink) null else readlink(shell, candidate.path).let { target ->
                val root = rootPaths.firstOrNull { target == it || target.startsWith("$it/") }
                if (root == null) target else target.removePrefix(root)
            }
        } ?: return null

        val guest = guestPath(resolved)
        val source = hostOf(rootfs, resolved, PhoneStorage.hostRoot())
        if (isSharedGuestPath(guest)) {
            if (!PhoneStorage.isGranted(context)) return null
            return source.takeIf { it.isFile && it.canRead() }
        }
        val rootFile = suFile(source, shell)
        if (!rootFile.isFile || !rootFile.canRead()) return null

        val exportCache = File(context.cacheDir, "artifacts")
        if (!exportCache.isDirectory && !exportCache.mkdirs() && !exportCache.isDirectory) {
            throw IOException("cannot create artifact cache directory")
        }
        val temporary = File.createTempFile("guest-artifact-", ".tmp", exportCache)
        try {
            SuFileInputStream.open(rootFile).use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            return temporary
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    internal fun resolveGuestSegments(
        path: String,
        linkTarget: (List<String>) -> String?,
    ): List<String>? {
        if (!path.startsWith("/")) return null
        val pending = ArrayDeque(segments(path))
        val resolved = ArrayList<String>()
        var links = 0
        while (pending.isNotEmpty()) {
            val segment = pending.removeFirst()
            when (segment) {
                "", "." -> continue
                ".." -> { if (resolved.isNotEmpty()) resolved.removeAt(resolved.size - 1); continue }
            }
            resolved += segment
            val target = linkTarget(resolved.toList()) ?: continue
            if (++links > MAX_LINKS) return null
            resolved.removeAt(resolved.size - 1)
            if (target.startsWith("/")) resolved.clear()
            segments(target).asReversed().forEach { pending.addFirst(it) }
        }
        return resolved
    }

    private fun readlink(shell: Shell, path: String): String {
        val encoded = ArrayList<String>()
        val escaped = ShellUtils.escapedString(path)
        val result = shell.newJob()
            .add("readlink $escaped | base64 | tr -d '\\n'")
            .to(encoded)
            .exec()
        if (!result.isSuccess || encoded.size != 1) throw IOException("cannot read guest symlink")
        val bytes = try {
            Base64.decode(encoded.single(), Base64.DEFAULT)
        } catch (error: IllegalArgumentException) {
            throw IOException("invalid guest symlink target", error)
        }
        if (bytes.isEmpty() || bytes.last() != '\n'.code.toByte()) throw IOException("invalid guest symlink output")
        return bytes.copyOf(bytes.size - 1).toString(Charsets.UTF_8)
    }

    private fun suFile(file: File, shell: Shell): SuFile =
        SuFile(file.path).apply { setShell(shell) }

    /** Host path of resolved guest [segments] (no symlinks left to follow in them). */
    private fun hostOf(rootfs: File, segments: List<String>, phoneRoot: String): File {
        val guest = "/" + segments.joinToString("/")
        val phone = PhoneStorage.GUEST_PATH
        return when {
            guest == phone || guest.startsWith("$phone/") -> File(phoneRoot + guest.removePrefix(phone))
            guest == "/storage" || guest.startsWith("/storage/") -> File(guest)
            else -> File(rootfs, guest.removePrefix("/"))
        }
    }

    private fun guestPath(segments: List<String>): String = "/" + segments.joinToString("/")

    private fun isSharedGuestPath(path: String): Boolean =
        path == PhoneStorage.GUEST_PATH || path.startsWith("${PhoneStorage.GUEST_PATH}/") ||
            path == "/storage" || path.startsWith("/storage/")

    private fun segments(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }
}
