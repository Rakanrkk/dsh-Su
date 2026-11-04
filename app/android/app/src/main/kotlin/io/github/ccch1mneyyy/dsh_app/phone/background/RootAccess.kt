package io.github.ccch1mneyyy.dsh_app.phone.background

import android.Manifest
import android.os.Build

/** Stable setup item names shared with Dart through exception details. */
internal object RootAccessItems {
    const val POST_NOTIFICATIONS = "notifications"
    const val RECORD_AUDIO = "recordAudio"
    const val WRITE_SECURE_SETTINGS = "writeSecureSettings"
    const val STORAGE_ACCESS = "storageAccess"
    const val OVERLAY = "overlay"
    const val INSTALL_PACKAGES = "installPackages"
    const val BATTERY_EXEMPTION = "batteryExemption"
}

/** What this App itself needs after the person allowed Root. Unsupported Android versions yield no item. */
internal fun rootAccessRequired(sdkInt: Int): Map<String, String> = buildMap {
    if (sdkInt >= 33) put(RootAccessItems.POST_NOTIFICATIONS, Manifest.permission.POST_NOTIFICATIONS)
    put(RootAccessItems.RECORD_AUDIO, Manifest.permission.RECORD_AUDIO)
    put(RootAccessItems.WRITE_SECURE_SETTINGS, SecureSettingsGrant.PERMISSION)
    put(RootAccessItems.STORAGE_ACCESS, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    put(RootAccessItems.OVERLAY, Manifest.permission.SYSTEM_ALERT_WINDOW)
    if (sdkInt >= 26) put(RootAccessItems.INSTALL_PACKAGES, Manifest.permission.REQUEST_INSTALL_PACKAGES)
    if (sdkInt >= 23) put(RootAccessItems.BATTERY_EXEMPTION, "deviceIdleWhitelist")
}

/** Real OS readback decides [RootAccessSnapshot.configured]; the RPC result never overrides it. */
internal fun rootAccessMissing(
    sdkInt: Int,
    notificationsGranted: Boolean,
    permissionGranted: (String) -> Boolean,
    storageGranted: Boolean,
    overlayGranted: Boolean,
    canRequestPackageInstalls: Boolean,
    ignoresBatteryOptimizations: Boolean,
): List<String> = rootAccessRequired(sdkInt).mapNotNull { (item, permission) ->
    val granted = when (item) {
        RootAccessItems.POST_NOTIFICATIONS -> notificationsGranted
        RootAccessItems.STORAGE_ACCESS -> storageGranted
        RootAccessItems.OVERLAY -> overlayGranted
        RootAccessItems.INSTALL_PACKAGES -> canRequestPackageInstalls
        RootAccessItems.BATTERY_EXEMPTION -> ignoresBatteryOptimizations
        else -> permissionGranted(permission)
    }
    if (granted) null else item
}

internal data class RootAccessSnapshot(
    val rootConnected: Boolean,
    val missing: List<String>,
    val configured: Boolean,
) {
    fun wire(): Map<String, Any?> = mapOf(
        "rootConnected" to rootConnected,
        "missing" to missing,
        "configured" to configured,
    )
}

internal fun rootAccessSnapshot(
    sdkInt: Int = Build.VERSION.SDK_INT,
    rootConnected: Boolean,
    notificationsGranted: Boolean,
    permissionGranted: (String) -> Boolean,
    storageGranted: Boolean,
    overlayGranted: Boolean,
    canRequestPackageInstalls: Boolean,
    ignoresBatteryOptimizations: Boolean,
): RootAccessSnapshot {
    val missing = rootAccessMissing(
        sdkInt,
        notificationsGranted,
        permissionGranted,
        storageGranted,
        overlayGranted,
        canRequestPackageInstalls,
        ignoresBatteryOptimizations,
    )
    return RootAccessSnapshot(rootConnected, missing, missing.isEmpty())
}

internal fun rootAccessFailureReason(rootReason: String?): String = when (rootReason) {
    "rootTimeout" -> "timeout"
    "rootCancelled" -> "cancelled"
    "rootIdentityMismatch" -> "identityMismatch"
    "rootDisconnected" -> "disconnected"
    "rootGrantFailed" -> "grantFailed"
    null -> "unknown"
    else -> rootReason
}
