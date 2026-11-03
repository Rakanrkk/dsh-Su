package io.github.ccch1mneyyy.dsh_app.runtime

import android.content.Context
import io.github.ccch1mneyyy.dsh_app.BuildConfig
import android.os.Process
import java.io.File
import java.util.UUID

/**
 * Fixed root branch plan for [DshProcess]: reuses the already-composed manifest
 * guest argv, stages the App-owned supervisor copy, and stages once per instance.
 * No PRoot bind translation and no process-tree mutation.
 */
object DshProcessRoot {
    const val SU = "su"

    fun plan(
        context: Context,
        manifest: RuntimeManifest,
        rootfs: File,
        guestArgv: List<String>,
        extraBinds: List<String>,
    ): RootLaunch.Spec {
        check(BuildConfig.ROOT_RUNTIME) { "root launch requires the ROOT_RUNTIME build" }
        val instance = UUID.randomUUID().toString()
        val abi = manifest.abi
        val script = stageScript(context, instance)
        return RootLaunch.Spec(
            scriptPath = script.path,
            packageName = context.packageName,
            appUid = Process.myUid(),
            instance = instance,
            rootfs = rootfs,
            loader = File(rootfs, DirectLaunch.loaderPath(abi)).path,
            libraryPath = DirectLaunch.libraryDirectories(abi).joinToString(":") { File(rootfs, it).path },
            cwd = guestCwd(guestArgv, rootfs),
            binds = extraBinds.mapNotNull { bind(it) },
            guestArgv = guestArgv,
            suExecutable = SU,
        )
    }

    /** Root builds stage the fixed asset once per instance; the pid file belongs to root. */
    fun stageScript(context: Context, instance: String): File {
        val dir = File(context.filesDir, "root/" + instance).apply { mkdirs() }
        check(dir.isDirectory && dir.setReadable(true, true) && dir.setWritable(true, true) && dir.setExecutable(true, true)) {
            "root script stage is not private"
        }
        val target = File(dir, "supervisor.sh")
        context.assets.open(RootLaunch.ASSET_SCRIPT).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        check(target.setExecutable(true, true)) { "root script is not executable" }
        return target
    }

    /**
     * A single extra "host:guest" bind, same source format as the PRoot launch.
     * "/sdcard" maps to the FUSE view; "/storage" needs recursive bind for its
     * per-user mounts. Manifest alias noise is dropped, not user requests.
     */
    fun bind(entry: String): RootLaunch.Bind? {
        val parts = entry.split(':', limit = 2)
        val host = parts.getOrNull(0)?.trimEnd('/').orEmpty().ifEmpty { "/" }
        val guest = parts.getOrNull(1)?.trimEnd('/') ?: host
        if (guest == "/sdcard" || guest == "/storage") return RootLaunch.Bind(host, guest)
        return null
    }

    private fun guestCwd(guestArgv: List<String>, rootfs: File): String {
        val envAt = guestArgv.indexOf("/usr/bin/env")
        check(envAt >= 0) { "guest argv must contain /usr/bin/env" }
        return File(rootfs, "root").canonicalPath
    }
}
