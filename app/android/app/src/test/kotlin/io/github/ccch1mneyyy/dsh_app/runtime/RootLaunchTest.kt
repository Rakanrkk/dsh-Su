package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.*
import org.junit.Test

class RootLaunchTest {
    private fun tempRoot(): File = createTempDirectory("root-launch").toFile()
    private fun spec(
        root: File = tempRoot(),
        binds: List<RootLaunch.Bind> = emptyList(),
        guestArgv: List<String> = listOf("/usr/bin/env", "-i", "HOME=/root", "/bin/sh", "-c", "echo hi"),
        suExecutable: String = "su",
        invocation: RootLaunch.SuInvocation = RootLaunch.SuInvocation.COMMAND,
    ): RootLaunch.Spec = RootLaunch.Spec(
        scriptPath = "/data/data/io.test/files/supervisor.sh",
        packageName = "io.github.ccch1mneyyy.dsh_app",
        appUid = 10000,
        instance = "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
        rootfs = root,
        loader = "/rootfs/usr/lib/ld-linux.so",
        libraryPath = "/rootfs/usr/lib",
        cwd = "/root",
        binds = binds,
        guestArgv = guestArgv,
        suExecutable = suExecutable,
        invocation = invocation,
    )

    @Test fun suArgvUsesConfigurableExecutableAndQuotedFlags() {
        val root = tempRoot()
        val result = RootLaunch.launch(spec(root))
        assertEquals(3, result.size)
        assertEquals("su", result[0])
        assertEquals("-c", result[1])
        val command = result[2]
        assertTrue(command.startsWith("exec '/system/bin/sh' '/data/data/io.test/files/supervisor.sh' 'run'"))
        assertTrue(command.contains("'--protocol' '1'"))
        assertTrue(command.contains("'--package' 'io.github.ccch1mneyyy.dsh_app'"))
        assertTrue(command.contains("'--instance' 'a1b2c3d4-e5f6-7890-abcd-ef1234567890'"))
        assertTrue(command.endsWith("'--'"))
        assertEquals("/debug_ramdisk/su", RootLaunch.launch(spec(root, suExecutable = "/debug_ramdisk/su"))[0])
        root.deleteRecursively()
    }

    @Test fun shellQuotePreservesSpacesAndApostrophes() {
        assertEquals("'a b'", RootLaunch.shellQuote("a b"))
        assertEquals("'a'\\''b'", RootLaunch.shellQuote("a'b"))
        assertEquals("''", RootLaunch.shellQuote(""))
        assertThrows(IllegalArgumentException::class.java) { RootLaunch.shellQuote(0.toChar().toString()) }
    }

    @Test fun frameEncodesNulSeparatedArguments() {
        val args = listOf("/usr/bin/env", "-i", "HOME=/root", "/bin/sh", "-c", "echo hello")
        val bytes = RootLaunch.frame(args)
        val header = "argv " + args.size + "\n"
        val headerBytes = header.toByteArray(Charsets.UTF_8)
        assertTrue(bytes.size > headerBytes.size)
        for (i in headerBytes.indices) assertEquals(headerBytes[i], bytes[i])
        val body = bytes.drop(headerBytes.size)
        var nulCount = 0
        for (b in body) if (b.toInt() == 0) nulCount++
        assertEquals(args.size, nulCount)
    }

