package io.github.ccch1mneyyy.dsh_app.diagnostics

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug-only identity probe for the installed app UID. Fixed su candidate
 * list, fixed `id` command, no arbitrary input and no runtime startup.
 */
class RootShellProbeActivity : Activity() {
    private val output = StringBuilder()
    private val running = AtomicBoolean(false)
    private lateinit var outputView: TextView
    private lateinit var runButton: Button

    private fun line(text: String) {
        Log.i(TAG, text)
        Handler(Looper.getMainLooper()).post {
            output.appendLine(text)
            outputView.text = output.toString()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        runButton = Button(this).apply { text = "Run fixed probe" }
        outputView = TextView(this).apply {
            setTextIsSelectable(true)
            text = "Tap Run fixed probe."
        }
        val scroll = ScrollView(this)
        scroll.addView(outputView)
        root.addView(runButton)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        runButton.setOnClickListener { startProbe() }
    }

    @SuppressLint("SetTextI18n")
    private fun startProbe() {
        if (!running.compareAndSet(false, true)) return
        runButton.isEnabled = false
        output.setLength(0)
        outputView.text = ""
        Thread({ runProbe() }, PROBE_THREAD_NAME).start()
    }

    private fun runProbe() {
        try {
            line("PROBE_START=true")
            line("APP_PACKAGE=${applicationContext.packageName}")
            line("APP_UID=${Process.myUid()}")
            line("SELINUX_CURRENT=${readContext("/proc/self/attr/current")}")
            line("SELINUX_ENFORCING=${readContext("/sys/fs/selinux/enforce")}")
            val results = PROBE_SU_PATHS.map { path -> runFixedId(path) }
            line("SUMMARY=" + results.joinToString(";"))
            line("PROBE_DONE=true")
        } finally {
            Handler(Looper.getMainLooper()).post {
                running.set(false)
                runButton.isEnabled = true
            }
        }
    }

    private fun runFixedId(probeSu: String): String {
        line("SU=$probeSu MODE=COMMAND COMMAND=$FIXED_ID")
        val process: java.lang.Process = try {
            ProcessBuilder(listOf(probeSu, "-c", FIXED_ID)).start()
        } catch (error: Exception) {
            line("RESULT=START_ERROR EXCEPTION=${error.javaClass.name} MESSAGE=${oneLine(error.message ?: "unknown")}")
            return "START_ERROR"
        }
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val readersDone = CountDownLatch(2)
        Thread({
            process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { stdout.appendLine(it) }
            }
            readersDone.countDown()
        }, "$PROBE_THREAD_NAME-stdout").apply { isDaemon = true }.start()
        Thread({
            process.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { stderr.appendLine(it) }
            }
            readersDone.countDown()
        }, "$PROBE_THREAD_NAME-stderr").apply { isDaemon = true }.start()
        try {
            process.outputStream.close()
        } catch (_: Exception) {
        }
        if (!readersDone.await(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            closeOwnStdin(process.outputStream)
            line("RESULT=TIMEOUT EXIT=unknown")
            return "TIMEOUT"
        }
        if (!process.waitFor(WAIT_AFTER_READERS_SECONDS, TimeUnit.SECONDS)) {
            closeOwnStdin(process.outputStream)
            line("RESULT=TIMEOUT EXIT=unknown READERS_DONE=true")
            return "TIMEOUT"
        }
        val exit = process.exitValue()
        val uid = parseUid(stdout.toString())
        val context = parseContext(stdout.toString())
        line("UID=$uid")
        line("CONTEXT=${context ?: "UNKNOWN"}")
        line("STDERR=${oneLine(stderr.toString())}")
        line("EXIT=$exit")
        val result = when {
            uid != null && exit == 0 -> "OK"
            uid != null && exit != 0 -> "UID_OK_EXIT_MISMATCH"
            exit == 0 -> "EMPTY_OUTPUT"
            else -> "COMMAND_ERROR"
        }
        line("RESULT=$result")
        return when {
            uid != null -> "$result uid=$uid ctx=${context ?: "unknown"}"
            else -> result
        }
    }

    private fun closeOwnStdin(output: java.io.OutputStream?) {
        try {
            output?.close()
        } catch (_: Exception) {
        }
    }

    private fun parseUid(text: String): Int? {
        return UID_PATTERN.find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun parseContext(text: String): String? {
        return CONTEXT_PATTERN.find(text)?.groupValues?.get(1)
    }

    private fun oneLine(text: String): String {
        return text.trim().replace('\n', ' ').take(LOG_LIMIT).ifEmpty { "EMPTY" }
    }

    private fun readContext(path: String): String {
        return try {
            File(path).readBytes().toString(Charsets.ISO_8859_1).trim().ifEmpty { "EMPTY" }
        } catch (error: Exception) {
            "READ_ERROR:${error.javaClass.simpleName}"
        }
    }

    companion object {
        private const val TAG = "DshRootAppProbe"
        private const val PROBE_THREAD_NAME = "dsh-app-root-probe"
        private val PROBE_SU_PATHS = listOf("/system/bin/su", "/debug_ramdisk/su")
        private const val FIXED_ID = "id"
        private const val PROBE_TIMEOUT_SECONDS = 15L
        private const val WAIT_AFTER_READERS_SECONDS = 3L
        private const val LOG_LIMIT = 400
        private val UID_PATTERN = Regex("""\buid=(\d+)""")
        private val CONTEXT_PATTERN = Regex("""\bcontext=(\S+)""")
    }
}
