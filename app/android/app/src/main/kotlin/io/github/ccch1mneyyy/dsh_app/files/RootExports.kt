package io.github.ccch1mneyyy.dsh_app.files

import java.io.File
import java.io.IOException
import java.io.InputStream

/** Copies a Root-only source into the App's controlled artifact cache. */
internal object RootExports {
    fun copyToCache(openSource: () -> InputStream, cacheRoot: File): File {
        if (!cacheRoot.isDirectory && !cacheRoot.mkdirs() && !cacheRoot.isDirectory) {
            throw IOException("cannot create artifact cache directory")
        }
        val target = File.createTempFile("guest-artifact-", ".tmp", cacheRoot)
        try {
            openSource().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return target
        } catch (error: Throwable) {
            try {
                target.delete()
            } catch (cleanup: Throwable) {
                if (cleanup !== error) error.addSuppressed(cleanup)
            }
            throw error
        }
    }
}
