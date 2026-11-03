package io.github.ccch1mneyyy.dsh_app.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class DshRuncCleanupRetryTest {
    @Test fun repeatedCleanupRetriesDeleteForTheSameContainerAndStateRoot() {
        val stopResults = mutableListOf("deleteFailed", "killFailed")
        val waitResults = mutableListOf("deleteFailed", "ok:17")
        val waitTimeouts = mutableListOf<Long>()
        val calls = mutableListOf<String>()
        var stopCalls = 0

        val stop: (String, String) -> String = { containerId, stateRoot ->
            stopCalls++
            calls += "stop:$containerId:$stateRoot"
            stopResults.removeAt(0)
        }
        val waitExit: (String, String, Long) -> String = { containerId, stateRoot, timeout ->
            waitTimeouts += timeout
            calls += "wait:$containerId:$stateRoot"
            waitResults.removeAt(0)
        }

        val firstStatus = DshRuncClient.stopAndConfirmCleanup("same-container", "/state", stop, waitExit)
        assertEquals("deleteFailed", firstStatus)

        val retryStatus = DshRuncClient.stopAndConfirmCleanup("same-container", "/state", stop, waitExit)
        assertEquals("ok:17", retryStatus)
        assertEquals(2, stopCalls)
        assertEquals(listOf(1000L, 1000L), waitTimeouts)
        assertEquals(
            listOf(
                "stop:same-container:/state",
                "wait:same-container:/state",
                "stop:same-container:/state",
                "wait:same-container:/state",
            ),
            calls,
        )
    }
}
