package io.github.ccch1mneyyy.dsh_app.rootprobe

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OwnedRuncStateRecoveryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun clearsOwnedRecordsBeforeStartAndUsesExplicitStateRoot() {
        val root = temp.newFolder("state")
        val generatedId = "dsh-" + java.util.UUID.randomUUID().toString().substring(0, 12)
        val ids = linkedSetOf(generatedId, "dsh-3c0f87e2-9a5")
        ids.forEach { File(root, it).mkdir() }
        val commands = mutableListOf<List<String>>()

        val recovered = OwnedRuncStateRecovery.recover("/pinned/runc", root, parse = ::parseFixture) { argv ->
            commands += argv
            when (argv[3]) {
                "list" -> OwnedRuncStateRecovery.CommandResult(0, ids.joinToString(","))
                "delete" -> {
                    ids.remove(argv.last())
                    File(root, argv.last()).delete()
                    OwnedRuncStateRecovery.CommandResult(0, "")
                }
                else -> error("unexpected command")
            }
        }

        assertTrue(recovered)
        assertTrue(ids.isEmpty())
        assertEquals(2, commands.count { it.contains("delete") })
        assertTrue(commands.all { it.take(3) == listOf("/pinned/runc", "--root", root.canonicalPath) })
    }

    @Test fun acceptsKnownDeviceIdAndUuidPrefixVariants() {
        val knownId = "dsh-3c0f87e2-9a5"
        val root = temp.newFolder("known-device-id")
        File(root, knownId).mkdir()
        var present = true
        assertTrue(OwnedRuncStateRecovery.recover("/pinned/runc", root, parse = ::parseFixture) { argv ->
            if (argv.contains("list")) {
                OwnedRuncStateRecovery.CommandResult(0, if (present) knownId else "")
            } else {
                present = false
                File(root, knownId).delete()
                OwnedRuncStateRecovery.CommandResult(0, "")
            }
        })
    }

    @Test fun refusesUnknownDirectoriesAndListOmissions() {
        val unknownRoot = temp.newFolder("unknown")
        File(unknownRoot, "foreign-id").mkdir()
        var commands = 0
        assertFalse(OwnedRuncStateRecovery.recover("/pinned/runc", unknownRoot, parse = ::parseFixture) {
            commands += 1
            OwnedRuncStateRecovery.CommandResult(0, "")
        })
        assertEquals(1, commands)

        val omittedRoot = temp.newFolder("omitted")
        File(omittedRoot, generatedId()).mkdir()
        assertFalse(OwnedRuncStateRecovery.recover("/pinned/runc", omittedRoot, parse = ::parseFixture) {
            OwnedRuncStateRecovery.CommandResult(0, "")
        })
    }

    @Test fun malformedListAndDeleteFailureBlockStart() {
        val malformedRoot = temp.newFolder("malformed")
        File(malformedRoot, generatedId()).mkdir()
        assertFalse(OwnedRuncStateRecovery.recover("/pinned/runc", malformedRoot, parse = ::parseFixture) {
            OwnedRuncStateRecovery.CommandResult(0, "malformed")
        })

        val failureRoot = temp.newFolder("failure")
        val id = generatedId()
        File(failureRoot, id).mkdir()
        val commands = mutableListOf<List<String>>()
        assertFalse(OwnedRuncStateRecovery.recover("/pinned/runc", failureRoot, parse = ::parseFixture) { argv ->
            commands += argv
            if (argv.contains("list")) {
                OwnedRuncStateRecovery.CommandResult(0, id)
            } else {
                OwnedRuncStateRecovery.CommandResult(1, "")
            }
        })
        assertEquals(2, commands.size)
    }

    @Test fun emptyStateAllowsFirstStart() {
        assertTrue(OwnedRuncStateRecovery.recover("/pinned/runc", temp.newFolder("empty"), parse = ::parseFixture) {
            OwnedRuncStateRecovery.CommandResult(0, "")
        })
    }

    @Test fun waitExitDoesNotHoldMonitorWhileWaiting() {
        val monitor = Any()
        val waiting = java.util.concurrent.CountDownLatch(1)
        val releaseWait = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val waiter = executor.submit<String> {
                RootProbeExitWait.run(
                    timeoutMs = 1000,
                    capture = { synchronized(monitor) { "active" } },
                    await = { _, _ ->
                        waiting.countDown()
                        releaseWait.await(2, java.util.concurrent.TimeUnit.SECONDS)
                    },
                    finish = { "ok:$it" },
                )
            }
            assertTrue(waiting.await(1, java.util.concurrent.TimeUnit.SECONDS))
            val stop = executor.submit<Boolean> {
                synchronized(monitor) { true }
            }
            assertTrue(stop.get(1, java.util.concurrent.TimeUnit.SECONDS))
            releaseWait.countDown()
            assertEquals("ok:active", waiter.get(1, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            releaseWait.countDown()
            executor.shutdownNow()
        }
    }

    private fun generatedId(): String = "dsh-" + java.util.UUID.randomUUID().toString().substring(0, 12)

    private fun parseFixture(output: String): Set<String>? = when (output) {
        "malformed" -> null
        "" -> emptySet()
        else -> output.split(',').toSet()
    }
}
