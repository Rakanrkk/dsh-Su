package io.github.ccch1mneyyy.dsh_app.files

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.io.SuFile
import com.topjohnwu.superuser.io.SuFileInputStream
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import io.github.ccch1mneyyy.dsh_app.BuildConfig
import io.github.ccch1mneyyy.dsh_app.runtime.PhoneStorage
import io.github.ccch1mneyyy.dsh_app.runtime.RootShellSelection
import io.github.ccch1mneyyy.dsh_app.runtime.RootShellSelector
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Files the agent made for the user (chat deliverables / artifacts):
 * MethodChannel `dsh/files`.
 *
 * - `resolveGuest {path}` → host path of a local-engine file, or null. A
 *   Root build copies a Root-only file into the app cache first; ordinary
 *   builds keep the original app-uid behavior and permission flow.
 * - `listOutputs {depth}` → `[{path, bytes, modified}]` under `/sdcard/Download/DSH-Go`, then
 *   the old `/sdcard/Download/DSH` (guest paths; Root can list them without
 *   Android shared-storage permission).
 * - `open {path, mime, name, chooser}` → ACTION_VIEW through this app's FileProvider.
 * - `saveToDownloads {path, name, mime}` → shown location (`Download/DSH-Go/x.pdf`);
 *   ordinary storage is used first, live Root is the fallback.
 * - `pdfInfo {path}` → page sizes; `pdfPage {path, page, width}` → PNG bytes;
 *   `pdfClose` (in-app PDF preview, [PdfPages]).
 *
 * Errors: `missing` (no such readable file), `no_app` (nothing opens this
 * type), `permission` (storage write refused), `root_unavailable` (no
 * authorized Root shell when the fallback is needed), `failed`.
 */
