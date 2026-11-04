package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reset/upgrade/delete paths refuse trees with live mounts instead of deleting through them. */
class RuntimeResetMountGuardTest {
    private fun tempDir(name: String): File = kotlin.io.path.createTempDirectory(name).toFile()

    private class RecordingFileAccess : RuntimeFileAccess {
        val touched = mutableListOf<String>()
        override fun file(file: File): File {
            touched += file.path
            return file
        }
        override fun isSymlink(file: File): Boolean {
            touched += file.path
            return Files.isSymbolicLink(file.toPath())
        }
        override fun delete(file: File, recursive: Boolean): Boolean =
            if (recursive) file.deleteRecursively() else file.delete()
        override fun readText(file: File): String {
            touched += file.path
            return file.readText()
        }
        override fun close() {}
    }

    @Test fun settleResetKeepsTheRetiredTreeWhenAMountSitsUnderIt() {
        val base = tempDir("settle-mount")
        try {
            val retired = File(base, "retired")
            File(retired, "rootfs/root").mkdirs()
            val data = File(retired, "rootfs/root/data.txt"); data.writeText("user data")
            val keep = File(base, "keep")
            val mounts = MountGuard(emptyList(), listOf(MountEntry(File(retired, "rootfs/dev").absolutePath)))
            assertThrows(IllegalStateException::class.java) { settleReset(retired, keep, mounts = mounts) }
            assertTrue(data.exists())
            assertTrue(retired.exists())
        } finally { base.deleteRecursively() }
    }

    @Test fun settleResetDeletesACleanRetiredTree() {
        val base = tempDir("settle-clean")
        try {
            val retired = File(base, "retired")
            File(retired, "rootfs/root").mkdirs()
            File(retired, "rootfs/root/data.txt").writeText("user data")
            val keep = File(base, "keep")
            settleReset(retired, keep, mounts = MountGuard(emptyList(), emptyList()))
            assertFalse(retired.exists())
        } finally { base.deleteRecursively() }
    }

    @Test fun strictSettleWithoutAHostViewRefusesAndKeepsEverything() {
        val base = tempDir("settle-strict")
        try {
            val retired = File(base, "retired")
            File(retired, "rootfs/root").mkdirs()
            val data = File(retired, "rootfs/root/data.txt"); data.writeText("user data")
            val keep = File(base, "keep")
            val mounts = MountGuard(emptyList(), null, strict = true)
            assertThrows(IllegalStateException::class.java) { settleReset(retired, keep, mounts = mounts) }
            assertTrue(data.exists())
        } finally { base.deleteRecursively() }
    }

    @Test fun activateRootfsRefusesToClearAnOldRootfsWithMountsUnderIt() {
        val base = tempDir("activate-mount")
        try {
            val old = File(base, "rootfs")
            File(old, "root").mkdirs()
            File(old, "root/data.txt").writeText("user data")
            val staging = File(base, "staging")
            File(staging, "root").mkdirs()
            File(staging, "root/staged.txt").writeText("new tree")
            val mounts = MountGuard(
                emptyList(),
                listOf(MountEntry(File(base, "rootfs.old/dev").absolutePath, master = true)),
                strict = false,
            )
            var thrown: Throwable? = null
            try {
                activateRootfs(base, "r2", sync = {}, mounts = mounts)
            } catch (t: Throwable) {
                thrown = t
            }
            assertTrue("expected a refusal, got: " + (thrown?.let { it.javaClass.simpleName + ": " + it.message } ?: "no throw"),
                thrown is IllegalStateException)
            // The rename moved the old tree to rootfs.old and staging became rootfs, so the
            // refusal fires when the final delete touches the retired tree under its new
            // name; the user's home was carried into the staged rootfs and no ready marker
            // is written.
            val refusal = thrown as? IllegalStateException
            assertTrue("expected a mount-guard refusal, got: " + thrown?.message, refusal != null)
            assertTrue(
                "refusal should name the renamed tree, got: " + refusal!!.message,
                refusal.message!!.contains("rootfs.old/dev is mounted there") ||
                    refusal.message!!.contains("rootfs.old is mounted there"),
            )
            // The user's home survived (carried into the staged rootfs), the guard refused
            // to clear the retired tree, and no ready marker was written.
            assertEquals("user data", File(base, "rootfs/root/data.txt").readText())
            assertTrue(File(base, "rootfs.old").isDirectory)
            assertFalse(File(base, "rootfs/root/staged.txt").exists())
            // The final delete runs after the ready marker, so the refused attempt leaves
            // the runtime marked installed; the next start only settles the retired tree.
            assertEquals("r2", File(base, "rootfs.version").readText().trim())
        } finally { base.deleteRecursively() }
    }

    @Test fun retireResetDeletionStillSucceedsWithoutGuardArguments() {
        // Existing callers (tests, JVM flows) keep the legacy lenient behavior.
        val base = tempDir("retire-legacy")
        try {
            val runtime = File(base, "runtime")
            File(runtime, "rootfs/root").mkdirs()
            File(runtime, "rootfs/root/data.txt").writeText("user data")
            val retired = File(base, "retired")
            val keep = File(base, "keep")
            retireRuntime(runtime, retired, keep, listOf("root/.dsh/dsh-go"))
            assertFalse(runtime.exists())
        } finally { base.deleteRecursively() }
    }

