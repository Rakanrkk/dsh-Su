package io.github.ccch1mneyyy.dsh_app.rootprobe

import java.io.File
import io.github.ccch1mneyyy.dsh_app.runtime.DshRuncClient
import io.github.ccch1mneyyy.dsh_app.runtime.RootCleanupConfirmation
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

        val recovered = OwnedRuncStateRecovery.recover("/pinned/runc", root) { argv ->
            commands += argv
            when (argv[3]) {
                "list" -> OwnedRuncStateRecovery.CommandResult(
                    0,
                    ids.joinToString(prefix = "[", postfix = "]") {
                        """{"id":"$it","status":"running"}"""
                    },
                )
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

    @Test fun refusesUnknownDirectoriesAndListOmissions() {
        val unknownRoot = temp.newFolder("unknown")
        File(unknownRoot, "foreign-id").mkdir()
        var commands = 0
        assertFalse(OwnedRuncStateRecovery.recover("/pinned/runc", unknownRoot) {
            commands += 1
            OwnedRuncStateRecovery.CommandResult(0, "[]")
        })
        assertEquals(1, commands)

        val omittedRoot = temp.newFolder("omitted")
        File(omittedRoot, generatedId()).mkdir()
        assertFalse(OwnedRuncStateRecovery.recover("/pinned/runc", omittedRoot) {
            OwnedRuncStateRecovery.CommandResult(0, "[]")
        })

        val foreignListRoot = temp.newFolder("foreign-list")
        var deleteAttempted = false
        assertFalse(OwnedRuncStateRecovery.recover("/pinned/runc", foreignListRoot) { argv ->
            if (argv.contains("delete")) deleteAttempted = true
            OwnedRuncStateRecovery.CommandResult(
                0,
                """[{"id":"foreign-id","status":"running"}]""",
            )
        })
        assertFalse(deleteAttempted)
    }

    @Test fun malformedListAndDeleteFailureBlockStart() {
        val malformedRoot = temp.newFolder("malformed")
        File(malformedRoot, generatedId()).mkdir()
        assertFalse(OwnedRuncStateRecovery.recover("/pinned/runc", malformedRoot) {
            OwnedRuncStateRecovery.CommandResult(0, """[{"id":"dsh-12345678-abc"}]""")
        })

        val failureRoot = temp.newFolder("failure")
        val id = generatedId()
        File(failureRoot, id).mkdir()
        val commands = mutableListOf<List<String>>()
        assertFalse(OwnedRuncStateRecovery.recover("/pinned/runc", failureRoot) { argv ->
            commands += argv
            if (argv.contains("list")) {
                OwnedRuncStateRecovery.CommandResult(0, """[{"id":"$id","status":"running"}]""")
            } else {
                OwnedRuncStateRecovery.CommandResult(1, "")
            }
        })
        assertEquals(2, commands.size)
    }

    @Test fun nonzeroListExitBlocksStartEvenWithNullOutput() {
        val root = temp.newFolder("list-failure")
        val commands = mutableListOf<List<String>>()
        assertFalse(OwnedRuncStateRecovery.recover("/pinned/runc", root) { argv ->
            commands += argv
            OwnedRuncStateRecovery.CommandResult(2, "null\n")
        })
        assertEquals(1, commands.size)
        assertTrue(commands.single().contains("list"))
    }

    @Test fun explicitRecoveryRejectionSkipsRunConfirmsThisRequestAndAllowsRetry() {
        val root = temp.newFolder("rejected-then-retry")
        val oldId = generatedId()
        File(root, oldId).mkdir()
        var failList = true
        var deleteAttempts = 0
        var runAttempts = 0
        fun recover(): Boolean = OwnedRuncStateRecovery.recover("/pinned/runc", root) { argv ->
            when (argv[3]) {
                "list" -> if (failList) {
                    OwnedRuncStateRecovery.CommandResult(1, "null\n")
                } else if (File(root, oldId).exists()) {
                    OwnedRuncStateRecovery.CommandResult(
                        0,
                        """[{"id":"$oldId","status":"running"}]""",
                    )
                } else {
                    OwnedRuncStateRecovery.CommandResult(0, "null\n")
                }
                "delete" -> {
                    deleteAttempts += 1
                    File(root, argv.last()).delete()
                    OwnedRuncStateRecovery.CommandResult(0, "")
                }
                else -> error("unexpected command")
            }
        }

        val firstResult = RootProbeStartResult.runIfRecovered(recover()) {
            runAttempts += 1
            "dsh-new-container"
        } ?: RootProbeStartResult.RECOVERY_REJECTED

        assertEquals(RootProbeStartResult.RECOVERY_REJECTED, firstResult)
        assertEquals(0, runAttempts)
        assertEquals(0, deleteAttempts)
        assertTrue(File(root, oldId).isDirectory)
        val rejectedStatus = RootProbeStartResult.cleanupStatus(firstResult)
        assertEquals("notSubmitted", rejectedStatus)
        val cleanup = RootCleanupConfirmation()
        cleanup.recordStop(rejectedStatus)
        assertTrue(cleanup.confirmed)

        failList = false
        val retryResult = RootProbeStartResult.runIfRecovered(recover()) {
            runAttempts += 1
            "dsh-new-container"
        } ?: RootProbeStartResult.RECOVERY_REJECTED

        assertEquals("dsh-new-container", retryResult)
        assertEquals(1, deleteAttempts)
        assertEquals(1, runAttempts)
        assertFalse(File(root, oldId).exists())
    }

    @Test fun transportFailureOrRunFailureNeverMapsToNotSubmitted() {
        val gate = DshRuncClient.RootStartGate()
        var startResponse: String? = null
        assertThrows(IllegalStateException::class.java) {
            gate.submit(canSubmit = { true }) {
                throw IllegalStateException("binder failure")
            }
        }
        assertEquals(
            DshRuncClient.RootStartGate.CancelResult.SUBMISSION_ATTEMPTED,
            gate.cancel(),
        )
        assertNull(RootProbeStartResult.cleanupStatus(startResponse))
        assertNull(RootProbeStartResult.cleanupStatus("notRunning"))
        assertNull(RootProbeStartResult.cleanupStatus("remoteError"))
        val uncertainCleanup = RootCleanupConfirmation()
        uncertainCleanup.recordStop(RootProbeStartResult.cleanupStatus(startResponse))
        assertFalse(uncertainCleanup.confirmed)

        val runGate = DshRuncClient.RootStartGate()
        var runAttempts = 0
        assertThrows(IllegalStateException::class.java) {
            runGate.submit(canSubmit = { true }) {
                RootProbeStartResult.runIfRecovered(true) {
                    runAttempts += 1
                    throw IllegalStateException("runc run failed")
                }
            }
        }
        assertEquals(1, runAttempts)
        assertEquals(
            DshRuncClient.RootStartGate.CancelResult.SUBMISSION_ATTEMPTED,
            runGate.cancel(),
        )
        assertNull(RootProbeStartResult.cleanupStatus(null))
    }

    @Test fun emptyStateAllowsFirstStart() {
        assertTrue(OwnedRuncStateRecovery.recover("/pinned/runc", temp.newFolder("empty")) {
            OwnedRuncStateRecovery.CommandResult(0, "[]")
        })
    }

    @Test fun recoversAnEmptyStateRootFromRuncNullOutput() {
        val root = temp.newFolder("runc-empty")
        val commands = mutableListOf<List<String>>()
        assertTrue(OwnedRuncStateRecovery.recover("/pinned/runc", root) { argv ->
            commands += argv
            OwnedRuncStateRecovery.CommandResult(0, "null\n")
        })
        assertEquals(
            listOf(
                listOf("/pinned/runc", "--root", root.canonicalPath, "list", "--format", "json"),
                listOf("/pinned/runc", "--root", root.canonicalPath, "list", "--format", "json"),
            ),
            commands,
        )
    }

    @Test fun parsesEveryEmptyListEncodingAndRejectsBlankOutput() {
        assertEquals(emptySet<String>(), OwnedRuncStateRecovery.parseList("null"))
        assertEquals(emptySet<String>(), OwnedRuncStateRecovery.parseList(" \r\n null\t"))
        assertEquals(emptySet<String>(), OwnedRuncStateRecovery.parseList("[]"))
        assertEquals(emptySet<String>(), OwnedRuncStateRecovery.parseList("\n [] \t"))
        assertNull(OwnedRuncStateRecovery.parseList(""))
        assertNull(OwnedRuncStateRecovery.parseList(" \r\n\t"))
    }

    @Test fun parsesNonemptyRuncArrayAndRejectsMalformedRecords() {
        val id = generatedId()
        assertEquals(
            setOf(id),
            OwnedRuncStateRecovery.parseList("""[{"id":"$id","status":"running"}]"""),
        )
        assertNull(OwnedRuncStateRecovery.parseList("{}"))
        assertNull(OwnedRuncStateRecovery.parseList("""[{"id":"$id"}]"""))
        assertNull(OwnedRuncStateRecovery.parseList("""[{"id":"$id","status":""}]"""))
        assertNull(OwnedRuncStateRecovery.parseList("""[{"id":"$id","status":"running"}] trailing"""))
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
}
