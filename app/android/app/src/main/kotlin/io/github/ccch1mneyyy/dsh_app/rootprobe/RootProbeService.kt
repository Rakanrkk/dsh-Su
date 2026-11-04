package io.github.ccch1mneyyy.dsh_app.rootprobe

import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs runc as an OS Process inside the root service (uid 0). The App side
 * composes the bundle config and guest argv, passes PFD pipes for
 * stdin/stdout/stderr, and reuses the original pump/token-ready logic.
 * Lifecycle: one DSH instance per RootService binding; stop and onDestroy use
 * the same runc kill/delete, not raw process destroy.
 */
class RootProbeService : RootService() {
    private class Instance(
        val id: String,
        val stateRoot: String,
        val process: java.lang.Process,
    ) {
        @Volatile var exitCode: Int? = null
        val exited = CountDownLatch(1)
        var deleted = false
    }

    @Volatile private var instance: Instance? = null

    override fun onBind(intent: Intent): IBinder {
        return object : IRootProbe.Stub() {
            override fun identity(): String =
                "uid=" + android.os.Process.myUid() + " pid=" + android.os.Process.myPid()

            override fun start(
                argv: List<String>,
                containerId: String,
                stateRoot: String,
                stdin: ParcelFileDescriptor,
                stdout: ParcelFileDescriptor,
                stderr: ParcelFileDescriptor,
            ): String {
                val (p, entry) = synchronized(this@RootProbeService) {
                    check(instance == null || instance!!.exited.count == 0L) { "container ${instance?.id} already running" }
                    val runcPath = resolveRunc()
                    check(stateRoot == File(filesDir, "root-runtime-lab/runc-state").canonicalPath) {
                        "invalid root state"
                    }
                    val previous = instance
                    check(OwnedRuncStateRecovery.recover(runcPath, File(stateRoot)) { commandArgv ->
                        val command = try {
                            ProcessBuilder(commandArgv).redirectErrorStream(true).start()
                        } catch (_: Exception) {
                            return@recover OwnedRuncStateRecovery.CommandResult(-1, "")
                        }
                        val output = java.util.concurrent.atomic.AtomicReference<String?>()
                        val reader = thread(name = "runc-recovery-output", isDaemon = true) {
                            output.set(command.inputStream.bufferedReader().use { it.readText() })
                        }
                        if (!command.waitFor(10, TimeUnit.SECONDS)) {
                            command.destroyForcibly()
                            reader.join(1000)
                            return@recover OwnedRuncStateRecovery.CommandResult(-1, "")
                        }
                        reader.join(1000)
                        val captured = output.get() ?: return@recover OwnedRuncStateRecovery.CommandResult(-1, "")
                        OwnedRuncStateRecovery.CommandResult(command.exitValue(), captured)
                    }) { "owned root containers could not be recovered" }
                    previous?.deleted = true
                    val builder = ProcessBuilder(argv).redirectErrorStream(false)
                    val p = builder.start()
                    val entry = Instance(containerId, stateRoot, p)
                    instance = entry
                    p to entry
                }
                thread(name = "runc-stdin", isDaemon = true) {
                    ParcelFileDescriptor.AutoCloseInputStream(stdin).use { input ->
                        p.outputStream.use { output -> input.copyTo(output) }
                    }
                }
                thread(name = "runc-stdout", isDaemon = true) {
                    p.inputStream.use { input ->
                        ParcelFileDescriptor.AutoCloseOutputStream(stdout).use { output -> input.copyTo(output) }
                    }
                }
                thread(name = "runc-stderr", isDaemon = true) {
                    p.errorStream.use { input ->
                        ParcelFileDescriptor.AutoCloseOutputStream(stderr).use { output -> input.copyTo(output) }
                    }
                }
                thread(name = "runc-wait", isDaemon = true) {
                    val code = p.waitFor()
                    entry.exitCode = code
                    entry.exited.countDown()
                    Log.i("RootProbe", "runc $containerId exited $code")
                }
                return containerId
            }

            override fun stop(containerId: String, stateRoot: String): String {
                val current = synchronized(this@RootProbeService) {
                    val captured = instance ?: return "notRunning"
                    check(captured.id == containerId) { "container id mismatch" }
                    captured
                }
                val runcPath = resolveRunc()
                val kill = try {
                    ProcessBuilder(listOf(runcPath, "--root", stateRoot, "kill", containerId, "KILL"))
                        .redirectErrorStream(true).start()
                } catch (_: Exception) {
                    return synchronized(this@RootProbeService) {
                        if (current.deleted) exitResult(current) else "killFailed"
                    }
                }
                if (!kill.waitFor(10, TimeUnit.SECONDS)) {
                    kill.destroyForcibly()
                    return synchronized(this@RootProbeService) {
                        if (current.deleted) exitResult(current) else "killTimeout"
                    }
                }
                if (kill.exitValue() != 0) {
                    return synchronized(this@RootProbeService) {
                        if (current.deleted) exitResult(current) else "killFailed"
                    }
                }
                if (!current.exited.await(15, TimeUnit.SECONDS)) {
                    return synchronized(this@RootProbeService) {
                        if (current.deleted) exitResult(current) else "timeout"
                    }
                }
                return synchronized(this@RootProbeService) {
                    if (instance !== current && !current.deleted) return@synchronized "notRunning"
                    val status = delete(current, runcPath)
                    if (status != "ok") return@synchronized status
                    exitResult(current)
                }
            }

            override fun waitExit(containerId: String, stateRoot: String, timeoutMs: Long): String = RootProbeExitWait.run(
                timeoutMs = timeoutMs,
                capture = {
                    synchronized(this@RootProbeService) {
                        instance?.takeIf { it.id == containerId && it.stateRoot == stateRoot }
                    }
                },
                await = { current, timeout -> current.exited.await(timeout, TimeUnit.MILLISECONDS) },
                finish = { current ->
                    synchronized(this@RootProbeService) {
                        if (instance !== current && !current.deleted) return@synchronized "notRunning"
                        val status = delete(current, resolveRunc())
                        if (status != "ok") return@synchronized status
                        exitResult(current)
                    }
                },
            )

            private fun exitResult(current: Instance): String {
                val code = current.exitCode ?: current.process.exitValue()
                current.exitCode = code
                return "ok:$code"
            }

            private fun delete(current: Instance, runcPath: String): String = synchronized(this@RootProbeService) {
                if (current.deleted) return@synchronized "ok"
                val delete = try {
                    ProcessBuilder(listOf(runcPath, "--root", current.stateRoot, "delete", "--force", current.id))
                        .redirectErrorStream(true).start()
                } catch (_: Exception) {
                    return@synchronized "deleteFailed"
                }
                if (!delete.waitFor(10, TimeUnit.SECONDS)) return@synchronized "deleteTimeout"
                if (delete.exitValue() != 0) return@synchronized "deleteFailed"
                current.deleted = true
                "ok"
            }
        }
    }

    override fun onDestroy() {
        val current = instance
        if (current != null) {
            val runcPath = resolveRunc()
            runCatching {
                ProcessBuilder(listOf(runcPath, "--root", current.stateRoot, "kill", current.id, "KILL"))
                    .redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS)
            }
            runCatching {
                ProcessBuilder(listOf(runcPath, "--root", current.stateRoot, "delete", "--force", current.id))
                    .redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS)
            }
        }
        super.onDestroy()
    }

    private fun resolveRunc(): String {
        val appDir = filesDir.parentFile ?: filesDir
        return File(appDir, "files/runc/runc").canonicalPath
    }
}