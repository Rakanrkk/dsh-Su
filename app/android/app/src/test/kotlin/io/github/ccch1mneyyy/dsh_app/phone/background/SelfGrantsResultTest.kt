package io.github.ccch1mneyyy.dsh_app.phone.background

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfGrantsResultTest {
    @Test
    fun partialFailuresAreNotACompleteGrant() {
        assertFalse(
            selfGrantComplete(
                true,
                listOf("android.permission.POST_NOTIFICATIONS", "android.permission.SYSTEM_ALERT_WINDOW"),
                selfPermissions(33),
            ),
        )
    }

    @Test
    fun cleanResponseIsComplete() {
        assertTrue(selfGrantComplete(true, emptyList(), selfPermissions(33)))
    }

    @Test
    fun fixedPermissionFailureIsRejected() {
        assertFalse(selfGrantComplete(true, listOf("android.permission.POST_NOTIFICATIONS"), selfPermissions(33)))
    }

    @Test
    fun rpcFailureIsRejected() {
        assertFalse(selfGrantComplete(false, emptyList(), selfPermissions(33)))
    }
}
