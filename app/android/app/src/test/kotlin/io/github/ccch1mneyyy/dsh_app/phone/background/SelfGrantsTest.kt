package io.github.ccch1mneyyy.dsh_app.phone.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfGrantsTest {
    private val base = listOf(
        "android.permission.WRITE_SECURE_SETTINGS",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.RECORD_AUDIO",
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.REQUEST_INSTALL_PACKAGES",
    )

    @Test fun sdk26IncludesFixedSelfPermissions() {
        assertEquals(base, selfPermissions(26))
    }

    @Test fun sdk28IncludesFixedSelfPermissions() {
        assertEquals(base, selfPermissions(28))
    }

    @Test fun sdk32KeepsTheFixedFivePermissions() {
        assertEquals(base + "android.permission.MANAGE_EXTERNAL_STORAGE", selfPermissions(32))
    }

    @Test fun sdk30AddsAllFilesAccess() {
        assertEquals(base + "android.permission.MANAGE_EXTERNAL_STORAGE", selfPermissions(30))
    }

    @Test fun sdk33AddsNotificationsLast() {
        assertEquals(
            base + "android.permission.POST_NOTIFICATIONS" + "android.permission.MANAGE_EXTERNAL_STORAGE",
            selfPermissions(33),
        )
    }

    @Test fun requestInstallPermissionIsAddedStartingAtAndroidO() {
        assertFalse(selfPermissions(25).contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        assertTrue(selfPermissions(26).contains("android.permission.REQUEST_INSTALL_PACKAGES"))
    }

    @Test fun privilegedCompletionRequiresBothNewRootGrants() {
        val targets = selfGrantTargets(35)
        assertTrue(targets.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        assertTrue(targets.contains(SELF_GRANT_DEVICE_IDLE_WHITELIST))
        assertFalse(selfGrantComplete(true, listOf("android.permission.REQUEST_INSTALL_PACKAGES"), targets))
        assertFalse(selfGrantComplete(true, listOf(SELF_GRANT_DEVICE_IDLE_WHITELIST), targets))
    }

    @Test fun actualStateMustIncludeInstallAndDozeAccess() {
        val granted: (String) -> Boolean = { true }
        assertTrue(selfGrantStateComplete(35, granted, true, true))
        assertFalse(selfGrantStateComplete(35, granted, false, true))
        assertFalse(selfGrantStateComplete(35, granted, true, false))
        assertFalse(selfGrantStateComplete(35, { it != "android.permission.RECORD_AUDIO" }, true, true))
    }

    @Test fun rootAutoGrantOnlyRunsWhenRootBuildStateIsIncomplete() {
        assertTrue(shouldAutoGrantRootSelf(true, false))
        assertFalse(shouldAutoGrantRootSelf(true, true))
        assertFalse(shouldAutoGrantRootSelf(false, false))
        assertFalse(shouldAutoGrantRootSelf(false, true))
    }

    @Test fun unresolvedGrantStateRemainsEligibleForRetry() {
        assertTrue(shouldAutoGrantRootSelf(true, false))
        assertTrue(shouldAutoGrantRootSelf(true, false))
        assertFalse(shouldAutoGrantRootSelf(true, true))
    }
}
