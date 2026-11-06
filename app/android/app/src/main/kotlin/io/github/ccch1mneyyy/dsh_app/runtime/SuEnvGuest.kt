package io.github.ccch1mneyyy.dsh_app.runtime

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Local environment briefing for the engine's system prompt. The `suenv` plugin
 * (a single `index.mjs`, kept as the asset-packaged plugin source) measures the
 * running guest — real UID, Ubuntu release, working directory — when it loads
 * and registers one native `ctx.systemPrompt` section with those facts; it
 * never claims unmeasured capabilities (networking, apt, sandbox backends).
 * The operational rules stay in AGENTS.md ([GuestSetup]); the refreshed dynamic
 * snapshot stays in diagnostics.md, so this section only carries stable facts.
 *
 * Written at every engine start of a Root build (the caller gates on
 * [BuildConfig.ROOT_RUNTIME], and only the Root launch branch passes the
 * overlay with --patch), never in safe mode, and only when everything was
 * written — a plugin that fails to load leaves DSH running with only the
 * briefing missing.
 */
object SuEnvGuest {
    const val GUEST_DIR = "/opt/dsh-app/suenv"
    const val PATCH = "$GUEST_DIR/suenv.patch.yml"
    const val ENTRY = "$GUEST_DIR/index.mjs"

    /** Where Flutter packs `app/assets/engine/suenv/` inside the APK's assets. */
    const val ASSET_DIR = "flutter_assets/assets/engine/suenv"
    val MODULES = listOf("index.mjs")
    const val PLUGIN_ID = "dsh-su-env"

    private const val WEB_PATCH_ARG = "--patch /etc/dsh-app/dsh-web.patch.yml"
    private const val DSH_WEB = "/usr/local/bin/dsh web"

    // l10n:keep guest config comment
    val PATCH_YAML = """# dsh-su: local environment briefing. The DSH SU App rewrites this directory at every engine
# start of a Root build and passes this file with --patch on normal launches (never in safe
# mode). Edits do not survive.
- insert:
    - id: $PLUGIN_ID
      name: $ENTRY
"""

    /** The launch shell command with the overlay right after the app's own one; null when [arg] is not the `dsh web` launch. */
    fun launchArg(arg: String): String? {
        val web = arg.indexOf("$DSH_WEB ")
        if (web < 0 || arg.indexOf("$DSH_WEB ", web + 1) >= 0 || arg.contains("--profile")) return null
        val patch = arg.indexOf(WEB_PATCH_ARG, web)
        val at = if (patch >= 0 && arg.indexOf(WEB_PATCH_ARG, patch + 1) < 0) patch + WEB_PATCH_ARG.length else web + DSH_WEB.length
        return arg.substring(0, at) + " --patch $PATCH" + arg.substring(at)
    }

    fun file(rootfs: File) = File(rootfs, PATCH.removePrefix("/"))

    /**
     * Copies the plugin module from the APK assets, then writes the overlay; files already up
     * to date are left alone. False (and no overlay file) when anything could not be written,
     * so the launch leaves it out.
     */
    fun writeFiles(context: Context, rootfs: File): Boolean {
        val modules = try {
            MODULES.associateWith { name -> context.assets.open("$ASSET_DIR/$name").use { it.readBytes() } }
        } catch (_: Exception) {
            return false
        }
        return writeOverlay(rootfs, modules)
    }

    /** Asset-free core so unit tests can drive the real write path without a Context. */
    internal fun writeOverlay(rootfs: File, modules: Map<String, ByteArray>): Boolean {
        val patch = file(rootfs)
        return try {
            val dir = File(rootfs, GUEST_DIR.removePrefix("/"))
            if (Files.isSymbolicLink(dir.toPath()) || (dir.exists() && !dir.isDirectory)) dir.delete()
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create ${dir.path}")
            for ((name, bytes) in modules) writeIfChanged(File(dir, name), bytes)
            writeIfChanged(patch, PATCH_YAML.toByteArray())
            true
        } catch (_: Exception) {
            patch.delete()
            false
        }
    }

    /** Sibling temp file renamed over [target]; only a regular file is compared (never a link or FIFO). */
    private fun writeIfChanged(target: File, bytes: ByteArray) {
        if (Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS) && target.readBytes().contentEquals(bytes)) return
        val tmp = File(target.parentFile, ".${target.name}.dsh-app-${System.nanoTime()}.tmp")
        try {
            tmp.writeBytes(bytes)
            tmp.setReadable(true, false)
            if (!tmp.renameTo(target)) throw IOException("could not replace ${target.path}")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}