class FilesPlugin() : FlutterPlugin, ActivityAware, MethodChannel.MethodCallHandler,
    PluginRegistry.RequestPermissionsResultListener {

    internal constructor(testContext: Context) : this() {
        context = testContext
    }

    internal constructor(
        testContext: Context,
        testReplyDispatcher: ((() -> Unit) -> Unit)?,
    ) : this() {
        context = testContext
        if (testReplyDispatcher != null) reply = testReplyDispatcher
    }

    private lateinit var methods: MethodChannel
    private lateinit var context: Context
    private var activity: ActivityPluginBinding? = null
    private var pendingSave: (() -> Unit)? = null
    private var pendingDenied: (() -> Unit)? = null
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val pdfPages by lazy { PdfPages() }
    private val rootAccess by lazy { RootFiles(context) }
    private val rootRuntime = BuildConfig.ROOT_RUNTIME
    private var reply: (() -> Unit) -> Unit = { action -> main.post(action) }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        methods = MethodChannel(binding.binaryMessenger, CHANNEL).also { it.setMethodCallHandler(this) }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        onDetachedFromActivity()
        methods.setMethodCallHandler(null)
        pdfPages.run({ close() }) {}
        rootAccess.close()
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity?.removeRequestPermissionsResultListener(this)
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) = onAttachedToActivity(binding)

    override fun onDetachedFromActivity() {
        onDetachedFromActivityForConfigChanges()
        finishPermission(granted = false)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "resolveGuest" -> background(result) {
                val path = call.argument<String>("path") ?: ""
                if (rootRuntime) rootAccess.resolve(path)?.absolutePath
                else GuestFiles.resolve(context, path)?.absolutePath
            }
            "listOutputs" -> background(result) { listOutputs(call.argument<Int>("depth") ?: 3) }
            "open" -> if (rootRuntime) background(result) { open(call, result); null } else open(call, result)
            "saveToDownloads" -> save(call, result)
            "pdfInfo" -> pdf(result) {
                val path = call.argument<String>("path") ?: ""
                if (rootRuntime) info(pdfSource(path).path) else info(path)
            }
            "pdfPage" -> pdf(result) {
                val path = call.argument<String>("path") ?: ""
                val page = call.argument<Int>("page") ?: 0
                val width = call.argument<Int>("width") ?: 1080
                if (rootRuntime) render(pdfSource(path).path, page, width) else render(path, page, width)
            }
            "pdfClose" -> pdf(result) { close() }
            else -> result.notImplemented()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Open

    private fun open(call: MethodCall, result: MethodChannel.Result) {
        val file = if (rootRuntime) {
            openSource(call)
        } else {
            ordinarySource(call) ?: return result.error("missing", "no such file", null)
        }
        val name = call.argument<String>("name") ?: file.name
        val mime = mimeOf(call.argument<String>("mime"), name)
        val uri = try {
            contentUri(file, name)
        } catch (e: Exception) {
            if (rootRuntime) throw FilesError("failed", e.message) else return result.error("failed", e.message, null)
        }
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        view.clipData = ClipData.newRawUri(name, uri)
        if (view.resolveActivity(context.packageManager) == null) {
            if (rootRuntime) throw FilesError("no_app", "no app opens $mime") else return result.error("no_app", "no app opens $mime", null)
        }
        val launch = if (call.argument<Boolean>("chooser") == true) Intent.createChooser(view, null) else view
        val host: Activity? = activity?.activity
        try {
            if (host != null) {
                host.startActivity(launch)
            } else {
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (e: ActivityNotFoundException) {
            if (rootRuntime) throw FilesError("no_app", e.message) else return result.error("no_app", e.message, null)
        } catch (e: Exception) {
            if (rootRuntime) throw FilesError("failed", e.message) else return result.error("failed", e.message, null)
        }
        if (!rootRuntime) result.success(null)
    }

    /**
     * A content URI for [file]: through the provider's roots when it lies in one,
     * else a copy in the cache's `artifacts/open` (named [name], so the
     * receiving app sees the real file name).
     */
    private fun contentUri(file: File, name: String): Uri {
        val authority = "${context.packageName}.files"
        return try {
            FileProvider.getUriForFile(context, authority, file)
        } catch (_: IllegalArgumentException) {
            val copy = copyForOpen(file, File(context.cacheDir, "artifacts/open"), name)
            FileProvider.getUriForFile(context, authority, copy)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Save to Downloads

    internal fun save(call: MethodCall, result: MethodChannel.Result) {
        if (!rootRuntime) {
            val file = ordinarySource(call) ?: return result.error("missing", "no such file", null)
            val name = safeName(call.argument<String>("name") ?: file.name)
            val mime = mimeOf(call.argument<String>("mime"), name)
            saveOrAsk(file, name, mime, result, asked = false)
            return
        }
        background(result) {
            val file = openSource(call)
            val name = safeName(call.argument<String>("name") ?: file.name)
            val mime = mimeOf(call.argument<String>("mime"), name)
            if (canWriteShared()) saveNow(file, name, mime) else saveWithRoot(file, name, mime)
        }
    }

    private fun openSource(call: MethodCall): File {
        val path = call.argument<String>("path") ?: ""
        return rootAccess.resolve(path) ?: throw FilesError("missing", "no such readable file")
    }

    private fun ordinarySource(call: MethodCall): File? {
        return File(call.argument<String>("path") ?: "").takeIf { it.isFile }
    }

    /**
     * Saves on a worker thread. Android 8–9 need the storage permission for the
     * public Downloads directory, so it is asked for first; on 10+ it is asked
     * for only if MediaStore turns this (targetSdk 28) app away. Asked once per save.
     */
    internal fun saveOrAsk(file: File, name: String, mime: String, result: MethodChannel.Result, asked: Boolean) {
        val retry = { saveOrAsk(file, name, mime, result, asked = true) }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !canWriteShared()) {
            return if (asked) result.error("permission", "storage permission denied", null) else ask(result, retry)
        }
        thread(name = "dsh-files", isDaemon = true) {
            val outcome = runCatching { saveNow(file, name, mime) }
            main.post {
                val error = outcome.exceptionOrNull()
                val refused = error is SecurityException || (error is FilesError && error.code == "permission")
                when {
                    error == null -> result.success(outcome.getOrNull())
                    refused && !asked && activity != null -> ask(result, retry)
                    else -> fail(result, error)
                }
            }
        }
    }

    private fun ask(result: MethodChannel.Result, then: () -> Unit) {
        if (pendingSave != null) return result.error("permission", "storage permission request already pending", null)
        val host = activity?.activity ?: return result.error("permission", "no activity to ask for permission", null)
        val denied = { result.error("permission", "storage permission denied", null) }
        pendingSave = then
        pendingDenied = denied
        try {
            host.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_REQUEST)
        } catch (error: Exception) {
            // A synchronous callback may already have consumed this request.
            if (pendingDenied === denied) {
                pendingSave = null
                pendingDenied = null
                result.error("permission", error.message, null)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean {
        if (requestCode != STORAGE_REQUEST) return false
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        finishPermission(granted)
        return true
    }

    private fun finishPermission(granted: Boolean) {
        val next = if (granted) pendingSave else pendingDenied
        pendingSave = null
        pendingDenied = null
        next?.invoke()
    }

    private fun saveNow(file: File, name: String, mime: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return saveToMediaStore(file, name, mime)
            } catch (e: SecurityException) {
                if (!canWriteShared()) throw FilesError("permission", e.message)
            } catch (e: IOException) {
                if (!canWriteShared()) throw e
            }
        }
        if (!canWriteShared()) throw FilesError("permission", "storage permission denied")
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(downloads, SUBDIR).apply { mkdirs() }
        val target = file.inputStream().use { LegacyDownloads.copy(it, dir, name) }
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
        return "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR/${target.name}"
    }

    private fun saveWithRoot(file: File, name: String, mime: String): String {
        val directory = rootAccess.rootOutput()
        return rootAccess.openInput(file).use { input ->
            LegacyDownloads.copy(input, directory, name).let { copied ->
                MediaScannerConnection.scanFile(context, arrayOf(copied.absolutePath), arrayOf(mime), null)
                "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR/${copied.name}"
            }
        }
    }

    private fun saveToMediaStore(file: File, name: String, mime: String): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore refused the file")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("cannot write $uri")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        // MediaStore renames on collision ("report (1).pdf"): report the name it kept.
        val shown = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: name
        return "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR/$shown"
    }

    private fun canWriteShared(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) return true
        val granted = context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (!granted) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Environment.isExternalStorageLegacy()
    }

    // ---------------------------------------------------------------------------------------------
    // The phone's output folder

    private fun listOutputs(depth: Int): List<Map<String, Any>> {
        if (PhoneStorage.isGranted(context)) return listOutputsNormally(depth)
        if (rootRuntime) return rootAccess.listOutputs(depth)
        return emptyList()
    }

    private fun listOutputsNormally(depth: Int): List<Map<String, Any>> {
        val hostRoot = PhoneStorage.hostRoot()
        val out = ArrayList<Map<String, Any>>()
        fun walk(dir: File, level: Int) {
            val children = dir.listFiles() ?: return
            for (child in children.sortedBy { it.name }) {
                if (out.size >= MAX_OUTPUTS) return
                if (child.name.startsWith(".")) continue
                if (child.isDirectory) {
                    if (level < depth) walk(child, level + 1)
                } else if (child.isFile) {
                    out += mapOf(
                        "path" to PhoneStorage.GUEST_PATH + child.absolutePath.removePrefix(hostRoot),
                        "bytes" to child.length(),
                        "modified" to child.lastModified(),
                    )
                }
            }
        }
        // The old folder too: earlier chats' files, and a user's own AGENTS.md that still names it.
        for (folder in listOf(SUBDIR, LEGACY_SUBDIR)) walk(File(hostRoot, "${Environment.DIRECTORY_DOWNLOADS}/$folder"), 1)
        return out
    }

    // ---------------------------------------------------------------------------------------------

    private fun pdfSource(path: String): File = rootAccess.resolve(path)
        ?: throw FilesError("missing", "no such readable file")

    private fun <T> background(result: MethodChannel.Result, work: () -> T) {
        thread(name = "dsh-files", isDaemon = true) {
            val outcome = try {
                Result.success(work())
            } catch (e: Throwable) {
                Result.failure(e)
            }
            reply { outcome.fold(onSuccess = { result.success(it) }, onFailure = { fail(result, it) }) }
        }
    }

    private fun <T> pdf(result: MethodChannel.Result, work: PdfPages.() -> T) {
        pdfPages.run(work) { outcome ->
            reply {
                outcome.fold(
                    onSuccess = { result.success(if (it is Unit) null else it) },
                    onFailure = { e -> if (e is java.io.FileNotFoundException) result.error("missing", e.message, null) else fail(result, e) },
                )
            }
        }
    }

    private fun fail(result: MethodChannel.Result, e: Throwable) = when (e) {
        is FilesError -> result.error(e.code, e.message, null)
        is RootUnavailableException -> result.error("root_unavailable", e.message, null)
        is SecurityException -> result.error("permission", e.message, null)
        else -> result.error("failed", e.message ?: e.javaClass.simpleName, null)
    }

    private class FilesError(val code: String, message: String?) : Exception(message)

    internal class RootFiles(
        private val appContext: Context,
        private val rootRuntime: Boolean = BuildConfig.ROOT_RUNTIME,
        private val selector: (Context) -> RootShellSelection? = RootShellSelector::select,
    ) {
        private var selection: RootShellSelection? = null

        fun resolve(path: String): File? {
            if (!GuestFiles.isGuestRootPath(path)) return null
            if (GuestFiles.isRootfsPath(path)) {
                GuestFiles.resolve(appContext, path)?.let { return it }
            } else if (!PhoneStorage.isSharedHostPath(path) || PhoneStorage.isGranted(appContext)) {
                File(path).takeIf { it.isFile && it.canRead() }?.let { return it }
            }
            val active = ensureSelection() ?: throw RootUnavailableException()
            return try {
                when {
                    GuestFiles.isRootfsPath(path) -> GuestFiles.resolveRoot(appContext, path, active.shell)?.canonicalFile
                    isSharedGuestPath(path) -> rootSharedGuest(path, active.shell)?.canonicalFile
                    else -> rootHost(path, active.shell)?.canonicalFile
                }
            } finally {
                if (!active.useDefaultBinding) active.close()
            }
        }

        fun listOutputs(depth: Int): List<Map<String, Any>> {
            val active = ensureSelection() ?: throw RootUnavailableException()
            val hostRoot = PhoneStorage.hostRoot()
            val downloads = SuFile(File(hostRoot, Environment.DIRECTORY_DOWNLOADS).path).apply { setShell(active.shell) }
            val out = ArrayList<Map<String, Any>>()
            fun walk(dir: SuFile, level: Int) {
                val children = dir.listFiles() ?: return
                for (child in children.sortedBy { it.name }) {
                    if (out.size >= MAX_OUTPUTS) return
                    if (child.name.startsWith(".")) continue
                    if (child.isDirectory) {
                        if (level < depth) walk(child, level + 1)
                    } else if (child.isFile) {
                        out += mapOf(
                            "path" to PhoneStorage.GUEST_PATH + child.absolutePath.removePrefix(hostRoot),
                            "bytes" to child.length(),
                            "modified" to child.lastModified(),
                        )
                    }
                }
            }
            for (folder in listOf(SUBDIR, LEGACY_SUBDIR)) {
                walk(downloads.getChildFile(folder), 1)
            }
            return out
        }

        fun rootOutput(): SuFile {
            val active = ensureSelection() ?: throw RootUnavailableException()
            val downloads = SuFile(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path)
                .apply { setShell(active.shell) }
            val directory = downloads.getChildFile(SUBDIR)
            if (!directory.isDirectory && !directory.mkdirs()) throw FilesError("failed", "cannot create Downloads/DSH-Go")
            return directory
        }

        fun openInput(file: File): InputStream {
            if (!PhoneStorage.isSharedHostPath(file.absolutePath)) return file.inputStream()
            val active = ensureSelection() ?: throw RootUnavailableException()
            return SuFileInputStream.open(SuFile(file.absolutePath).apply { setShell(active.shell) })
        }

        private fun rootSharedGuest(path: String, shell: Shell): File? {
            val host = PhoneStorage.hostRoot() + path.removePrefix(PhoneStorage.GUEST_PATH)
            return rootHost(host, shell)
        }

        private fun rootHost(path: String, shell: Shell): File? {
            val source = SuFile(path).apply { setShell(shell) }
            if (!source.isFile || !source.canRead()) return null
            if (PhoneStorage.isSharedHostPath(path) && !PhoneStorage.canUseRoot(appContext, shell)) return null
            return RootExports.copyToCache({ SuFileInputStream.open(source) }, GuestFiles.exportCache(appContext))
        }

        private fun ensureSelection(): RootShellSelection? {
            if (!rootRuntime) throw RootUnavailableException()
            selection?.let { return it }
            val active = selector(appContext) ?: return null
            selection = active
            return active
        }

        private fun isSharedGuestPath(path: String): Boolean =
            path == PhoneStorage.GUEST_PATH || path.startsWith("${PhoneStorage.GUEST_PATH}/")

        fun close() {
            selection?.close()
            selection = null
        }
    }

    internal class RootUnavailableException : Exception("no root shell")

    companion object {
        const val CHANNEL = "dsh/files"
        /** `Download/DSH-Go`: saved files, and the local engine's output folder (GuestSetup). No
         *  space: the agent types this path into shell commands. */
        private const val SUBDIR = "DSH-Go"
        /** The folder before the app was called DSH Go; nothing is written there any more. */
        private const val LEGACY_SUBDIR = "DSH"
        private const val STORAGE_REQUEST = 0x5d6
        private const val MAX_OUTPUTS = 500

        /** [given] unless it is missing or generic, else the platform's guess from [name]'s extension. */
        fun mimeOf(given: String?, name: String): String {
            if (!given.isNullOrBlank() && given != "application/octet-stream") return given
            val ext = name.substringAfterLast('.', "").lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }

        /** A file name without path separators or control characters. */
        fun safeName(name: String): String {
            val cleaned = name.substringAfterLast('/').replace(Regex("[\\u0000-\\u001f\\\\:*?\"<>|]"), "_").trim()
            return cleaned.ifEmpty { "file" }
        }

        /** Stable per-source copies: another source cannot replace an already shared URI. */
        internal fun copyForOpen(file: File, root: File, name: String): File {
            val source = file.canonicalFile
            val key = MessageDigest.getInstance("SHA-256")
                .digest(source.path.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            val target = File(File(root, key), safeName(name))
            return source.copyTo(target, overwrite = true)
        }

        /** [name] in [dir], or `name (2).ext`, `name (3).ext`… when taken. */
        fun unique(dir: File, name: String): File {
            var candidate = File(dir, name)
            if (!candidate.exists()) return candidate
            val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            val stem = name.substring(0, dot)
            val ext = name.substring(dot)
            var n = 2
            while (candidate.exists()) candidate = File(dir, "$stem ($n)$ext").also { n++ }
            return candidate
        }
    }
}
