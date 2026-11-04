package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/** mountinfo field-5 parsing and the refusal to rm -rf across live mounts (incident rule 4). */
class RuntimeMountGuardTest {
    private fun tempDir(name: String): File = kotlin.io.path.createTempDirectory(name).toFile()

    // ── MountInfo.parse: field 5 is the mount point ──

    @Test fun parsesFieldFiveAsTheMountPoint() {
        val line = "36 35 98:0 /mnt1 /mnt2 rw,noatime master:1 - ext3 /dev/root rw,errors=continue"
        assertEquals(listOf("/mnt2"), MountInfo.parse(line).map { it.mountPoint })
    }

    @Test fun flagsPeerGroupMarkersInOptionalFields() {
        val shared = MountInfo.parse("90 1 8:1 / /mnt rw shared:1 - ext4 /dev rw").single()
        val master = MountInfo.parse("91 1 8:1 / /mnt2 rw master:2 - ext4 /dev rw").single()
        val plain = MountInfo.parse("92 1 8:1 / /mnt3 rw - ext4 /dev rw").single()
        assertTrue(shared.shared)
        assertFalse(shared.master)
        assertTrue(master.master)
        assertFalse(master.shared)
        assertFalse(plain.shared)
        assertFalse(plain.master)
    }

    @Test fun optionalFieldsAfterTheSeparatorAreNotReexamined() {
        val entry = MountInfo.parse("93 1 8:1 / /mnt rw - ext4 /dev shared:9").single()
        assertFalse(entry.shared)
    }

    @Test fun decodesOctalEscapesInTheMountPoint() {
        val entry = MountInfo.parse("1 2 8:1 / /mnt\\040sp\\134bs rw - ext4 /dev rw").single()
        assertEquals("/mnt sp\\bs", entry.mountPoint)
    }

    @Test fun doesNotReDecodeDecodedEscapes() {
        val entry = MountInfo.parse("1 2 8:1 / /m\\134040x rw - ext4 /dev rw").single()
        assertEquals("/m\\040x", entry.mountPoint)
    }

    @Test fun skipsShortAndEmptyLines() {
        val mounts = MountInfo.parse("\n1 2 3 4\n5 6 7:0 / /real rw - ext4 /dev rw\n")
        assertEquals(listOf("/real"), mounts.map { it.mountPoint })
    }

    // ── MountInfo.under: whole path components ──

    @Test fun underMatchesTheTreeItselfAndDescendantsOnly() {
        val mounts = listOf(
            "/data",
            "/data/local/tmp/x",
            "/data/local/tmp/x/dev",
            "/data/local/tmp-x",
            "/data/local/tmp/xy",
        ).map { MountEntry(it) }
        val found = MountInfo.under(mounts, "/data/local/tmp/x")
        assertEquals(listOf("/data/local/tmp/x", "/data/local/tmp/x/dev"), found.map { it.mountPoint })
    }

    @Test fun theRootTreeMatchesEverything() {
        val mounts = listOf(MountEntry("/a"), MountEntry("/b"))
        assertEquals(2, MountInfo.under(mounts, "/").size)
    }

    // ── MountGuard: refuse to delete through mounts ──

    @Test fun aGuardRefusesTreesWithMountsUnderThemAndDeletesNothing() {
        val base = tempDir("guard-block")
        try {
            val tree = File(base, "rootfs")
            File(tree, "root").mkdirs()
            val keep = File(tree, "root/data.txt")
            keep.writeText("user data")
            val mounts = MountGuard(emptyList(), listOf(MountEntry(File(tree, "dev").absolutePath, shared = true)))
            val e = assertThrows(IllegalStateException::class.java) { tree.deleteRecursivelySafely(mounts) }
            assertTrue(e.message!!.contains("mounted there"))
            assertTrue(keep.exists())
            assertTrue(tree.exists())
        } finally { base.deleteRecursively() }
    }

