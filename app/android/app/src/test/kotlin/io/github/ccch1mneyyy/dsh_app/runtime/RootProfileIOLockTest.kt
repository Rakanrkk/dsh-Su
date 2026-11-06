package io.github.ccch1mneyyy.dsh_app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [RootProfileIo.write] under the cross-process writer lock ([ProfileLock], the
 * app-side mirror of DSH's `dsh-atomic-write`): every mutation of the main
 * profile's settings layer takes `package.json.lock`, a live holder keeps other
 * writers out, a holder whose pid is gone is taken over through a claim file,
 * and every failure path releases the lock and leaves the original untouched.
 */
class RootProfileIOLockTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Plain-file seam over a real directory tree, like RootProfileIoTest's fake. */
    private inner class FakeAccess(private val rootfs: File) : RuntimeFileAccess {
        override fun file(file: File): File {
            if (file.path.startsWith(rootfs.path)) return file
            return File(rootfs, file.path.removePrefix("/"))
        }
        override fun isSymlink(file: File): Boolean = file(file).let { java.nio.file.Files.isSymbolicLink(it.toPath()) }
        override fun delete(file: File, recursive: Boolean): Boolean = file(file).let { if (recursive) it.deleteRecursively() else it.delete() }
        override fun readText(file: File): String = file(file).readText()
        override fun close() {}
    }

    /**
     * A deliberately non-identity mapping: every distinct input path resolves
     * to a flat unique name under [mirror], so any filesystem call made on a
     * raw (unresolved) `File` lands at a different absolute path than the
     * seam's resolution — a raw bypass of [RuntimeFileAccess.file] cannot pass
     * the temporary-file test below.
     */
    private inner class MappedAccess(private val mirror: File) : RuntimeFileAccess {
        private val resolved = HashMap<String, File>()

        // A stable, deliberately non-identity mapping: each distinct logical
        // input gets one deterministic slot "m<N>" under [mirror], and a slot
        // handed back through the seam maps to itself, so re-entries behave
        // exactly like the identity Root/Plain backends while raw (unresolved)
        // paths still land somewhere else entirely.
        override fun file(file: File): File {
            resolved[file.path]?.let { return it }
            resolved.values.firstOrNull { it.path == file.path }?.let { return it }
            val slot = File(mirror, "m" + (resolved.size + 1))
            resolved[file.path] = slot
            return slot
        }

        // Operations resolve only inputs the seam explicitly mapped; raw paths
        // (the writer lock, derived from the target's own path) act on
        // themselves, matching the raw lock layout of the real backends.
        private fun slotOf(file: File): File = resolved[file.path] ?: file

        override fun isSymlink(file: File): Boolean = java.nio.file.Files.isSymbolicLink(slotOf(file).toPath())
        override fun delete(file: File, recursive: Boolean): Boolean = slotOf(file).let { if (recursive) it.deleteRecursively() else it.delete() }
        override fun readText(file: File): String = slotOf(file).readText()
        override fun close() {}
    }

    private fun rootfs(): File = tmp.newFolder("rootfs")

    private fun webDir(rootfs: File): File = File(rootfs, "root/.dsh/profiles/web").apply { mkdirs() }

    private fun patchPath(rootfs: File): File = File(webDir(rootfs), "cordis.patch.yml")

    private fun lockPath(rootfs: File): File = File(webDir(rootfs), "package.json.lock")

    private fun claimPath(rootfs: File, record: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(record.toByteArray(Charsets.UTF_8))
        return File(lockPath(rootfs).path + ".takeover-" + digest.take(8).joinToString("") { "%02x".format(it) })
    }

    private fun deadPid(): Long {
        val self = ProfileLock.currentPid()
        var candidate = self + 1
        while (java.io.File("/proc/" + candidate).exists()) candidate += 1
        return candidate
    }

    @Test
    fun `write holds the lock while replacing and removes it after`() {
        val rootfs = rootfs()
        webDir(rootfs) // write() requires the settings layer directory to exist
        val access = FakeAccess(rootfs)
        assertTrue(RootProfileIo.write(access, rootfs, "one", setPrivateMode = { file ->
            assertTrue("lock must be held during the write", lockPath(rootfs).exists())
            val perms = Files.getPosixFilePermissions(lockPath(rootfs).toPath()).toSet()
            assertTrue(
                "the lock must expose no group or other access, got $perms",
                perms.intersect(setOf(PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE)).isEmpty(),
            )
            assertTrue("the lock must be owner-readable", PosixFilePermission.OWNER_READ in perms)
            RootProfileIo.setPrivateMode(file)
        }))
        assertEquals("one", patchPath(rootfs).readText())
        assertFalse(lockPath(rootfs).exists())
        assertEquals(listOf("cordis.patch.yml"), webDir(rootfs).list()!!.sorted())
    }

    @Test
    fun `write waits out a live holder and fails busy`() {
        val rootfs = rootfs()
        patchPath(rootfs).writeText("original")
        Files.write(lockPath(rootfs).toPath(), "${ProfileLock.currentPid()}\n".toByteArray())
        assertFalse(RootProfileIo.write(FakeAccess(rootfs), rootfs, "new", setPrivateMode = { true }, lockWaitMs = 400))
        assertEquals("original", patchPath(rootfs).readText())
        assertTrue("a live holder's lock must be preserved", lockPath(rootfs).exists())
        assertEquals(listOf("cordis.patch.yml", "package.json.lock"), webDir(rootfs).list()!!.sorted())
    }

    @Test
    fun `write takes over a lock whose holder pid is gone`() {
        val rootfs = rootfs()
        Files.write(lockPath(rootfs).toPath(), "${deadPid()}\n".toByteArray())
        // The injected adapter plays the kernel: the stale holder reports ESRCH-dead.
        assertTrue(RootProfileIo.write(FakeAccess(rootfs), rootfs, "after takeover", setPrivateMode = { true }, alive = { false }))
        assertEquals("after takeover", patchPath(rootfs).readText())
        assertFalse(lockPath(rootfs).exists())
    }

    @Test
    fun `write never steals a lock whose record does not parse`() {
        val rootfs = rootfs()
        patchPath(rootfs).writeText("original")
        Files.write(lockPath(rootfs).toPath(), "not-a-pid\n".toByteArray())
        assertFalse(RootProfileIo.write(FakeAccess(rootfs), rootfs, "new", setPrivateMode = { true }, lockWaitMs = 300))
        assertEquals("original", patchPath(rootfs).readText())
        assertEquals("not-a-pid\n", lockPath(rootfs).readText())
    }

    @Test
    fun `write releases the lock when the private mode step fails`() {
        val rootfs = rootfs()
        patchPath(rootfs).writeText("original")
        assertFalse(RootProfileIo.write(FakeAccess(rootfs), rootfs, "new", setPrivateMode = { false }))
        assertEquals("original", patchPath(rootfs).readText())
        assertFalse(lockPath(rootfs).exists())
        assertEquals(listOf("cordis.patch.yml"), webDir(rootfs).list()!!.sorted())
    }

    @Test
    fun `write releases the lock when the rename step fails`() {
        val rootfs = rootfs()
        patchPath(rootfs).delete()
        File(webDir(rootfs), "cordis.patch.yml").mkdirs() // blocker: rename target occupied by a directory
        assertFalse(RootProfileIo.write(FakeAccess(rootfs), rootfs, "new", setPrivateMode = { true }))
        assertTrue("the blocker must be left untouched", File(webDir(rootfs), "cordis.patch.yml").isDirectory)
        assertFalse(lockPath(rootfs).exists())
        assertFalse(File(webDir(rootfs), "cordis.patch.yml.dsh-app-tmp").exists())
    }

    @Test
    fun `concurrent writes serialize instead of clobbering`() {
        val rootfs = rootfs()
        webDir(rootfs) // both writers require the settings layer directory to exist
        val access = FakeAccess(rootfs)
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val first = Thread {
            assertTrue(
                RootProfileIo.write(access, rootfs, "first", setPrivateMode = { file ->
                    firstStarted.countDown()
                    releaseFirst.await(5, TimeUnit.SECONDS)
                    RootProfileIo.setPrivateMode(file)
                }),
            )
        }
        first.start()
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
        val second = Thread {
            // Blocks on the held lock (waitMs 10s > the held duration) and succeeds after release.
            assertTrue(RootProfileIo.write(access, rootfs, "second", setPrivateMode = { true }, lockWaitMs = 10_000))
        }
        second.start()
        releaseFirst.countDown()
        second.join(10_000)
        first.join(5_000)
        assertFalse("the second write must finish", second.isAlive)
        assertEquals("second", patchPath(rootfs).readText())
        assertFalse(lockPath(rootfs).exists())
        assertEquals(listOf("cordis.patch.yml"), webDir(rootfs).list()!!.sorted())
    }

    @Test
    fun `a stale takeover claim fails closed without deleting the lock`() {
        val rootfs = rootfs()
        patchPath(rootfs).writeText("original")
        val record = "${deadPid()}\n"
        Files.write(lockPath(rootfs).toPath(), record.toByteArray())
        Files.write(claimPath(rootfs, record).toPath(), "${deadPid()}\n".toByteArray())
        assertFalse(RootProfileIo.write(FakeAccess(rootfs), rootfs, "new", setPrivateMode = { true }, lockWaitMs = 300))
        assertEquals("original", patchPath(rootfs).readText())
        assertTrue("the pre-existing claim is not ours to delete", claimPath(rootfs, record).exists())
        assertTrue(lockPath(rootfs).exists())
    }

    @Test
    fun `liveness classification treats only ESRCH as death`() {
        // Linux kernel ABI errno values (EPERM=1, ESRCH=3); android.jar stub
        // constants are not distinguishable under the JVM test classpath.
        val eperm = 1
        val esrch = 3
        val other = esrch + 1
        assertTrue("a delivered signal is alive", ProfileLock.isAlive(100, kill = { null }))
        assertFalse("ESRCH alone is death", ProfileLock.isAlive(100, kill = { esrch }))
        assertTrue("EPERM is an uninspectable live holder", ProfileLock.isAlive(100, kill = { eperm }))
        assertTrue("any other errno is alive", ProfileLock.isAlive(100, kill = { other }))
        assertTrue("a probe that throws is alive", ProfileLock.isAlive(100, kill = { throw java.io.IOException("probe failed") }))
        assertFalse("non-positive pids are dead", ProfileLock.isAlive(0, kill = { null }))
        assertFalse(ProfileLock.isAlive(-1, kill = { null }))
    }

    @Test
    fun `an undeletable stale temporary fails closed and keeps the original`() {
        val rootfs = rootfs()
        patchPath(rootfs).writeText("original")
        File(webDir(rootfs), "cordis.patch.yml.dsh-app-tmp").apply {
            mkdirs() // directory: a plain delete only succeeds when empty
            resolve("occupied").writeText("x") // non-empty, so a plain delete must fail
        }
        assertFalse(RootProfileIo.write(FakeAccess(rootfs), rootfs, "new", setPrivateMode = { true }))
        assertEquals("original", patchPath(rootfs).readText())
        assertFalse(lockPath(rootfs).exists())
        assertTrue(File(webDir(rootfs), "cordis.patch.yml.dsh-app-tmp").isDirectory)
    }

    @Test
    fun `a dead holder takeover re-enters contention and serializes writers`() {
        val rootfs = rootfs()
        webDir(rootfs)
        patchPath(rootfs).writeText("original")
        val dead = deadPid()
        Files.write(lockPath(rootfs).toPath(), "$dead\n".toByteArray())
        val access = FakeAccess(rootfs)
        val alive: (Long) -> Boolean = { it != dead }
        val inCritical = AtomicInteger(0)
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val first = Thread {
            assertTrue(
                RootProfileIo.write(access, rootfs, "first", setPrivateMode = { file ->
                    // Entered only through a successful exclusive create (the
                    // takeover itself must not admit a writer).
                    assertEquals(1, inCritical.incrementAndGet())
                    assertTrue("the taken-over lock must be held during the write", lockPath(rootfs).exists())
                    assertEquals("${ProfileLock.currentPid()}\n", lockPath(rootfs).readText())
                    firstEntered.countDown()
                    releaseFirst.await(5, TimeUnit.SECONDS)
                    RootProfileIo.setPrivateMode(file)
                    inCritical.decrementAndGet()
                    true
                }, alive = alive),
            )
        }
        first.start()
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
        val second = Thread {
            assertTrue(
                RootProfileIo.write(access, rootfs, "second", setPrivateMode = { file ->
                    assertEquals(1, inCritical.incrementAndGet())
                    assertTrue("the successor's lock must survive the predecessor's release", lockPath(rootfs).exists())
                    assertEquals("${ProfileLock.currentPid()}\n", lockPath(rootfs).readText())
                    RootProfileIo.setPrivateMode(file)
                    inCritical.decrementAndGet()
                    true
                }, lockWaitMs = 10_000),
            )
        }
        second.start()
        releaseFirst.countDown()
        second.join(10_000)
        first.join(5_000)
        assertFalse(first.isAlive)
        assertFalse("the second write must finish", second.isAlive)
        assertEquals("second", patchPath(rootfs).readText())
        assertFalse(lockPath(rootfs).exists())
        assertEquals(listOf("cordis.patch.yml"), webDir(rootfs).list()!!.sorted())
    }

    @Test
    fun `the temporary file resolves through the non-identity access mapping`() {
        val rootfs = rootfs()
        val mirror = tmp.newFolder("mirror")
        val access = MappedAccess(mirror)
        // The settings layer exists only at its seam-resolved location, and
        // production constructs the temporary from that mapped directory —
        // so every path below starts from the mapped dir, exactly like the
        // production code does from target.parentFile.
        val mappedDir = access.file(File(rootfs, "root/.dsh/profiles/web")).apply { mkdirs() }
        // Production builds the temporary from the resolved target's parent:
        // tmp = files.file(File(target.parentFile, target.name + suffix)).
        val resolvedTarget = access.file(File(mappedDir, "cordis.patch.yml"))
        // Exactly the construction production uses: the temporary is named
        // after the resolved target's own name, under its resolved parent.
        val rawTmp = File(resolvedTarget.parentFile, resolvedTarget.name + ".dsh-app-tmp")
        // A stale temporary exists only at the seam-resolved path, so only a
        // tmp resolved through RuntimeFileAccess.file can find and remove it.
        access.file(rawTmp).writeText("stale")
        assertTrue(RootProfileIo.write(access, rootfs, "new", setPrivateMode = { true }))
        assertEquals("new", access.readText(resolvedTarget))
        assertFalse("the stale temporary must be removed through the seam", access.file(rawTmp).exists())
        assertFalse("a raw temporary path must never be touched", rawTmp.exists())
        assertEquals(
            "the mirror must hold only the settings layer and the replaced patch",
            2,
            mirror.listFiles()!!.size,
        )
    }
}
