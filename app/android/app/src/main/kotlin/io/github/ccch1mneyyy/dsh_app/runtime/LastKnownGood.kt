package io.github.ccch1mneyyy.dsh_app.runtime

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Enabled bundles, installed packages and settings layer of the main profile at one moment. */
data class ProfileSnapshot(
    /** `dsh.profile.bundles`, in order. */
    val bundles: List<String>,
    /** `dependencies` (installed third-party packages). */
    val deps: Set<String>,
    /** Installed versions of [deps] that could be read from node_modules. */
    val versions: Map<String, String>,
    /** `cordis.patch.yml` text, or null when the file is absent. */
    val patch: String?,
)

/** One read of the main profile on disk, with the failure kept explicit for Root600. */
sealed class LkgDisk {
    /** The profile is there and parsed. */
    data class Ok(val snapshot: ProfileSnapshot) : LkgDisk()

    /** `package.json` does not exist yet — DSH has not created the profile. */
    object Missing : LkgDisk()

    /** `package.json` exists but does not parse. */
    data class Corrupt(val cause: String) : LkgDisk()

    /** `package.json` exists but cannot be read (for example permission denied under Root600). */
    data class Unreadable(val cause: String) : LkgDisk()
}

/**
 * What restoring the last good snapshot changes in the current profile: the bundle list
 * goes back to the snapshot's, minus packages uninstalled or upgraded since (an upgraded package
 * can only be turned off, not downgraded); the settings layer goes back when it differs.
 * Nothing is installed or removed.
 */
data class LkgPlan(
    val target: List<String>,
    val disable: List<String>,
    val enable: List<String>,
    /** Enabled packages whose installed version differs from the snapshot's (part of [disable]). */
    val changed: List<String>,
    val patchChanged: Boolean,
    val bundlesChanged: Boolean,
) {
    val isEmpty: Boolean get() = !bundlesChanged && !patchChanged

    fun toMap(): Map<String, Any?> = mapOf(
        "disable" to disable,
        "enable" to enable,
        "changed" to changed,
        "patchChanged" to patchChanged,
        "empty" to isEmpty,
    )

    companion object {
        fun of(lkg: ProfileSnapshot, current: ProfileSnapshot): LkgPlan {
            fun sameVersion(name: String): Boolean {
                val then = lkg.versions[name]
                val now = current.versions[name]
                return then == null || now == null || then == now
            }
            val target = lkg.bundles.filter { name ->
                when {
                    name !in lkg.deps -> true // ships with DSH
                    name !in current.deps -> false // uninstalled since
                    else -> sameVersion(name)
                }
            }.distinct()
            return LkgPlan(
                target = target,
                disable = current.bundles.filter { it !in target }.distinct(),
                enable = target.filter { it !in current.bundles },
                changed = lkg.bundles.filter { it in lkg.deps && it in current.deps && it in current.bundles && !sameVersion(it) }.distinct(),
                patchChanged = !samePatch(lkg.patch, current.patch),
                bundlesChanged = target != current.bundles,
            )
        }

        fun samePatch(a: String?, b: String?): Boolean {
            val trivialA = a == null || SafeMode.isTrivialPatch(a)
            val trivialB = b == null || SafeMode.isTrivialPatch(b)
            if (trivialA && trivialB) return true
            return a?.trimEnd() == b?.trimEnd()
        }
    }
}

/**
 * The last good plugin set of profile `web`, kept outside the rootfs in
 * `filesDir/runtime/lkg/lkg.json` and recorded after each healthy two-minute normal run.
 *
 * Reads, restores and the settings-layer replacement all go through [RuntimeFileAccess]
 * (the root shell under Root600, plain files under proot) and share the writer lock
 * DSH itself takes on `package.json` (`ProfileLock`, mirroring `dsh-atomic-write`).
 */
object LastKnownGood {
    class Record(val at: Long, val snapshot: ProfileSnapshot)

    private const val PKG_NAME = "package.json"
    private const val PATCH_NAME = "cordis.patch.yml"

    fun webDir(rootfs: File): File = File(rootfs, "root/.dsh/profiles/web")

