package io.github.ccch1mneyyy.dsh_app.phone.a11y

import io.github.ccch1mneyyy.dsh_app.phone.PhoneAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootMainActuatorTest {
    private class Recording(private val accepted: Boolean) : RootMainInput {
        var request: RootMainInput.Request? = null
        var calls = 0

        override fun send(request: RootMainInput.Request): Boolean {
            calls++
            this.request = request
            return accepted
        }
    }

    @Test fun nullInputKeepsA11yOnlyFallback() {
        assertEquals(RootMainActuator.Result.Unavailable, RootMainActuator(null).tapXY(3, 4, long = false))
    }

    @Test fun tapAlwaysTargetsDisplayZero() {
        val input = Recording(accepted = true)
        val result = RootMainActuator(input).tapXY(7, 9, long = true)

        assertEquals(RootMainActuator.Result.Accepted, result)
        assertEquals(1, input.calls)
        assertEquals(RootMainInput.Request(displayId = 0, x = 7, y = 9, long = true), input.request)
    }

    @Test fun keyAlwaysTargetsDisplayZero() {
        val input = Recording(accepted = true)
        val result = RootMainActuator(input).key(PhoneAction.GlobalKey.BACK)

        assertEquals(RootMainActuator.Result.Accepted, result)
        assertEquals(1, input.calls)
        assertEquals(RootMainInput.Request(displayId = 0, key = PhoneAction.GlobalKey.BACK), input.request)
    }

    @Test fun failedInputIsRejectedWithoutSilentSuccess() {
        val result = RootMainActuator(Recording(accepted = false)).tapXY(1, 2, long = false)
        assertTrue(result is RootMainActuator.Result.Rejected)
        assertEquals("Root 系统没有接受这次输入。", (result as RootMainActuator.Result.Rejected).reason)
    }

    @Test fun keyFailureUsesExplicitFailureWording() {
        val result = RootMainActuator(Recording(accepted = false)).key(PhoneAction.GlobalKey.HOME)
        assertTrue(result is RootMainActuator.Result.Rejected)
        assertEquals("Root 系统没有执行这个按键。", (result as RootMainActuator.Result.Rejected).reason)
    }

    @Test fun requestHasNoDisplayFallbackField() {
        val request = RootMainInput.Request(displayId = 0, key = PhoneAction.GlobalKey.RECENTS)
        assertEquals(0, request.displayId)
        assertEquals(null, request.x)
        assertEquals(null, request.y)
        assertFalse(request.long)
    }
}
