package io.github.ccch1mneyyy.dsh_app.phone.background

import io.github.ccch1mneyyy.dsh_app.BuildConfig
import org.junit.Assert.*
import org.junit.Test

class SecureSettingsGrantTest {
    @Test fun suiUsesTheExistingPermissionAndGrantRoutesWithoutAnApk() {
        val sui = ShizukuStatus.fromFacts(false, true, true, false)
        val noAdb = AdbStatus(paired = false, wirelessDebugging = false, connected = false)
        assertEquals(SecureSettingsGrant.SHIZUKU_PERMISSION, SecureSettingsGrant.path(false, sui, noAdb))
        assertEquals(SecureSettingsGrant.SHIZUKU, SecureSettingsGrant.path(false, sui.copy(granted = true), noAdb))
        assertEquals(SecureSettingsGrant.SETUP,
            SecureSettingsGrant.path(false, ShizukuStatus.fromFacts(false, true, false, true), noAdb))
    }

    @Test fun aLiveRootConnectionIsTheGrantPathButSelectionAloneIsNot() {
        val none = ShizukuStatus(installed = false, running = false, granted = false)
        val noAdb = AdbStatus(wirelessDebugging = false, paired = false, connected = false)
        assertEquals(SecureSettingsGrant.ROOT, SecureSettingsGrant.path(false, none, noAdb, rootConnected = true))
        assertEquals(SecureSettingsGrant.ROOT,
            SecureSettingsGrant.privilegedPath(false, none, noAdb, rootSelected = false, rootConnected = true))
        assertEquals(SecureSettingsGrant.SETUP,
            SecureSettingsGrant.privilegedPath(false, none, noAdb, rootSelected = true, rootConnected = false))
    }

    @Test fun prefersAnAuthorizedChannelWithoutAnotherPrompt() {
        val shizuku = ShizukuStatus(installed = true, running = true, granted = true)
        val adb = AdbStatus(paired = true, wirelessDebugging = true, connected = false)
        assertEquals(SecureSettingsGrant.GRANTED, SecureSettingsGrant.path(true, shizuku, adb))
        assertEquals(SecureSettingsGrant.SHIZUKU, SecureSettingsGrant.path(false, shizuku, adb))
        assertEquals(SecureSettingsGrant.ADB, SecureSettingsGrant.path(false, shizuku.copy(granted = false), adb))
        assertEquals(SecureSettingsGrant.SHIZUKU_PERMISSION,
            SecureSettingsGrant.path(false, shizuku.copy(granted = false), adb.copy(wirelessDebugging = false)))
    }

    @Test fun appPackageMatchesBuildIdentity() {
        assertEquals(if (BuildConfig.ROOT_RUNTIME) "io.github.rakanrkk.dsh_su" else "io.github.ccch1mneyyy.dsh_app", Proto.APP_PACKAGE)
        assertNotNull(Proto.APP_PACKAGE)
    }
}