    @Test fun resetMovesKeptDataOutOfPrivateRootOwnedDirectoriesThroughRootFiles() {
        val base = tempDir("reset-private")
        try {
            val runtime = File(base, "runtime")
            val private = File(runtime, "rootfs/root/.dsh").apply { mkdirs() }
            Files.setPosixFilePermissions(File(runtime, "rootfs/root").toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
            Files.setPosixFilePermissions(private.toPath(), PosixFilePermissions.fromString("rwx------"))
            File(private, "dsh-go/ui.json").apply { parentFile!!.mkdirs(); writeText("{}") }
            val retired = File(base, "runtime.reset")
            val keep = File(base, "runtime-keep")
            val files = RecordingFileAccess()

            retireRuntime(
                runtime, retired, keep, ResetKeep.UI_AND_SKILLS.paths,
                MountGuard(emptyList(), emptyList()), files,
            )

            assertFalse(runtime.exists())
            assertFalse(retired.exists())
            assertEquals("{}", File(keep, "root/.dsh/dsh-go/ui.json").readText())
            assertTrue(files.touched.any { it.endsWith("/rootfs/root/.dsh") })
        } finally {
            base.deleteRecursively()
        }
    }

    @Test fun upgradeCarriesPrivateHomeAndDeletesOldTreeThroughRootFiles() {
        val base = tempDir("upgrade-private")
        try {
            val old = File(base, "rootfs")
            val private = File(old, "root/.private").apply { mkdirs() }
            Files.setPosixFilePermissions(File(old, "root").toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
            Files.setPosixFilePermissions(private.toPath(), PosixFilePermissions.fromString("rwx------"))
            File(private, "item").writeText("private")
            File(base, "staging/root").mkdirs()
            File(base, "staging/etc/os-release").apply { parentFile!!.mkdirs(); writeText("new") }
            val files = RecordingFileAccess()

            activateRootfs(base, "r2", sync = {}, mounts = MountGuard(emptyList(), emptyList()), rootRuntime = true, files = files)

            assertEquals("private", File(base, "rootfs/root/.private/item").readText())
            assertFalse(File(base, "rootfs.old").exists())
            assertFalse(File(base, "home.carry").exists())
            assertTrue(files.touched.any { it.endsWith("/rootfs/root") })
            assertTrue(files.touched.any { it.endsWith("/home.carry") })
        } finally {
            base.deleteRecursively()
        }
    }

    @Test fun resetRechecksTheCurrentPathAfterRetiringInsteadOfUsingTheOldSnapshot() {
        val base = tempDir("reset-fresh-mount")
        try {
            val runtime = File(base, "runtime")
            File(runtime, "rootfs/root").mkdirs()
            File(runtime, "rootfs/root/data").writeText("user data")
            val retired = File(base, "runtime.reset")
            val keep = File(base, "runtime-keep")
            var reads = 0
            val mounts = MountGuard(emptyList(), emptyList(), strict = true) {
                reads++
                if (reads == 1) MountGuard(emptyList(), emptyList(), strict = true)
                else MountGuard(
                    emptyList(),
                    listOf(MountEntry(File(retired, "rootfs/dev").absolutePath)),
                    strict = true,
                )
            }

            assertThrows(IllegalStateException::class.java) {
                retireRuntime(runtime, retired, keep, emptyList(), mounts)
            }

            assertFalse(runtime.exists())
            assertTrue(File(retired, "rootfs/root/data").exists())
            assertTrue(reads >= 2)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test fun upgradeRefusesAMountBeforeCarryingHome() {
        val base = tempDir("upgrade-preflight-mount")
        try {
            val rootfs = File(base, "rootfs")
            File(rootfs, "root/workspace").mkdirs()
            File(rootfs, "root/workspace/data").writeText("user data")
            File(base, "staging/root").mkdirs()
            val mounts = MountGuard(
                emptyList(),
                listOf(MountEntry(File(rootfs, "dev").absolutePath)),
                strict = true,
            )

            assertThrows(IllegalStateException::class.java) {
                activateRootfs(base, "r2", sync = {}, mounts = mounts)
            }

            assertTrue(File(rootfs, "root/workspace/data").exists())
            assertFalse(File(base, "home.carry").exists())
        } finally {
            base.deleteRecursively()
        }
    }

    @Test fun settleResetRefusesAMountedTreeBeforeMovingKeptPaths() {
        val base = tempDir("settle-keep-first")
        try {
            val retired = File(base, "retired")
            File(retired, "rootfs/root/.dsh/dsh-go").mkdirs()
            File(retired, "rootfs/root/.dsh/dsh-go/ui.json").writeText("{}")
            File(retired, "rootfs/dev").mkdirs()
            File(retired, "reset.keep").writeText("root/.dsh/dsh-go\n")
            val keep = File(base, "keep")
            val mounts = MountGuard(emptyList(), listOf(MountEntry(File(retired, "rootfs/dev").absolutePath)))
            assertThrows(IllegalStateException::class.java) { settleReset(retired, keep, mounts = mounts) }
            assertFalse(File(keep, "root/.dsh/dsh-go/ui.json").exists())
            assertTrue(File(retired, "rootfs/root/.dsh/dsh-go/ui.json").exists())
            assertTrue(File(retired, "rootfs/dev").exists())
        } finally { base.deleteRecursively() }
    }
}
