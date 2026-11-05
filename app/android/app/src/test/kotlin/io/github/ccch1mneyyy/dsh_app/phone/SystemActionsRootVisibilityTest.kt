package io.github.ccch1mneyyy.dsh_app.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemActionsRootVisibilityTest {
    private class Recording(private val result: RootSystemActions.Result) : RootSystemActions {
        var request: RootSystemActions.Request? = null
        var calls = 0

        override fun execute(request: RootSystemActions.Request): RootSystemActions.Result {
            calls++
            this.request = request
            return result
        }
    }

    @Test
    fun rootRequestKeepsExplicitMainTarget() {
        val request = RootSystemActions.Request.MainLaunch("com.example.app")
        assertEquals("com.example.app", request.packageName)
    }

    @Test
    fun recordingAdapterReceivesOnlyTheExplicitMainPackage() {
        val adapter = Recording(RootSystemActions.Result.Ok())
        assertEquals(RootSystemActions.Result.Ok(), adapter.execute(RootSystemActions.Request.MainLaunch("com.example.target")))
        assertEquals(1, adapter.calls)
        assertEquals(RootSystemActions.Request.MainLaunch("com.example.target"), adapter.request)
    }
}
