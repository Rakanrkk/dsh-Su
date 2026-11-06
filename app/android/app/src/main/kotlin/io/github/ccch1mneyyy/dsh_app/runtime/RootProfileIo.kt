package io.github.ccch1mneyyy.dsh_app.runtime

import android.content.Context
import com.topjohnwu.superuser.io.SuFileOutputStream
import java.io.File

/**
 * Root reads and the atomic replacement of the main profile's settings layer,
 * the rootfs file the app account cannot open (owner 0, mode 0600). Paths are
 * fixed, never caller-supplied; [files] variants exist so tests can supply a
 * plain-file [RuntimeFileAccess].
 */
internal object RootProfileIo {
    private const val RELATIVE_DIR = "root/.dsh/profiles/web"
    private const val PATCH_NAME = "cordis.patch.yml"
    /** Every main-profile mutation shares the writer lock DSH itself takes on `package.json`. */
    private const val PKG_NAME = "package.json"

    fun readMainProfilePatch(context: Context, rootfs: File): String? =
        openRootFileAccess(context).use { read(it, rootfs) }

    fun read(files: RuntimeFileAccess, rootfs: File): String? {
        val file = files.file(File(rootfs, "$RELATIVE_DIR/$PATCH_NAME"))
        if (!file.exists()) return null
        return files.readText(file)
    }

    fun writeMainProfilePatch(context: Context, rootfs: File, content: String): Boolean =
        openRootFileAccess(context).use { write(it, rootfs, content) }

    /**
     * Atomically replaces the settings layer under the shared writer lock
     * (`package.json.lock`, the same file DSH's `dsh-atomic-write` takes before it
     * touches this profile), so the patch writer, a restore and the engine's own
     * plugin manager cannot interleave. A held lock makes this return false after
     * [lockWaitMs]; a failed step leaves the original untouched. [setPrivateMode]
     * is injectable for tests; on device it runs chmod through the already-selected
     * root shell bound to the [com.topjohnwu.superuser.io.SuFile]. The temporary
     * file is resolved through [RuntimeFileAccess.file], keeping its whole
     * lifecycle on the same Root/Plain backend as [target].
     */
    fun write(
        files: RuntimeFileAccess,
        rootfs: File,
        content: String,
        setPrivateMode: (File) -> Boolean = ::setPrivateMode,
        lockWaitMs: Long = ProfileLock.DEFAULT_WAIT_MS,
        pid: () -> Long = ProfileLock::currentPid,
        alive: (Long) -> Boolean = { ProfileLock.isAlive(it) },
    ): Boolean {
        val dir = files.file(File(rootfs, RELATIVE_DIR))
        if (!dir.isDirectory) return false
        return try {
            ProfileLock.withLock(files, files.file(File(dir, PKG_NAME)), "settings layer is busy", waitMs = lockWaitMs, pid = pid, alive = alive) {
                atomicReplace(files, files.file(File(dir, PATCH_NAME)), content, setPrivateMode)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Atomically replaces [target] through a same-directory temporary file; a failed
     * step leaves the original untouched and throws, so callers can tell a busy lock
     * from an I/O failure. The temporary file is created empty and set to mode 0600
     * before content is written, so a failed mode change never exposes the bytes, and
     * a crash before the rename leaves the stale temporary for the next write to clean.
     */
    internal fun atomicReplace(
        files: RuntimeFileAccess,
        target: File,
        content: String,
        setPrivateMode: (File) -> Boolean = ::setPrivateMode,
    ) {
        // Resolve the temporary through the same access seam as [target]:
        // under the Root backend this is a SuFile (shell-backed) and under the
        // plain backend a plain file, so its exists/create/chmod/rename never
        // bypass the backend — a raw `File(parent, name)` would perform
        // app-uid filesystem calls on the rootfs path and fail under Root 600.
        val tmp = files.file(File(target.parentFile, target.name + ".dsh-app-tmp"))
        try {
            if (tmp.exists() && !files.delete(tmp, recursive = false)) {
                throw IllegalStateException("could not remove the stale temporary file ${tmp.name}")
            }
            if (!tmp.createNewFile()) throw IllegalStateException("could not create the temporary file ${tmp.name}")
            if (!setPrivateMode(tmp)) throw IllegalStateException("could not set the private mode on ${tmp.name}")
            files.writeText(tmp, content)
            if (!tmp.renameTo(target)) throw IllegalStateException("rename failed")
        } catch (e: Exception) {
            runCatching { files.delete(tmp, recursive = false) }
            throw e
        }
    }

    /** Sets the newly created temporary file to owner-only read/write. */
    internal fun setPrivateMode(file: File): Boolean =
        file.setReadable(false, false) &&
            file.setWritable(false, false) &&
            file.setExecutable(false, false) &&
            file.setReadable(true, true) &&
            file.setWritable(true, true)
}