    @Test fun frameRejectsEmptyAndNulArguments() {
        assertThrows(IllegalArgumentException::class.java) { RootLaunch.frame(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { RootLaunch.frame(listOf("/usr/bin/env", 0.toChar().toString())) }
    }

    @Test fun positionalModeBuildsDirectArgvWithoutShellQuote() {
        val root = tempRoot()
        val argv = RootLaunch.launch(spec(root, invocation = RootLaunch.SuInvocation.ROOT_POSITIONAL))
        assertEquals(listOf("su", "0", "/system/bin/sh", "/data/data/io.test/files/supervisor.sh", "run", "--protocol", "1", "--package", "io.github.ccch1mneyyy.dsh_app", "--app-uid", "10000", "--instance", "a1b2c3d4-e5f6-7890-abcd-ef1234567890", "--rootfs", root.canonicalPath, "--loader", "/rootfs/usr/lib/ld-linux.so", "--library-path", "/rootfs/usr/lib", "--cwd", "/root", "--"), argv)
        root.deleteRecursively()
    }

    @Test fun positionalModeKeepsSpacesIntact() {
        val root = tempRoot()
        val tricky = RootLaunch.Bind("/sd card/quote", "/data/local/tricky")
        val argv = RootLaunch.launch(spec(root, binds = listOf(tricky), invocation = RootLaunch.SuInvocation.ROOT_POSITIONAL))
        assertTrue(argv.contains("/sd card/quote"))
        assertFalse(argv.any { it.contains("\\") })
        root.deleteRecursively()
    }

    @Test fun commandModeRemainsDefaultAndQuotes() {
        val root = tempRoot()
        val argv = RootLaunch.launch(spec(root))
        assertEquals(3, argv.size)
        assertEquals("su", argv[0])
        assertEquals("-c", argv[1])
        assertTrue(argv[2].startsWith("exec '"))
        root.deleteRecursively()
    }
    @Test fun parseEventMatchesInstanceAndFields() {
        val instance = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
        val event = RootLaunch.parseEvent("[dsh-su] 1 " + instance + " hello", instance)!!
        assertEquals("hello", event.event)
        assertTrue(event.fields.isEmpty())
        val phase = RootLaunch.parseEvent("[dsh-su] 1 " + instance + " phase launched", instance)!!
        assertEquals("launched", phase.fields.single())
        val exit = RootLaunch.parseEvent("[dsh-su] 1 " + instance + " exit 0 clean", instance)!!
        assertTrue(RootLaunch.isFinal(exit))
        assertTrue(RootLaunch.isCleanStop(exit))
        val badExit = RootLaunch.parseEvent("[dsh-su] 1 " + instance + " exit 1 incomplete", instance)!!
        assertTrue(RootLaunch.isFinal(badExit))
        assertFalse(RootLaunch.isCleanStop(badExit))
        assertNull(RootLaunch.parseEvent("[dsh-su] 1 other-instance hello", instance))
        assertNull(RootLaunch.parseEvent("dsh web: some log line", instance))
    }

    @Test fun parseEventRejectsInvalidExitRecords() {
        val inst = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
        assertNull(RootLaunch.parseEvent("[dsh-su] 1 " + inst + " exit", inst))
        assertNull(RootLaunch.parseEvent("[dsh-su] 1 " + inst + " exit 0", inst))
        assertNull(RootLaunch.parseEvent("[dsh-su] 1 " + inst + " exit abc clean", inst))
        assertNull(RootLaunch.parseEvent("[dsh-su] 1 " + inst + " exit 256 clean", inst))
        assertNull(RootLaunch.parseEvent("[dsh-su] 1 " + inst + " exit -1 clean", inst))
        assertNull(RootLaunch.parseEvent("[dsh-su] 1 " + inst + " exit 0 unknown", inst))
        val ok = RootLaunch.parseEvent("[dsh-su] 1 " + inst + " exit 37 clean", inst)!!
        assertTrue(RootLaunch.isFinal(ok))
        assertTrue(RootLaunch.isCleanupConfirmed(ok, 37))
    }

    @Test fun cleanupConfirmedRequiresCleanAndMatchingExitCode() {
        val inst = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
        fun event(fields: String) = RootLaunch.parseEvent("[dsh-su] 1 " + inst + " exit " + fields, inst)!!
        val e37 = event("37 clean")
        assertTrue(RootLaunch.isCleanupConfirmed(e37, 37))
        assertFalse(RootLaunch.isCleanStop(e37))
        val e0 = event("0 clean")
        assertTrue(RootLaunch.isCleanupConfirmed(e0, 0))
        assertTrue(RootLaunch.isCleanStop(e0))
        assertFalse(RootLaunch.isCleanupConfirmed(event("37 clean"), 0))
        assertFalse(RootLaunch.isCleanupConfirmed(event("0 incomplete"), 0))
        assertFalse(RootLaunch.isCleanupConfirmed(event("1 not-started"), 1))
        val hello = RootLaunch.parseEvent("[dsh-su] 1 " + inst + " hello", inst)!!
        assertFalse(RootLaunch.isCleanupConfirmed(hello, 0))
    }

    @Test fun mountinfoParsesFieldsAndDetectsPropagation() {
        val normal = "25 30 0:24 / /dev rw,nosuid shared:2 - tmpfs tmpfs rw"
        val entries = RootLaunch.parseMountInfo(normal)
        assertEquals(1, entries.size)
        assertEquals("/dev", entries[0].mountPoint)
        assertEquals("tmpfs", entries[0].fsType)
        assertTrue(entries[0].shared)
        assertTrue(RootLaunch.hasPropagationMarks(normal))
        val private = "30 25 0:24 / /dev rw,nosuid - tmpfs tmpfs rw"
        assertFalse(RootLaunch.hasPropagationMarks(private))
        val master = "40 25 0:30 / /data rw master:1 - ext4 /dev/block/dm-1 rw"
        assertTrue(RootLaunch.hasPropagationMarks(master))
        val spaced = "50 25 0:40 / /path\\040with\\040spaces rw - ext4 dev rw"
        assertEquals("/path with spaces", RootLaunch.parseMountInfo(spaced)[0].mountPoint)
    }

    @Test fun mountinfoFailsClosedOnEmptyOrMalformed() {
        assertThrows(IllegalArgumentException::class.java) { RootLaunch.parseMountInfo("") }
        assertThrows(IllegalArgumentException::class.java) { RootLaunch.parseMountInfo("short line") }
        assertThrows(IllegalArgumentException::class.java) { RootLaunch.parseMountInfo("25 30 0:24 / /dev rw - tmpfs") }
    }

    @Test fun mountsUnderMatchesTreePrefix() {
        val text = "25 30 0:24 / /dev rw - tmpfs tmpfs rw\n30 25 0:26 / /data/data/io.test/files/runtime/rootfs/dev rw - tmpfs tmpfs rw"
        assertTrue(RootLaunch.mountsUnder(text, "/data/data/io.test/files/runtime/rootfs/dev"))
        assertTrue(RootLaunch.mountsUnder(text, "/data/data/io.test/files/runtime/rootfs"))
        assertFalse(RootLaunch.mountsUnder(text, "/data/data/io.test/other"))
    }

    @Test fun validationRejectsBadInputs() {
        val root = tempRoot()
        assertThrows(IllegalArgumentException::class.java) { RootLaunch.launch(spec(root, guestArgv = emptyList())) }
        assertThrows(IllegalArgumentException::class.java) { RootLaunch.launch(spec(root, guestArgv = listOf("/bin/sh"))) }
        assertThrows(IllegalArgumentException::class.java) { RootLaunch.launch(spec(root, binds = listOf(RootLaunch.Bind("/dev", "/sdcard")))) }
        assertThrows(IllegalArgumentException::class.java) { RootLaunch.launch(spec(root, binds = listOf(RootLaunch.Bind("/sdcard", "/")))) }
        assertThrows(IllegalArgumentException::class.java) { RootLaunch.launch(spec(root, binds = listOf(RootLaunch.Bind("/data", "/proc")))) }
        assertThrows(IllegalArgumentException::class.java) {
            RootLaunch.launch(spec(root, binds = listOf(RootLaunch.Bind("/a", "/b"), RootLaunch.Bind("/c", "/b"))))
        }
        root.deleteRecursively()
    }

    @Test fun bindRejectsReservedAndRelativePaths() {
        assertThrows(IllegalArgumentException::class.java) { RootLaunch.Bind("relative", "/sdcard") }
        assertThrows(IllegalArgumentException::class.java) { RootLaunch.Bind("/sdcard", "relative") }
        assertThrows(IllegalArgumentException::class.java) { RootLaunch.Bind("/", "/sdcard") }
        assertThrows(IllegalArgumentException::class.java) { RootLaunch.Bind("/sdcard", "/") }
        val root = tempRoot()
        for (bad in listOf(
            RootLaunch.Bind("/dev", "/sdcard"),
            RootLaunch.Bind("/sdcard", "/dev"),
            RootLaunch.Bind("/sdcard", "/proc"),
            RootLaunch.Bind("/sdcard", "/system"),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                RootLaunch.launch(spec(root, binds = listOf(bad)))
            }
        }
        root.deleteRecursively()
    }
}