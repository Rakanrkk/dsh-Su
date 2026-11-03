package io.github.ccch1mneyyy.dsh_app.phone.background

import org.junit.Assert.*
import org.junit.Test

class RootStartupPreparationTest {
    @Test fun fixedGrantsAreAttemptedEvenWhenPostWasAlreadyGrantedAndOtherGrantsFail() {
        val calls = mutableListOf<String>()
        val backend = Any()
        val result = RootStartupPreparation.prepare({ true }, { calls += "root"; backend },
            { calls += "grantSelf"; false }, { true }, { calls += "postReadback"; true })
        assertSame(backend, result)
        assertEquals(listOf("root", "grantSelf", "postReadback"), calls)
    }
    @Test fun postReadbackFailureRefusesStartup() {
        assertNull(RootStartupPreparation.prepare({ true }, { Any() }, { true }, { true }, { false }))
    }
    @Test fun unavailableBackendCannotUseGrantedPostAsRootEvidence() {
        var grants = 0
        assertNull(RootStartupPreparation.prepare<Any>({ true }, { null }, { grants++; true }, { true }, { true }))
        assertEquals(0, grants)
    }
    @Test fun cancelBeforeConnectDoesNotLaunchRoot() {
        var starts = 0
        assertNull(RootStartupPreparation.prepare({ false }, { starts++; Any() }, { true }, { true }, { true }))
        assertEquals(0, starts)
    }
    @Test fun cancellationAfterConnectOrDuringGrantPreventsContinuation() {
        var current = true
        var grants = 0
        assertNull(RootStartupPreparation.prepare({ current }, { current = false; Any() }, { grants++; true }, { true }, { true }))
        assertEquals(0, grants)
        current = true
        assertNull(RootStartupPreparation.prepare({ current }, { Any() }, { current = false; true }, { true }, { true }))
    }
    @Test fun deadBackendAfterGrantsRefusesStartup() {
        var alive = true
        assertNull(RootStartupPreparation.prepare({ true }, { Any() }, { alive = false; true }, { alive }, { true }))
    }
}
