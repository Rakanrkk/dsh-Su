package io.github.ccch1mneyyy.dsh_app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RootProfileIoTest {
    /** Plain-file access; device access wraps the same paths in SuFile. */
    private class FakeAccess(private val rootfs: File) : RuntimeFileAccess {
        override fun file(file: File): File {
            if (file.path.startsWith(rootfs.path)) return file
            return File(rootfs, file.path.removePrefix("/"))
        }
        override fun isSymlink(file: File): Boolean = java.nio.file.Files.isSymbolicLink(file(file).toPath())
        override fun delete(file: File, recursive: Boolean): Boolean = file(file).delete()
        override fun readText(file: File): String = file(file).readText()
        override fun close() {}
    }

    private fun dir(rootfs: File) = File(rootfs, "root/.dsh/profiles/web")
    private fun patch(rootfs: File) = File(dir(rootfs), "cordis.patch.yml")

    @Test
    fun `missing file reads null`() {
        val base = kotlin.io.path.createTempDirectory("root-profile").toFile()
        val rootfs = File(base, "rootfs")
        assertNull(RootProfileIo.read(FakeAccess(rootfs), rootfs))
        assertFalse(patch(rootfs).exists())
    }

    @Test
    fun `write replaces content atomically`() {
        val base = kotlin.io.path.createTempDirectory("root-profile").toFile()
        val rootfs = File(base, "rootfs")
        dir(rootfs).mkdirs()
        patch(rootfs).writeText("old\n")
        assertTrue(RootProfileIo.write(FakeAccess(rootfs), rootfs, "new\n", setPrivateMode = { true }))
        assertEquals("new\n", patch(rootfs).readText())
        assertEquals(listOf("cordis.patch.yml"), dir(rootfs).list()!!.sorted())
    }

    @Test
    fun `content is written only after the private mode is set`() {
        val base = kotlin.io.path.createTempDirectory("root-profile").toFile()
        val rootfs = File(base, "rootfs")
        dir(rootfs).mkdirs()
        patch(rootfs).writeText("original\n")
        val order = mutableListOf<String>()
        val tmp = File(dir(rootfs), "cordis.patch.yml.dsh-app-tmp")
        assertTrue(
            RootProfileIo.write(FakeAccess(rootfs), rootfs, "new\n", setPrivateMode = {
                order += "mode"
                // The temporary file is still empty when the mode is set.
                check(tmp.length() == 0L)
                true
            }),
        )
        order += "write"
        assertEquals(listOf("mode", "write"), order)
        assertEquals("new\n", patch(rootfs).readText())
    }

    @Test
    fun `failed private mode leaves no temporary bytes`() {
        val base = kotlin.io.path.createTempDirectory("root-profile").toFile()
        val rootfs = File(base, "rootfs")
        dir(rootfs).mkdirs()
        patch(rootfs).writeText("original\n")
        assertFalse(RootProfileIo.write(FakeAccess(rootfs), rootfs, "new\n", setPrivateMode = { false }))
        assertEquals("original\n", patch(rootfs).readText())
        assertEquals(listOf("cordis.patch.yml"), dir(rootfs).list()!!.sorted())
    }

    @Test
    fun `write failure keeps original`() {
        val base = kotlin.io.path.createTempDirectory("root-profile").toFile()
        val rootfs = File(base, "rootfs")
        dir(rootfs).mkdirs()
        val original = patch(rootfs)
        original.writeText("original\n")
        // Rename onto an existing directory fails, exercising the failure path
        // without mocking libsu internals.
        val blocker = File(dir(rootfs), "cordis.patch.yml")
        assertTrue(blocker.delete())
        assertTrue(blocker.mkdirs())
        assertFalse(RootProfileIo.write(FakeAccess(rootfs), rootfs, "new\n", setPrivateMode = { true }))
        assertEquals(listOf("cordis.patch.yml"), dir(rootfs).list()!!.sorted())
        assertTrue(blocker.isDirectory)
        blocker.delete()
        original.writeText("original\n")
    }

    @Test
    fun `private mode clears group and other read bits`() {
        val base = kotlin.io.path.createTempDirectory("root-profile").toFile()
        val file = File(base, "cordis.patch.yml")
        file.writeText("")
        val perms = java.nio.file.Files.getPosixFilePermissions(file.toPath())
        perms.add(java.nio.file.attribute.PosixFilePermission.GROUP_READ)
        perms.add(java.nio.file.attribute.PosixFilePermission.OTHERS_READ)
        java.nio.file.Files.setPosixFilePermissions(file.toPath(), perms)
        assertTrue(RootProfileIo.setPrivateMode(file))
        val after = java.nio.file.Files.getPosixFilePermissions(file.toPath())
        assertEquals(
            setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            ),
            after,
        )
        base.deleteRecursively()
    }

    @Test
    fun `missing directory fails without creating anything`() {
        val base = kotlin.io.path.createTempDirectory("root-profile").toFile()
        val rootfs = File(base, "rootfs")
        assertFalse(RootProfileIo.write(FakeAccess(rootfs), rootfs, "new\n", setPrivateMode = { true }))
        assertFalse(File(rootfs, "root").exists())
    }
}
