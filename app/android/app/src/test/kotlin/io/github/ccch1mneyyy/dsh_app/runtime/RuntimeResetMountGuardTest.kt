package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reset/upgrade/delete paths refuse trees with live mounts instead of deleting through them. */
class RuntimeResetMountGuardTest {
    private fun tempDir(name: String): File = kotlin.io.path.createTempDirectory(name).toFile()

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

    @Test fun keptPathsMoveOutBeforeTheGuardRefusesTheDelete() {
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
            // What the reset keeps was renamed out before the refused delete.
            assertTrue(File(keep, "root/.dsh/dsh-go/ui.json").exists())
            assertTrue(File(retired, "rootfs/dev").exists())
        } finally { base.deleteRecursively() }
    }
}
