package io.github.ccch1mneyyy.dsh_app.phone.background

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RootShellBootstrapTest {
    private fun quoted(value: String) = "'" + value.replace("'", "'\\''") + "'"
    private fun childCommand(vararg args: String) = listOf(
        File(System.getProperty("java.home"), "bin/java").path,
        "-cp", System.getProperty("java.class.path"), RootShellBootstrapChild::class.java.name,
    ) + args

    @Test fun defaultSuShellExecsBootstrapOnceBeforeBinaryProbeAndRetainsClasspathQuoting() {
        val dir = java.nio.file.Files.createTempDirectory("dsh-bootstrap-").toFile()
        val marker = File(dir, "exec-count")
        val injected = File(dir, "must-not-exist")
        val source = "/data/a' b\$(touch " + injected.path + ")" + 96.toChar() + "touch " + injected.path + 96.toChar() + ".apk"
        val split = "/data/app/split with spaces.apk"
        val classpath = "$source:$split"
        val su = File(dir, "su").apply {
            writeText("#!/bin/sh\n[ \"$#\" -eq 0 ] || exit 64\nexec /bin/sh\n")
            setExecutable(true)
        }
        val command = PrivilegedCommands.appProcess(source, listOf(split))
        val fixed = "exec /system/bin/app_process /system/bin " + PrivilegedDisplayMain::class.java.name
        val child = childCommand("echo", marker.path, classpath).joinToString(" ", transform = ::quoted)
        val bootstrap = command.replace(fixed, "exec $child")
        var starts = 0
        val owned = AtomicReference<Process>()
        val transport = RootProcess.launchEach(RootCommands.shell(), bootstrap = bootstrap) { argv ->
            starts++
            assertEquals(listOf("su"), argv)
            ProcessBuilder(listOf(su.path) + argv.drop(1)).start().also(owned::set)
        }
        try {
            val hello = transport.exchange("probe".toByteArray(), 5000).decodeToString().split('|')
            assertTrue(RootProbe.accepted(hello[0] == "ok", hello[1].toInt(), hello[2], "dsh-vdisplay-1"))
            assertArrayEquals(byteArrayOf(0, 1, 2, -1), transport.exchange(byteArrayOf(0, 1, 2, -1), 2000))
            assertArrayEquals(byteArrayOf(4, 5), transport.exchange(byteArrayOf(4, 5), 2000))
            assertEquals("exec\n", marker.readText())
            assertEquals(1, starts)
            assertFalse(injected.exists())
        } finally {
            transport.close()
            assertTrue(owned.get().waitFor(3, TimeUnit.SECONDS))
            dir.deleteRecursively()
        }
    }

    @Test fun deniedManagerDoesNotExecuteBootstrapOrTryAnotherSuProcess() {
        val dir = java.nio.file.Files.createTempDirectory("dsh-su-denied-").toFile()
        val marker = File(dir, "must-not-run")
        val denied = File(dir, "su").apply {
            writeText("#!/bin/sh\nprintf 'Permission denied\\n' >&2\nexit 1\n")
            setExecutable(true)
        }
        var starts = 0
        val owned = AtomicReference<Process>()
        val process = RootProcess.launchEach(RootCommands.shell(), bootstrap = "touch " + quoted(marker.path)) {
            starts++
            ProcessBuilder(denied.path).start().also(owned::set)
        }
        try {
            assertThrows(IOException::class.java) { process.exchange(byteArrayOf(1), 2000) }
            assertEquals(1, starts)
            assertFalse(marker.exists())
        } finally {
            process.close()
            assertTrue(owned.get().waitFor(3, TimeUnit.SECONDS))
            dir.deleteRecursively()
        }
    }

    @Test fun timeoutCoversBootstrapWriteAndClosesOwnedProcess() {
        val owned = AtomicReference<Process>()
        val process = RootProcess.launch(childCommand("hang"), bootstrap = "x".repeat(1024 * 1024)) {
            ProcessBuilder(it).start().also(owned::set)
        }
        val failure = assertThrows(IOException::class.java) { process.exchange(byteArrayOf(1), 150) }
        assertEquals("rootTimeout", failure.message)
        assertTrue(owned.get().waitFor(3, TimeUnit.SECONDS))
    }

    @Test fun cancelWhileWaitingForShellHelloClosesOwnedProcess() {
        val owned = AtomicReference<Process>()
        val process = RootProcess.launch(childCommand("hang"), bootstrap = "exec ignored") {
            ProcessBuilder(it).start().also(owned::set)
        }
        val begun = CountDownLatch(1)
        val reason = AtomicReference<Exception>()
        val worker = Thread {
            begun.countDown()
            try { process.exchange(byteArrayOf(1), 5000) } catch (error: Exception) { reason.set(error) }
        }
        worker.start()
        assertTrue(begun.await(1, TimeUnit.SECONDS))
        process.close()
        worker.join(2000)
        assertFalse(worker.isAlive)
        assertNotNull(reason.get())
        assertTrue(owned.get().waitFor(3, TimeUnit.SECONDS))
    }
}

object RootShellBootstrapChild {
    @JvmStatic fun main(args: Array<String>) {
        if (args[0] == "hang") { Thread.sleep(60_000); return }
        check(args[0] == "echo")
        check(System.getenv("CLASSPATH") == args[2])
        File(args[1]).appendText("exec\n")
        System.out.print("manager banner\n")
        System.out.write(Frames.MAGIC)
        System.out.flush()
        check(Frames.read(System.`in`)?.decodeToString() == "probe")
        Frames.write(System.out, "ok|0|dsh-vdisplay-1".toByteArray())
        while (true) {
            val request = Frames.read(System.`in`) ?: return
            Frames.write(System.out, request)
        }
    }
}
