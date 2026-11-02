package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import kotlin.io.path.createTempDirectory
import org.junit.Assert.*
import org.junit.Test

/**
 * Concurrency regressions for the root branch: frame-before-stop pipe ordering,
 * real pump wiring, and cleanup confirmation from final event + actual code.
 */
class DshProcessRootSessionTest {
    private class FakeStream(data: ByteArray) : InputStream() {
        protected val buf = data
        private var pos = 0
        override fun read(): Int = if (pos < buf.size) buf[pos++].toInt() and 0xFF else -1
    }

    private class FakeProcess : Process() {
        val stdin = ByteArrayOutputStream()
        val exited = CountDownLatch(1)
        @Volatile var exitCode = 0
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(b: Int) { synchronized(stdin) { stdin.write(b) } }
            override fun write(b: ByteArray, off: Int, len: Int) {
                synchronized(stdin) { stdin.write(b, off, len) }
            }
            override fun flush() {}
        }
        override fun getInputStream(): InputStream = FakeStream("engine output\n".toByteArray())
        override fun getErrorStream(): InputStream =
            FakeStream("[dsh-su] 1 a1b2c3d4-e5f6-7890-abcd-ef1234567890 hello\n[dsh-su] 1 a1b2c3d4-e5f6-7890-abcd-ef1234567890 exit 0 clean\n".toByteArray())
        override fun waitFor(): Int { exited.await(); return exitCode }
        override fun exitValue(): Int = if (exited.count == 0L) exitCode else throw IllegalStateException()
        override fun destroy() {}
    }

    private fun spec(): RootLaunch.Spec = RootLaunch.Spec(
        scriptPath = "/x/supervisor.sh", packageName = "io.test", appUid = 10000,
        instance = "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
        rootfs = createTempDirectory("root-session").toFile(),
        loader = "/rootfs/ld.so", libraryPath = "/rootfs/lib", cwd = "/root",
        binds = emptyList(), guestArgv = listOf("/usr/bin/env", "X=1", "/bin/sh"),
    )

    @Test fun immediateStopDoesNotClosePipeBeforeLaunchFrame() {
        val fake = FakeProcess()
        val session = DshProcessRootSession(spec(), onEvent = {}, onLog = { _, _ -> }, processOverride = fake)
        session.startPumps()
        val frame = RootLaunch.frame(listOf("/usr/bin/env", "X=1", "/bin/sh"))
        val writer = Thread { session.writeLaunchFrame(frame) }
        writer.start()
        writer.join()
        // Stop races the frame writer; the lock plus the frame latch must keep
        // order deterministic: frame bytes, then stop, then EOF.
        val stopThread = Thread { session.stopControl() }
        stopThread.start()
        stopThread.join(5000)
        fake.exited.countDown()
        session.awaitExit(5000)
        synchronized(fake.stdin) { fake.stdin.flush() }
        val bytes = fake.stdin.toByteArray()
        val frameEnd = frame.size
        val stop = "stop\n".toByteArray()
        assertTrue("size=" + bytes.size + " frameEnd=" + frameEnd + " stop=" + stop.size, bytes.size >= frameEnd + stop.size)
        for (i in 0 until frameEnd) assertEquals("frame byte " + i, frame[i].toInt(), bytes[i].toInt())
        for (i in stop.indices) assertEquals("stop byte " + i, stop[i].toInt(), bytes[frameEnd + i].toInt())
    }

    @Test fun pumpsRunAndCleanupConfirmedFromFinalEventAndExitCode() {
        val fake = FakeProcess()
        var stdoutSeen = false
        val session = DshProcessRootSession(
            spec(), onEvent = {}, onLog = { _, stream -> if (stream == "stdout") stdoutSeen = true },
            processOverride = fake,
        )
        session.startPumps()
        session.writeLaunchFrame(RootLaunch.frame(listOf("/usr/bin/env", "X=1", "/bin/sh")))
        fake.exited.countDown()
        val drained = session.awaitExit(5000)
        assertTrue(drained)
        assertEquals(0, session.exitCode())
        assertEquals(0 to "clean", session.finalStatus())
        assertTrue(session.cleanupConfirmed())
        assertTrue(stdoutSeen)
    }
}