    /**
     * The main profile as it is now, with the outcome kept explicit: [LkgDisk.Missing]
     * before DSH has created it, [LkgDisk.Corrupt] when `package.json` does not parse,
     * [LkgDisk.Unreadable] when an existing file cannot be read (permission denied
     * under Root600).
     */
    internal fun readDetailed(files: RuntimeFileAccess, rootfs: File): LkgDisk {
        val pkg = files.file(File(webDir(rootfs), PKG_NAME))
        if (!pkg.isFile) return LkgDisk.Missing
        val text = try {
            files.readText(pkg)
        } catch (e: Exception) {
            return LkgDisk.Unreadable(e.message ?: "package.json unreadable")
        }
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            return LkgDisk.Corrupt(e.message ?: "package.json unparsable")
        }
        return LkgDisk.Ok(parse(files, json, webDir(rootfs)))
    }

    /**
     * What a normal run booted with, fixed when it reports ready: [atLaunch], or on a first boot
     * (DSH creates the profile after the launch read found none) the profile [readNow] returns.
     * Null when it is still missing or can't be read.
     */
    fun bootedAtReady(atLaunch: ProfileSnapshot?, readNow: () -> ProfileSnapshot?): ProfileSnapshot? =
        atLaunch ?: try {
            readNow()
        } catch (_: Exception) {
            null
        }

    /**
     * A healthy run pins exactly what it booted with, never a later read of the disk: the app may
     * have imported a profile since that nobody has seen run. False, keeping the old pin, without one.
     */
    fun pinBooted(booted: ProfileSnapshot?, save: (ProfileSnapshot) -> Unit): Boolean {
        save(booted ?: return false)
        return true
    }

    private fun parse(files: RuntimeFileAccess, json: JSONObject, dir: File): ProfileSnapshot {
        val bundles = json.optJSONObject("dsh")?.optJSONObject("profile")?.optJSONArray("bundles").strings()
        val depsJson = json.optJSONObject("dependencies")
        val deps = LinkedHashSet<String>()
        depsJson?.keys()?.forEach { deps += it }
        val versions = LinkedHashMap<String, String>()
        for (name in deps) {
            val version = try {
                JSONObject(files.readText(files.file(File(dir, "node_modules/$name/package.json")))).optString("version")
            } catch (_: Exception) {
                null
            }
            if (!version.isNullOrEmpty()) versions[name] = version
        }
        val patchFile = files.file(File(dir, PATCH_NAME))
        val patch = if (patchFile.isFile) files.readText(patchFile) else null
        return ProfileSnapshot(bundles, deps, versions, patch)
    }

    fun load(file: File): Record? {
        if (!file.isFile) return null
        return try {
            val json = JSONObject(file.readText())
            val versionsJson = json.optJSONObject("versions")
            val versions = LinkedHashMap<String, String>()
            versionsJson?.keys()?.forEach { versions[it] = versionsJson.optString(it) }
            Record(
                at = json.optLong("at"),
                snapshot = ProfileSnapshot(
                    bundles = json.optJSONArray("bundles").strings(),
                    deps = json.optJSONArray("deps").strings().toCollection(LinkedHashSet()),
                    versions = versions,
                    patch = if (json.isNull("patch")) null else json.optString("patch"),
                ),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun save(file: File, snapshot: ProfileSnapshot, at: Long) {
        val versions = JSONObject()
        snapshot.versions.forEach { (k, v) -> versions.put(k, v) }
        val json = JSONObject()
            .put("at", at)
            .put("bundles", JSONArray(snapshot.bundles))
            .put("deps", JSONArray(snapshot.deps.toList()))
            .put("versions", versions)
            .put("patch", snapshot.patch ?: JSONObject.NULL)
        file.parentFile?.mkdirs()
        SafeMode.writeAtomic(file, json.toString(2) + "\n")
    }

    /**
     * Applies [LkgPlan.of] to the main profile under DSH's `package.json.lock`
     * ([ProfileLock], the same file DSH's plugin manager locks before it touches this
     * profile). The settings layer being replaced is kept as `cordis.patch.yml.bak-<stamp>`,
     * written through the same root-aware seam; a held lock makes this throw [busyConfig]
     * after [lockWaitMs] instead of writing.
     */
    internal fun restore(
        files: RuntimeFileAccess,
        rootfs: File,
        lkg: ProfileSnapshot,
        stamp: String,
        missingConfig: String = "找不到主配置 profiles/web/package.json", // l10n:keep test default
        busyConfig: String = "插件配置正被别的程序修改，稍后再试", // l10n:keep test default
        setPrivateMode: (File) -> Boolean = RootProfileIo::setPrivateMode,
        lockWaitMs: Long = ProfileLock.DEFAULT_WAIT_MS,
        pid: () -> Long = ProfileLock::currentPid,
        alive: (Long) -> Boolean = { ProfileLock.isAlive(it) },
        sleep: (Long) -> Unit = Thread::sleep,
    ): LkgPlan {
        val dir = webDir(rootfs)
        val pkg = files.file(File(dir, PKG_NAME))
        if (!pkg.isFile) throw IllegalStateException(missingConfig)
        return ProfileLock.withLock(files, pkg, busyConfig, waitMs = lockWaitMs, pid = pid, alive = alive, sleep = sleep) {
            val json = JSONObject(files.readText(pkg))
            val plan = LkgPlan.of(lkg, parse(files, json, dir))
            if (plan.bundlesChanged) {
                val dsh = json.optJSONObject("dsh") ?: JSONObject().also { json.put("dsh", it) }
                val profile = dsh.optJSONObject("profile") ?: JSONObject().also { dsh.put("profile", it) }
                profile.put("bundles", JSONArray(plan.target))
                // Android's org.json escapes every '/' as "\/"; undo that so the file stays as DSH writes it.
                RootProfileIo.atomicReplace(files, pkg, json.toString(2).replace("\\/", "/") + "\n", setPrivateMode)
            }
            if (plan.patchChanged) {
                val patchFile = files.file(File(dir, PATCH_NAME))
                if (patchFile.isFile) {
                    val previous = files.readText(patchFile)
                    RootProfileIo.atomicReplace(files, files.file(File(dir, "$PATCH_NAME.bak-$stamp")), previous, setPrivateMode)
                }
                val text = lkg.patch
                if (text != null) RootProfileIo.atomicReplace(files, patchFile, text, setPrivateMode)
                else files.delete(patchFile, recursive = false)
            }
            plan
        }
    }

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>(length())
        for (i in 0 until length()) optString(i).takeIf { it.isNotEmpty() }?.let { out += it }
        return out
    }
}
