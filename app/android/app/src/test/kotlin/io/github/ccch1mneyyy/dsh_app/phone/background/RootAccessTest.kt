package io.github.ccch1mneyyy.dsh_app.phone.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootAccessTest {
    private fun granted(permission: String): Boolean =
        permission == android.Manifest.permission.RECORD_AUDIO
    private val allGranted: (String) -> Boolean = { true }

    @Test fun androidThirteenRequiresTheAppsOwnFixedItems() {
        assertEquals(
            listOf("notifications", "recordAudio", "writeSecureSettings", "storageAccess", "overlay", "installPackages", "batteryExemption"),
            rootAccessRequired(33).keys.toList(),
        )
        assertEquals(
            listOf("recordAudio", "writeSecureSettings", "storageAccess", "overlay", "installPackages", "batteryExemption"),
            rootAccessRequired(29).keys.toList(),
        )
    }

    @Test fun realCheckerMissingItemsAreStableAndComplete() {
        val missing = rootAccessMissing(
            33,
            notificationsGranted = false,
            permissionGranted = ::granted,
            storageGranted = false,
            overlayGranted = true,
            canRequestPackageInstalls = false,
            ignoresBatteryOptimizations = true,
        )
            assertEquals(listOf("notifications", "writeSecureSettings", "storageAccess", "installPackages"), missing)
    }

    @Test fun snapshotRequiresEveryCheckerAndReportsNoFalseSuccess() {
        val ready = rootAccessSnapshot(
            33, rootConnected = true, notificationsGranted = true, permissionGranted = allGranted,
            storageGranted = true, overlayGranted = true, canRequestPackageInstalls = true,
            ignoresBatteryOptimizations = true,
        )
        assertTrue(ready.configured)
        assertEquals(emptyList<String>(), ready.missing)

        val partial = rootAccessSnapshot(
            33, rootConnected = true, notificationsGranted = false,
            permissionGranted = allGranted,
            storageGranted = true, overlayGranted = true, canRequestPackageInstalls = true,
            ignoresBatteryOptimizations = true,
        )
        assertTrue(!partial.configured)
        assertEquals(listOf("notifications"), partial.missing)
    }

    @Test fun rootFailureCodesMapToStableReasons() {
        assertEquals("timeout", rootAccessFailureReason("rootTimeout"))
        assertEquals("cancelled", rootAccessFailureReason("rootCancelled"))
        assertEquals("identityMismatch", rootAccessFailureReason("rootIdentityMismatch"))
        assertEquals("disconnected", rootAccessFailureReason("rootDisconnected"))
        assertEquals("grantFailed", rootAccessFailureReason("rootGrantFailed"))
        assertEquals("unknown", rootAccessFailureReason(null))
    }
}
