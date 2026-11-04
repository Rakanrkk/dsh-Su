package io.github.ccch1mneyyy.dsh_app.files

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class RootExportsTest {
    @Test fun copiesRootSourceIntoTheArtifactCache() = withDirectory { dir ->
        val cache = File(dir, "cache/artifacts")
        val exported = RootExports.copyToCache(
            { ByteArrayInputStream("root bytes".toByteArray()) },
            cache,
        )

        assertTrue(cache.isDirectory)
        assertEquals("root bytes", exported.readText())
        assertTrue(exported.name.startsWith("guest-artifact-"))
        assertTrue(exported.parentFile!!.canonicalFile == cache.canonicalFile)
    }

    @Test fun aFailedCopyRemovesOnlyItsPartialTarget() = withDirectory { dir ->
        val cache = File(dir, "cache/artifacts")
        val failure = IOException("root source interrupted")
        val source = object : java.io.InputStream() {
            private var reads = 0
            override fun read(): Int = throw AssertionError("bulk copy expected")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (reads++ == 0) {
                    buffer[offset] = 42
                    return 1
                }
                throw failure
            }
        }

        try {
            RootExports.copyToCache({ source }, cache)
            fail("copy should propagate the source failure")
        } catch (error: IOException) {
            assertSame(failure, error)
        }
        assertEquals(0, cache.listFiles()!!.size)
    }

    @Test fun cacheCreationFailureIsPropagatedWithoutOpeningTheSource() = withDirectory { dir ->
        val blocked = File(dir, "blocked").apply { writeText("not a directory") }
        var opened = false

        try {
            RootExports.copyToCache({ opened = true; ByteArrayInputStream(byteArrayOf()) }, blocked)
            fail("a regular file cannot contain exports")
        } catch (_: IOException) {
            assertEquals("not a directory", blocked.readText())
            assertFalse(opened)
        }
    }

    private fun withDirectory(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("root-exports-").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
}
