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
     * Atomically replaces the settings layer through a same-directory temporary
     * file; a failed step leaves the original untouched. The temporary file is
     * created empty and set to mode 0600 before content is written, so a failed
     * mode change never exposes the bytes. [setPrivateMode] is injectable for
     * tests; on device it runs chmod through the already-selected root shell
     * bound to the [com.topjohnwu.superuser.io.SuFile].
     */
    fun write(
        files: RuntimeFileAccess,
        rootfs: File,
        content: String,
        setPrivateMode: (File) -> Boolean = ::setPrivateMode,
    ): Boolean {
        val dir = files.file(File(rootfs, RELATIVE_DIR))
        val file = files.file(File(dir, PATCH_NAME))
        if (!dir.isDirectory) return false
        val tmp = files.file(File(dir, "$PATCH_NAME.dsh-app-tmp"))
        try {
            if (tmp.exists() && !files.delete(tmp, recursive = false)) return false
            if (!tmp.createNewFile()) return false
            if (!setPrivateMode(tmp)) {
                files.delete(tmp, recursive = false)
                return false
            }
            val out = if (tmp is com.topjohnwu.superuser.io.SuFile) SuFileOutputStream.open(tmp) else tmp.outputStream()
            out.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            if (!tmp.renameTo(file)) throw IllegalStateException("rename failed")
        } catch (_: Exception) {
            runCatching { files.delete(tmp, recursive = false) }
            return false
        }
        return true
    }

    /** Sets the newly created temporary file to owner-only read/write. */
    internal fun setPrivateMode(file: File): Boolean =
        file.setReadable(false, false) &&
            file.setWritable(false, false) &&
            file.setExecutable(false, false) &&
            file.setReadable(true, true) &&
            file.setWritable(true, true)
}
