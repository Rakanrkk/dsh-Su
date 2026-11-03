package io.github.ccch1mneyyy.dsh_app.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import io.github.ccch1mneyyy.dsh_app.phone.background.RootCommands
import io.github.ccch1mneyyy.dsh_app.rootprobe.IRootProbe
import java.io.File
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

/**
 * runc-backed root branch of [DshProcess]: reuses the composed manifest guest
 * argv, patches the passed OCI template via JSONObject, and drives runc through
 * the [IRootProbe] RootService (uid 0). App-side pumps read stdout/stderr via
 * ParcelFileDescriptor pipes; token-ready detection reuses [DshProcess].
 */
internal object DshRuncClient {
    private const val TAG = "DshRuncClient"
    private const val RUNC_REL = "runc/runc"
    private const val BUNDLE_REL = "root-runtime-lab/bundle"
    private const val STATE_REL = "root-runtime-lab/runc-state"

    private val CONFIG_TEMPLATE = """
{
  "ociVersion": "1.0.2",
  "process": {
    "terminal": false,
    "user": { "uid": 0, "gid": 0 },
    "args": ["/bin/sh","-c","true"],
    "env": ["PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"],
    "cwd": "/",
    "noNewPrivileges": false
  },
  "root": { "path": "/data/data/io.github.rakanrkk.dsh_su/files/runtime/rootfs", "readonly": false },
  "mounts": [
    { "destination": "/dev", "type": "tmpfs", "source": "tmpfs", "options": ["nosuid","noexec","mode=755"] },
    { "destination": "/proc", "type": "proc", "source": "proc", "options": ["nosuid","noexec","nodev"] },
    { "destination": "/dev/pts", "type": "devpts", "source": "devpts", "options": ["nosuid","noexec","newinstance","ptmxmode=0666","mode=0620"] },
    { "destination": "/dev/shm", "type": "tmpfs", "source": "tmpfs", "options": ["nosuid","noexec","nodev","mode=1777","size=16m"] },
    { "destination": "/sys", "type": "sysfs", "source": "sysfs", "options": ["nosuid","noexec","nodev","ro"] }
  ],
  "linux": {
    "rootfsPropagation": "private",
    "namespaces": [ { "type": "mount" } ],
    "cgroupsPath": "dsh-runc-template"
  }
}
""".trimIndent()

