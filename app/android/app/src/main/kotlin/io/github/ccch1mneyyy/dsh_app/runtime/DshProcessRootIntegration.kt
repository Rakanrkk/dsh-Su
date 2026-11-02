package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.OutputStream
import kotlin.concurrent.thread

/**
 * Root branch of [DshProcess]: spawns the staged supervisor over su, writes the
 * NUL launch frame after [RootLaunch] hello, parses stderr control events, and
 * keeps the original stdout token/ready path. Stop writes the control frame and
 * closes stdin; the supervisor owns process/cgroup cleanup and pid files.
 */
internal class DshProcessRootSession(
    private val spec: RootLaunch.Spec,
    private val onEvent: (RootLaunch.Event) -> Unit,
    private val onLog: (String, String) -> Unit,
    private val onStdout: (String) -> Unit = {},
    processOverride: Process? = null,
) {
    // Test seam: production always spawns over su; tests inject a fake Process.
    private val process = processOverride ?: ProcessBuilder(RootLaunch.launch(spec))
        .redirectErrorStream(false)
        .start()
    private val hello = java.util.concurrent.CountDownLatch(1)
    private val stderrDone = java.util.concurrent.CountDownLatch(1)
    private val stdoutDone = java.util.concurrent.CountDownLatch(1)
    @Volatile private var finalEvent: RootLaunch.Event? = null
    private var processExit: Int? = null

    @Volatile private var stopping = false
    // Launch frame and stop control share one stdin pipe: serialize writers so a
    // frame is complete before stop writes its command and EOF, and an immediate
    // stop can never close the pipe ahead of the frame.
    private val stdinLock = Any()
    @Volatile private var frameWritten = false
    private val frameWrittenLatch = java.util.concurrent.CountDownLatch(1)

    init {
        // If the frame never happens (hello timeout or spawn failure), unblock
        // stopControl so it doesn't hold a stop attempt for the full timeout.
        // The guard fires only after the full start window has passed without a
        // frame, so it never races the real writeLaunchFrame latch release.
        kotlin.concurrent.thread(name = "dsh-root-frame-guard", isDaemon = true) {
            hello.await(START_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            Thread.sleep(5000)
            if (!frameWritten) frameWrittenLatch.countDown()
        }
    }

    val alive: Boolean get() = process.isAlive

    /** The su child, for DshProcess's process field and readiness bookkeeping. */
    fun processReference(): Process = process

    fun startPumps() {
        thread(name = "dsh-root-stderr", isDaemon = true) {
            try {
                process.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        val event = RootLaunch.parseEvent(line, spec.instance)
                        if (event != null) {
                            if (event.event == "hello") hello.countDown()
                            onEvent(event)
                            if (RootLaunch.isFinal(event)) finalEvent = event
                        } else {
                            onLog(line, "stderr")
                        }
                    }
                }
            } finally {
                stderrDone.countDown()
            }
        }
        thread(name = "dsh-root-stdout", isDaemon = true) {
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        onLog(line, "stdout")
                        onStdout(line)
                    }
                }
            } finally {
                stdoutDone.countDown()
            }
        }
    }

    /** Blocks until the supervisor accepts this instance, then writes the frame. */
    fun writeLaunchFrame(guestFrame: ByteArray) {
        check(hello.await(START_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
            "root supervisor never sent hello"
        }
        synchronized(stdinLock) {
            val stdin: OutputStream = process.outputStream
            stdin.write(guestFrame)
            stdin.flush()
            frameWritten = true
        }
        frameWrittenLatch.countDown()
    }

    /** Control channel stop: frame, then EOF. No signal is sent by the App. */
    fun stopControl() {
        stopping = true
        // An immediate stop must not close the pipe before the launch frame:
        // wait for the frame writer, but do not hang forever on a dead hello.
        var waited = 0L
        while (frameWrittenLatch.count > 0L && waited < START_TIMEOUT_SECONDS * 1000L) {
            Thread.sleep(5); waited += 5
        }
        try {
            synchronized(stdinLock) {
                
                val stdin: OutputStream = process.outputStream
                if (frameWritten) {
                    stdin.write("stop\n".toByteArray(Charsets.UTF_8))
                    stdin.flush()
                }
                process.outputStream.close()
            }
        } catch (_: Exception) {
        }
    }

    /** Waits for the su child, then for both pumps so the final event is visible. */
    fun awaitExit(millis: Long): Boolean {
        if (millis == 0L) {
            process.waitFor()
        } else {
            val deadline = System.nanoTime() + millis * 1_000_000L
            while (process.isAlive && System.nanoTime() < deadline) Thread.sleep(100)
            if (process.isAlive) return false
        }
        processExit = process.exitValue()
        val pumpDeadline = System.nanoTime() + PUMP_DRAIN_NANOS
        while ((stderrDone.count > 0L || stdoutDone.count > 0L) && System.nanoTime() < pumpDeadline) Thread.sleep(25)
        return stderrDone.count == 0L && stdoutDone.count == 0L
    }

    fun exitCode(): Int? = processExit ?: if (process.isAlive) null else process.exitValue()

    fun finalStatus(): Pair<Int, String>? = finalEvent?.let { event ->
        if (!RootLaunch.isFinal(event)) return null
        val status = event.fields[0].toIntOrNull() ?: return null
        status to event.fields[1]
    }

    /** Final event, its status and the actual su exit code must agree before any reset may run. */
    fun cleanupConfirmed(): Boolean {
        val code = exitCode() ?: return false
        val event = finalEvent ?: return false
        return RootLaunch.isCleanupConfirmed(event, code)
    }

    /**
     * Unknown su termination: close the control stdin only. Destroying the su
     * child would orphan the root tree and bypass supervisor cleanup, so the
     * caller reports unknown state and lets the next locked start recover it.
     */
    fun abandonUnknown() {
        try {
            process.outputStream.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val START_TIMEOUT_SECONDS = 30L
        private val PUMP_DRAIN_NANOS = 5_000_000_000L
    }
}
