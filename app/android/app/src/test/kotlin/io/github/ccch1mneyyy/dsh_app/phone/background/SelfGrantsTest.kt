package io.github.ccch1mneyyy.dsh_app.phone.background

import org.junit.Assert.assertEquals
import org.junit.Test

class SelfGrantsTest {
    private val base = listOf(
        "android.permission.WRITE_SECURE_SETTINGS",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.RECORD_AUDIO",
        "android.permission.SYSTEM_ALERT_WINDOW",
    )

    @Test fun sdk26KeepsTheFixedFivePermissions() {
        assertEquals(base, selfPermissions(26))
    }

    @Test fun sdk28KeepsTheFixedFivePermissions() {
        assertEquals(base, selfPermissions(28))
    }

    @Test fun sdk32KeepsTheFixedFivePermissions() {
        assertEquals(base, selfPermissions(32))
    }

    @Test fun sdk33AddsNotificationsLast() {
        assertEquals(base + "android.permission.POST_NOTIFICATIONS", selfPermissions(33))
    }
}
