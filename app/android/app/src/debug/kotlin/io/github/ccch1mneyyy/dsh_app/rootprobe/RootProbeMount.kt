package io.github.ccch1mneyyy.dsh_app.rootprobe

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import java.io.File
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * Composes the DSH guest argv from the runtime manifest, patches the OCI
 * config.json template (from the already-passed runc experiment), and drives
 * runc via the root service. Token-ready detection reuses the same regex as
 * [io.github.ccch1mneyyy.dsh_app.runtime.DshProcess], with redaction.
 */
fun mountRootProbe(activity: android.app.Activity) {
    val shell = Shell.getShell()
    if (!shell.isRoot()) {
        Log.e("RootProbe", "FAIL no root shell (status=${shell.getStatus()})")
        return
    }
    val intent = Intent(activity, RootProbeService::class.java)
    val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val probe = IRootProbe.Stub.asInterface(binder)
            thread(name = "root-probe-launch", isDaemon = true) {
                try {
                    launchDsh(probe, activity)
                } catch (e: Exception) {
                    Log.e("RootProbe", "FAIL launch", e)
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            Log.w("RootProbe", "service disconnected (client death -> root service recycled)")
        }
    }
    RootService.bind(intent, conn)
}

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
    "cgroupsPath": "dsh-runc-experiment"
  }
}
""".trimIndent()

private fun launchDsh(probe: IRootProbe, activity: android.app.Activity) {
    val context = activity.applicationContext
    val files = context.filesDir
    val rootfs = File(files, "runtime/rootfs")
    check(rootfs.isDirectory) { "rootfs not unpacked at ${rootfs.path}" }
    val runc = File(files, "root-runtime-lab/runc.amd64")
    check(runc.isFile && runc.canExecute()) { "runc missing at ${runc.path}" }
    val bundle = File(files, "root-runtime-lab/bundle")
    val stateRoot = File(files, "root-runtime-lab/runc-state")
    bundle.mkdirs(); stateRoot.mkdirs()
    val containerId = "dsh-" + java.util.UUID.randomUUID().toString().substring(0, 12)

    val manifest = io.github.ccch1mneyyy.dsh_app.runtime.RuntimeManifest.load(context)
    val port = 3080 + (0..50).random()
    val tmp = File(context.cacheDir, "root-tmp").apply { mkdirs() }
    val vars = mapOf(
        "{NATIVE_LIB_DIR}" to context.applicationInfo.nativeLibraryDir,
        "{ROOTFS}" to rootfs.canonicalPath,
        "{TMP}" to tmp.absolutePath,
        "{FILES_DIR}" to files.absolutePath,
        "{PORT}" to port.toString(),
    )
    fun expand(s: String) = vars.entries.fold(s) { acc, (k, v) -> acc.replace(k, v) }

    // Guest argv: expand manifest.launchArgv, drop the proot prefix up to /usr/bin/env.
    val effectiveEnv = linkedMapOf<String, String>("TZ" to java.util.TimeZone.getDefault().id)
    val guestArgv = ArrayList<String>()
    var guestEnvPlaced = false
    for (rawArg in manifest.launchArgv) {
        if (rawArg == io.github.ccch1mneyyy.dsh_app.runtime.DshProcess.GUEST_ENV) {
            effectiveEnv.forEach { (k, v) -> guestArgv += "$k=$v" }
            guestEnvPlaced = true
        } else {
            guestArgv += expand(rawArg)
        }
    }
    check(guestEnvPlaced) { "guest env marker missing" }
    val envAt = guestArgv.indexOf("/usr/bin/env")
    check(envAt >= 0) { "guest argv does not start the engine at /usr/bin/env" }
    val engineArgv = guestArgv.drop(envAt)

    // Patch the passed template via JSONObject (no manual string escaping).
    val config = JSONObject(CONFIG_TEMPLATE)
    val process = config.getJSONObject("process")
    val argsJson = org.json.JSONArray()
    engineArgv.forEach { argsJson.put(it) }
    process.put("args", argsJson)
    val envJson = org.json.JSONArray()
    effectiveEnv.forEach { (k, v) -> envJson.put("$k=$v") }
    process.put("env", envJson)
    process.put("cwd", "/root")
    config.getJSONObject("root").put("path", rootfs.canonicalPath)
    config.getJSONObject("linux").put("cgroupsPath", containerId)
    File(bundle, "config.json").writeText(config.toString(2))
    Log.i("RootProbe", "bundle config written, containerId=$containerId")

    val runcArgv = listOf(
        runc.canonicalPath,
        "--root", stateRoot.canonicalPath,
        "run",
        "--bundle", bundle.canonicalPath,
        "--no-new-keyring",
        containerId,
    )
    Log.i("RootProbe", "runc argv=${runcArgv.joinToString(" ")}")

    val appStdin = ParcelFileDescriptor.createPipe()
    val appStdout = ParcelFileDescriptor.createPipe()
    val appStderr = ParcelFileDescriptor.createPipe()
    probe.start(runcArgv, containerId, stateRoot.canonicalPath, appStdin[0], appStdout[1], appStderr[1])
    appStdin[0].close(); appStdout[1].close(); appStderr[1].close()

    // Token detection on stdout: reuse the DshProcess regex, redact before logging.
    val tokenRegex = Regex("""https?://[^\s'"]*[?&#]token=[A-Za-z0-9._~%-]+""")
    val tokenParam = Regex("""[?&#]token=([A-Za-z0-9._~%-]+)""")
    val secretKey = Regex("(?i)(key|token|secret|password)")
    fun redact(arg: String): String {
        val eq = arg.indexOf('=')
        return if (eq <= 0) arg else if (secretKey.containsMatchIn(arg.substring(0, eq))) arg.substring(0, eq) + "=***" else arg
    }
    thread(name = "root-probe-stdout", isDaemon = true) {
        ParcelFileDescriptor.AutoCloseInputStream(appStdout[0]).bufferedReader().useLines { lines ->
            for (line in lines) {
                val match = tokenRegex.find(line)
                if (match != null) {
                    val token = tokenParam.find(match.value)?.groupValues?.get(1)
                    Log.i("RootProbe", "READY port=$port token=${token?.take(6)}...")
                } else {
                    Log.i("RootProbe.Stdout", line.take(200).replace(Regex("""token=[A-Za-z0-9._~%-]+"""), "token=***"))
                }
            }
        }
    }
    thread(name = "root-probe-stderr", isDaemon = true) {
        ParcelFileDescriptor.AutoCloseInputStream(appStderr[0]).bufferedReader().useLines { lines ->
            for (line in lines) Log.i("RootProbe.Stderr", line.take(200))
        }
    }
    thread(name = "root-probe-stop", isDaemon = true) {
        Thread.sleep(60_000)
        Log.i("RootProbe", "sending stop $containerId")
        probe.stop(containerId, stateRoot.canonicalPath)
    }
}