    @Test fun theMountPointOfTheTreeItselfBlocksDeletion() {
        val base = tempDir("guard-self")
        try {
            val tree = File(base, "rootfs")
            tree.mkdirs()
            val mounts = MountGuard(listOf(MountEntry(tree.absolutePath)), emptyList())
            assertThrows(IllegalStateException::class.java) { tree.deleteRecursivelySafely(mounts) }
            assertTrue(tree.exists())
        } finally { base.deleteRecursively() }
    }

    @Test fun cleanTreesDeleteAcrossBothViews() {
        val base = tempDir("guard-clean")
        try {
            val tree = File(base, "rootfs")
            File(tree, "root").mkdirs()
            val mounts = MountGuard(listOf(MountEntry("/app-only")), listOf(MountEntry("/host-only")))
            tree.deleteRecursivelySafely(mounts)
            assertFalse(tree.exists())
        } finally { base.deleteRecursively() }
    }

    @Test fun rootFileAccessDeletesEmptyDirectoriesAndDoesNotFollowSymlinks() {
        val base = tempDir("root-delete")
        try {
            val tree = File(base, "staging")
            val empty = File(tree, "empty")
            val nested = File(tree, "nested")
            assertTrue(empty.mkdirs())
            assertTrue(nested.mkdirs())
            val ordinary = File(nested, "data.txt")
            ordinary.writeText("staged")
            val target = File(base, "outside.txt")
            target.writeText("keep")
            val link = File(tree, "outside-link")
            Files.createSymbolicLink(link.toPath(), target.toPath())
            val access = object : RuntimeFileAccess {
                val recursiveDeletes = mutableSetOf<String>()
                val ordinaryDeletes = mutableSetOf<String>()
                override fun file(file: File): File = file
                override fun isSymlink(file: File): Boolean = Files.isSymbolicLink(file.toPath())
                override fun delete(file: File, recursive: Boolean): Boolean {
                    if (recursive) recursiveDeletes += file.path else ordinaryDeletes += file.path
                    return if (recursive) file.deleteRecursively() else file.delete()
                }
                override fun readText(file: File): String = file.readText()
                override fun close() {}
            }

            tree.deleteRecursivelySafely(
                MountGuard(emptyList(), emptyList(), strict = true),
                files = access,
            )

            assertFalse(tree.exists())
            assertTrue(target.exists())
            assertFalse(link.exists())
            assertTrue(access.recursiveDeletes.contains(empty.path))
            assertTrue(access.recursiveDeletes.contains(nested.path))
            assertTrue(access.recursiveDeletes.contains(tree.path))
            assertTrue(access.ordinaryDeletes.contains(ordinary.path))
        } finally { base.deleteRecursively() }
    }

    @Test fun strictBuildsRefuseWithoutAHostViewUnlessExempted() {
        val base = tempDir("guard-strict")
        try {
            val a = File(base, "a"); a.mkdirs()
            val b = File(base, "b"); b.mkdirs()
            val mounts = MountGuard(emptyList(), null, strict = true)
            val e = assertThrows(IllegalStateException::class.java) { a.deleteRecursivelySafely(mounts) }
            assertTrue(e.message!!.contains("/proc/1/mountinfo"))
            assertTrue(a.exists())
            b.deleteRecursivelySafely(mounts, allowWithoutHostProof = true)
            assertFalse(b.exists())
        } finally { base.deleteRecursively() }
    }

    @Test fun strictBuildsWithAReadableHostViewDeleteNormally() {
        val base = tempDir("guard-strict-host")
        try {
            val tree = File(base, "rootfs")
            File(tree, "root").mkdirs()
            tree.deleteRecursivelySafely(MountGuard(emptyList(), emptyList(), strict = true))
            assertFalse(tree.exists())
        } finally { base.deleteRecursively() }
    }

    @Test fun missingTreesNeedNoProofEvenInStrictBuilds() {
        val base = tempDir("guard-absent")
        try {
            val missing = File(base, "absent")
            missing.deleteRecursivelySafely(MountGuard(emptyList(), null, strict = true))
        } finally { base.deleteRecursively() }
    }
}
