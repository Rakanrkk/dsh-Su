package io.github.ccch1mneyyy.dsh_app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RootLaunchTest {
    @Test fun bindRequiresAbsolutePaths() {
        val bind = RootLaunch.Bind("/data/local/tmp", "/data/local/tmp")
        assertEquals("/data/local/tmp", bind.host)
        assertEquals("/data/local/tmp", bind.guest)
    }

    @Test fun bindKeepsCallerSpecifiedGuest() {
        assertEquals(RootLaunch.Bind("/host/data", "/guest/data"), DshProcessRoot.bind("/host/data:/guest/data"))
        assertEquals(RootLaunch.Bind("/storage", "/storage"), DshProcessRoot.bind("/storage"))
        assertEquals(RootLaunch.Bind("/host/dir", "/guest/dir"), DshProcessRoot.bind("/host/dir/:/guest/dir/"))
    }

    @Test fun bindRejectsRootAndRelativePaths() {
        assertThrows(IllegalArgumentException::class.java) { RootLaunch.Bind("relative", "/data") }
        assertThrows(IllegalArgumentException::class.java) { RootLaunch.Bind("/data", "relative") }
        assertThrows(IllegalArgumentException::class.java) { RootLaunch.Bind("/", "/data") }
        assertThrows(IllegalArgumentException::class.java) { RootLaunch.Bind("/data", "/") }
    }
}
