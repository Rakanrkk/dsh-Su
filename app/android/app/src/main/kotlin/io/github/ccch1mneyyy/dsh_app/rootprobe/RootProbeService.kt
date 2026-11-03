package io.github.ccch1mneyyy.dsh_app.rootprobe

import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService
import java.io.File
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
    private data class Instance(
        val id: String,
        val stateRoot: String,
        val process: java.lang.Process,
    )

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
                synchronized(this@RootProbeService) {
                    check(instance == null) { "container $containerId already running" }
                }
                val builder = ProcessBuilder(argv).redirectErrorStream(false)
                val p = builder.start()
                synchronized(this@RootProbeService) { instance = Instance(containerId, stateRoot, p) }
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
                    Log.i("RootProbe", "runc $containerId exited $code")
                    synchronized(this@RootProbeService) { instance = null }
                }
                return containerId
            }

            override fun stop(containerId: String, stateRoot: String): String {
                val current = synchronized(this@RootProbeService) { instance } ?: return "notRunning"
                check(current.id == containerId) { "container id mismatch" }
                val runcPath = resolveRunc()
                val kill = runCatching {
                    ProcessBuilder(listOf(runcPath, "--root", stateRoot, "kill", containerId, "KILL"))
                        .redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS)
                }.getOrDefault(-1)
                if (kill != 0) return "killFailed"
                val exited = current.process.waitFor(15, TimeUnit.SECONDS)
                if (!exited) return "timeout"
                val delete = runCatching {
                    ProcessBuilder(listOf(runcPath, "--root", stateRoot, "delete", "--force", containerId))
                        .redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS)
                }.getOrDefault(-1)
                synchronized(this@RootProbeService) { instance = null }
                return if (delete == 0) "ok" else "deleteFailed"
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