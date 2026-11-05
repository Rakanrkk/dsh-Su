package io.github.ccch1mneyyy.dsh_app.files

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.github.ccch1mneyyy.dsh_app.BuildConfig
import io.github.ccch1mneyyy.dsh_app.runtime.RootShellSelection
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private class TestContext(
    private val root: File,
    internal val onSelector: () -> RootShellSelection? = { null },
) : ContextWrapper(null) {
    override fun getFilesDir(): File = root
    override fun getCacheDir(): File = File(root, "cache")
    override fun getApplicationContext(): Context = this
    override fun checkSelfPermission(permission: String?): Int = PackageManager.PERMISSION_DENIED
}

private class RecordingResult : MethodChannel.Result {
    var code: String? = null
    var callerThread: Thread? = null
    val completed = CountDownLatch(1)

    override fun success(value: Any?) { record(null) }
    override fun error(code: String, message: String?, details: Any?) { record(code) }
    override fun notImplemented() { record("not_implemented") }

    private fun record(newCode: String?) {
        code = newCode
        callerThread = Thread.currentThread()
        completed.countDown()
    }
}

private class TestRootFiles(
    context: TestContext,
) : FilesPlugin.RootFiles(
    context,
    rootRuntime = false,
    selector = context.onSelector,
)

class RootFilesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun normalBuildDoesNotSelectRootForAnOrdinaryNonGuestPath() {
        val selected = AtomicBoolean()
        val access = TestRootFiles(TestContext(temp.root) {
            selected.set(true)
            null
        })

        assertNull(access.resolve("ordinary.txt"))
        assertFalse(selected)
    }

    @Test fun normalSaveOrAskUsesLegacyPermissionFlowWithoutRootSelector() {
        val selected = AtomicBoolean()
        val context = TestContext(temp.root) {
            selected.set(true)
            null
        }
        val file = File(temp.root, "runtime/rootfs/root/report.txt").apply {
            parentFile!!.mkdirs()
            writeText("normal")
        }
        val result = RecordingResult()
        val plugin = FilesPlugin(context)

        plugin.saveOrAsk(file, "report.txt", "text/plain", result, asked = false)

        assertEquals("permission", result.code)
        assertFalse(selected.get())
    }

    @Test fun ordinaryOpenMissingCompletesWithStableError() {
        if (BuildConfig.ROOT_RUNTIME) return
        val result = RecordingResult()

        FilesPlugin(TestContext(temp.root)).onMethodCall(
            MethodCall("open", mapOf("path" to "missing.txt")),
            result,
        )

        assertTrue(result.completed.await(1, TimeUnit.SECONDS))
        assertEquals("missing", result.code)
        assertEquals(Thread.currentThread(), result.callerThread)
    }

    @Test fun ordinarySaveMissingCompletesWithStableError() {
        if (BuildConfig.ROOT_RUNTIME) return
        val result = RecordingResult()

        FilesPlugin(TestContext(temp.root)).onMethodCall(
            MethodCall("saveToDownloads", mapOf("path" to "missing.txt")),
            result,
        )

        assertTrue(result.completed.await(1, TimeUnit.SECONDS))
        assertEquals("missing", result.code)
        assertEquals(Thread.currentThread(), result.callerThread)
    }

    @Test fun rootSaveMissingCompletesInBackgroundWithStableError() {
        if (!BuildConfig.ROOT_RUNTIME) return
        val result = RecordingResult()

        FilesPlugin(TestContext(temp.root)).onMethodCall(
            MethodCall("saveToDownloads", mapOf("path" to "missing.txt")),
            result,
        )

        assertTrue(result.completed.await(2, TimeUnit.SECONDS))
        assertEquals("missing", result.code)
        assertTrue(result.callerThread !== Thread.currentThread())
    }

    @Test fun ordinaryNonGuestPathsNeverRequestRoot() {
        assertFalse(GuestFiles.isRootfsPath("ordinary.txt"))
        assertFalse(GuestFiles.isGuestRootPath("ordinary.txt"))
    }

    @Test fun guestRootfsAbsolutePathsUseTheRootNamespaceWorkflow() {
        assertTrue(GuestFiles.isGuestRootPath("/root/protected.bin"))
        assertEquals(true, GuestFiles.isRootfsPath("/root/protected.bin"))
    }

    @Test fun sharedGuestPathsAreKeptOutOfRootfsResolution() {
        assertFalse(GuestFiles.isRootfsPath("/sdcard/Download/file"))
        assertTrue(GuestFiles.isGuestRootPath("/sdcard/Download/file"))
    }
}