    /** One bound DSH instance; kept until stop()/App death releases the RootService. */
    internal class Session(
        private val context: Context,
        private val port: Int,
        val onLog: (String, String) -> Unit,
        val onStdout: (String) -> Unit,
    ) {
        private val files = context.filesDir
        val runc = File(files, RUNC_REL)
        val bundle = File(files, BUNDLE_REL)
        val stateRoot = File(files, STATE_REL)
        val containerId = "dsh-" + java.util.UUID.randomUUID().toString().substring(0, 12)

        /** Re-stage when the asset version differs from what we previously extracted. */
        private fun stageRuncIfNeeded(onFailure: (Throwable) -> Unit) {
            val versionStamp = File(runc.parentFile, "version")
            var assetBytes: ByteArray
            try {
                context.assets.open("runtime/root-runtime/runc").use { input -> assetBytes = input.readBytes() }
            } catch (error: Exception) {
                if (runc.isFile && runc.canExecute()) return
                onFailure(IllegalStateException("runc asset not found: ${error.message}"))
                return
            }
            val assetHash = java.security.MessageDigest.getInstance("SHA-256").digest(assetBytes).joinToString("") { "%02x".format(it) }
            val currentHash = runCatching {
                if (runc.isFile) java.security.MessageDigest.getInstance("SHA-256").digest(runc.readBytes()).joinToString("") { "%02x".format(it) } else null
            }.getOrNull()
            if (runc.isFile && runc.canExecute() && currentHash == assetHash && versionStamp.isFile) return
            val dir = runc.parentFile ?: run { onFailure(IllegalStateException("runc parent null")); return }
            dir.mkdirs()
            val target = File(dir, "runc.new")
            java.io.FileOutputStream(target).use { output -> output.write(assetBytes) }
            target.setExecutable(true, false)
            if (!target.renameTo(runc)) {
                target.delete()
                onFailure(IllegalStateException("runc staging rename failed"))
                return
            }
            versionStamp.writeText(assetHash)
        }

        @Volatile private var probe: IRootProbe? = null
        @Volatile private var fallbackRootShell: Shell? = null
        @Volatile private var released = false
        private val bound = java.util.concurrent.CountDownLatch(1)
        private val exitLatch = java.util.concurrent.CountDownLatch(1)
        private val remoteExitReported = java.util.concurrent.atomic.AtomicBoolean(false)
        private val mainHandler = Handler(Looper.getMainLooper())
        // Guards only the remote p.start submission+publication window against kill();
        // never hold it across binder waits, prepare work or file IO.
        private val startGate = Any()
        private var startCancelled = false
        @Volatile private var started = false
        @Volatile private var exited = false
        @Volatile private var disconnected = false
        @Volatile private var remoteExitCode: Int? = null
        private var appStdin: ParcelFileDescriptor? = null
        @Volatile private var connection: ServiceConnection? = null

        private data class RootShellSelection(val shell: Shell, val useDefaultBinding: Boolean)

        /** Takes the fallback shell ownership once; the caller that gets non-null must close it. */
        private fun reclaimFallbackShell(): Shell? {
            val shell = fallbackRootShell
            fallbackRootShell = null
            return shell
        }

        // Never on the main thread: waitAndClose waits for pending shell tasks.
        private fun closeRootShell(shell: Shell) {
            Shell.EXECUTOR.execute {
                runCatching { shell.waitAndClose() }.onFailure {
                    Log.w(TAG, "root shell close failed: ${it.javaClass.simpleName}")
                    runCatching { shell.close() }
                }
            }
        }

        private fun cancelPendingStart() {
            exited = true
            exitLatch.countDown()
            bound.countDown()
        }

        private fun selectRootShell(): RootShellSelection? {
            // Only a normally returned non-root main shell may fall back to candidates. If
            // getShell() throws, MainShell.isInitMain stays true and a private Builder.build()
            // would cache its shell as the global main shell (MainShell.setCached guards on
            // isInitMain), so any further shell work here is a terminal error.
            val shell = Shell.getShell()
            if (shell.isRoot()) return RootShellSelection(shell, true)
            Log.i(TAG, "default root shell status=${shell.status}; trying absolute su candidates")
            for (path in RootCommands.suEntries.filter { it.startsWith('/') }) {
                val candidate = try {
                    Shell.Builder.create()
                        .setContext(context.applicationContext)
                        .setCommands(path)
                        .build()
                } catch (error: Exception) {
                    Log.w(TAG, "root shell candidate failed path=$path error=${error.javaClass.simpleName}")
                    continue
                }
                if (candidate.isRoot()) {
                    Log.i(TAG, "root shell candidate selected path=$path status=${candidate.status}")
                    return RootShellSelection(candidate, false)
                }
                Log.w(TAG, "root shell candidate is not root path=$path status=${candidate.status}")
                closeRootShell(candidate)
            }
            return null
        }

        fun bind(onReady: () -> Unit, onFailure: (Throwable) -> Unit) {
            var staged = true
            stageRuncIfNeeded { staged = false; cancelPendingStart(); onFailure(it) }
            if (!staged) return
            check(runc.isFile && runc.canExecute()) { "runc missing at ${runc.path}" }
            val selection = try {
                selectRootShell()
            } catch (error: Exception) {
                Log.w(TAG, "default root shell initialization failed: ${error.javaClass.simpleName}")
                cancelPendingStart()
                onFailure(IllegalStateException("no root shell"))
                return
            }
            if (selection == null) {
                cancelPendingStart()
                onFailure(IllegalStateException("no root shell"))
                return
            }
            val intent = Intent(context, io.github.ccch1mneyyy.dsh_app.rootprobe.RootProbeService::class.java)
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    if (released || connection !== this) {
                        // Late binder after release/unbind: libsu kept conn in pendingTasks,
                        // so drop it here or a stopped start would come up.
                        runCatching { RootService.unbind(this) }
                        return
                    }
                    probe = IRootProbe.Stub.asInterface(binder)
                    bound.countDown()
                    onReady()
                }
                override fun onServiceDisconnected(name: ComponentName) {
                    if (connection !== this) return
                    disconnected = true
                    onRemoteExit(-1)
                    onLog("[dsh-app] runc root service disconnected", "system")
                    release()
                }
            }
            connection = conn
            if (!selection.useDefaultBinding) fallbackRootShell = selection.shell
            mainHandler.post {
                if (released || connection !== conn) {
                    val orphan = reclaimFallbackShell()
                    if (orphan != null) closeRootShell(orphan)
                    return@post
                }
                if (selection.useDefaultBinding) {
                    RootService.bind(intent, conn)
                } else {
                    fun failBootstrap(error: Exception) {
                        // Late failure after cancel/release: release owns cleanup and the
                        // start is already reported cancelled, so do not fire a second exit.
                        if (released || connection !== conn) return
                        onLog("[dsh-app] root service bootstrap failed: ${error.javaClass.simpleName}", "system")
                        runCatching { RootService.unbind(conn) }
                        connection = null
                        val shell = reclaimFallbackShell()
                        if (shell != null) closeRootShell(shell)
                        cancelPendingStart()
                        onFailure(IllegalStateException("root service bootstrap failed"))
                    }
                    try {
                        val callbackExecutor = java.util.concurrent.Executor { callback -> mainHandler.post(callback) }
                        val task = RootService.bindOrTask(intent, callbackExecutor, conn)
                        if (task != null) Shell.EXECUTOR.execute {
                            try {
                                selection.shell.execTask(task)
                            } catch (error: Exception) {
                                mainHandler.post { failBootstrap(error) }
                            }
                        }
                    } catch (error: Exception) {
                        failBootstrap(error)
                    }
                }
            }
        }

        /** Composes the OCI config and starts runc; blocks until the runc process spawns. */
        fun start(engineArgv: List<String>, env: Map<String, String>, binds: List<RootLaunch.Bind> = emptyList(), onFailure: (Throwable) -> Unit) {
            // release/cancel count this latch down to wake a waiter here.
            bound.await()
            if (released) { onFailure(IllegalStateException("rootCancelled")); return }
            val p = probe ?: run { onFailure(IllegalStateException("root service not bound")); return }
            bundle.mkdirs(); stateRoot.mkdirs()
            val config = JSONObject(CONFIG_TEMPLATE)
            val proc = config.getJSONObject("process")
            val argsJson = JSONArray(); engineArgv.forEach { argsJson.put(it) }
            proc.put("args", argsJson)
            val envJson = JSONArray(); env.forEach { (k, v) -> envJson.put("$k=$v") }
            proc.put("env", envJson)
            proc.put("cwd", "/root")
            val rootfs = File(files, "runtime/rootfs")
            config.getJSONObject("root").put("path", rootfs.canonicalPath)
            config.getJSONObject("linux").put("cgroupsPath", containerId)
            // rbind keeps /storage's FUSE submounts visible; rprivate stops the
            // container's mounts from propagating back to the host.
            val mounts = config.getJSONArray("mounts")
            for (entry in binds) {
                mounts.put(
                    JSONObject()
                        .put("destination", entry.guest)
                        .put("type", "bind")
                        .put("source", entry.host)
                        .put("options", JSONArray().put("rbind").put("rprivate")),
                )
            }
            File(bundle, "config.json").writeText(config.toString(2))
            val runcArgv = listOf(
                runc.canonicalPath,
                "--root", stateRoot.canonicalPath,
                "run",
                "--bundle", bundle.canonicalPath,
                "--no-new-keyring",
                containerId,
            )
            if (released) { onFailure(IllegalStateException("rootCancelled")); return }
            val appStdin = ParcelFileDescriptor.createPipe()
            val appStdout = ParcelFileDescriptor.createPipe()
            val appStderr = ParcelFileDescriptor.createPipe()
            synchronized(startGate) {
                if (released || startCancelled) {
                    runCatching { appStdin[0].close() }; runCatching { appStdin[1].close() }
                    runCatching { appStdout[0].close() }; runCatching { appStdout[1].close() }
                    runCatching { appStderr[0].close() }; runCatching { appStderr[1].close() }
                    onFailure(IllegalStateException("rootCancelled"))
                    return
                }
                p.start(runcArgv, containerId, stateRoot.canonicalPath, appStdin[0], appStdout[1], appStderr[1])
                // Publish stdin together with started so kill() gating on started sees both.
                this.appStdin = appStdin[1]
                started = true
            }
            appStdin[0].close(); appStdout[1].close(); appStderr[1].close()
            thread(name = "runc-stdout-pump", isDaemon = true) {
                ParcelFileDescriptor.AutoCloseInputStream(appStdout[0]).bufferedReader().useLines { lines ->
                    for (line in lines) {
                        onLog(line, "stdout")
                        onStdout(line)
                    }
                }
            }
            thread(name = "runc-stderr-pump", isDaemon = true) {
                ParcelFileDescriptor.AutoCloseInputStream(appStderr[0]).bufferedReader().useLines { lines ->
                    for (line in lines) onLog(line, "stderr")
                }
            }
        }

        /** Writes raw bytes to the runc stdin pipe (currently only used for stop). */
        fun writeStdin(bytes: ByteArray) {
            val fd: ParcelFileDescriptor? = appStdin
            if (fd != null) {
                val stream = ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(fd.fileDescriptor))
                try {
                    stream.write(bytes)
                    stream.flush()
                } finally {
                    stream.close()
                }
            }
        }

        /** Closes stdin → runc sees EOF → the engine receives SIGTERM via container shutdown. */
        fun stopStdin() {
            runCatching { appStdin?.close() }
            appStdin = null
        }

        val isAlive: Boolean
            get() = started && !exited

        val lastExitCode: Int?
            get() = remoteExitCode

        fun awaitRemoteExit(): Int {
            while (exitLatch.count > 0) {
                if (disconnected) {
                    onRemoteExit(-1)
                    break
                }
                val current = probe
                if (started && current != null) {
                    try {
                        val result = current.waitExit(containerId, stateRoot.canonicalPath, 1000)
                        if (result.startsWith("ok:")) {
                            val code = result.substringAfter(':').toIntOrNull()
                            if (code != null) onRemoteExit(code) else onRemoteExit(-1)
                        } else if (result == "notRunning") {
                            onLog("[dsh-app] runc exit unavailable: $result", "system")
                            onRemoteExit(-1)
                        } else if (result != "timeout") {
                            onLog("[dsh-app] runc exit unavailable: $result", "system")
                            onRemoteExit(-1)
                        }
                    } catch (error: Exception) {
                        onLog("[dsh-app] runc exit unavailable: ${error.javaClass.simpleName}", "system")
                        onRemoteExit(-1)
                    }
                } else {
                    exitLatch.await(250, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
            }
            return remoteExitCode ?: -1
        }

        fun onRemoteExit(code: Int) {
            if (!remoteExitReported.compareAndSet(false, true)) return
            remoteExitCode = code
            exited = true
            exitLatch.countDown()
            onLog("[dsh-app] runc exited with code $code", "system")
        }

        fun release() {
            val current = connection
            connection = null
            released = true
            bound.countDown()
            mainHandler.post {
                // Unbind stays on main; reclaim the shell once and hand closing to
                // Shell.EXECUTOR so an unbind failure cannot block or leak it.
                if (current != null) runCatching { RootService.unbind(current) }
                val shell = reclaimFallbackShell()
                if (shell != null) closeRootShell(shell)
            }
        }

        /** runc kill + delete; the result is the Service's stop status. */
        fun kill(): String {
            val active: IRootProbe?
            synchronized(startGate) {
                startCancelled = true
                val current = probe
                active = if (current != null && started) current else {
                    // Nothing submitted remotely yet: cancel the waiters and tear down
                    // what exists (posted unbind + orphan shell close); the status stays
                    // "notBound" honestly, and start() aborts at its gated submit check.
                    cancelPendingStart()
                    release()
                    null
                }
            }
            val probe = active ?: return "notBound"
            val status = try {
                probe.stop(containerId, stateRoot.canonicalPath)
            } catch (error: Exception) {
                onLog("[dsh-app] runc stop failed: ${error.message}", "system")
                "remoteError"
            }
            stopStdin()
            if (status.startsWith("ok:")) {
                val code = status.substringAfter(':').toIntOrNull()
                onRemoteExit(code ?: -1)
                return if (code == null) "unknown" else "ok"
            }
            return status
        }
    }
}
