package io.github.ccch1mneyyy.dsh_app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    @Test fun suenvOverlayInsertsRightAfterBasePatch() {
        val arg = "echo pid > /tmp/dsh-web.pid; exec /usr/local/bin/dsh web --patch /etc/dsh-app/dsh-web.patch.yml --host 127.0.0.1 --port 3080 --no-open"
        val out = SuEnvGuest.launchArg(arg)!!
        val base = "/etc/dsh-app/dsh-web.patch.yml"
        val inserted = " --patch " + SuEnvGuest.PATCH
        assertEquals(arg.indexOf(base) + base.length, out.indexOf(inserted))
        assertEquals(arg.length + inserted.length, out.length)
    }

    @Test fun suenvOverlayInsertsWithoutBasePatch() {
        val arg = "exec /usr/local/bin/dsh web --host 127.0.0.1"
        val out = SuEnvGuest.launchArg(arg)!!
        assertEquals(arg.indexOf("/usr/local/bin/dsh web") + "/usr/local/bin/dsh web".length, out.indexOf(" --patch " + SuEnvGuest.PATCH))
    }

    @Test fun suenvOverlayRejectsNonLaunchProfilesAndDuplicates() {
        assertNull(SuEnvGuest.launchArg("echo hi"))
        assertNull(SuEnvGuest.launchArg("exec /usr/local/bin/dsh web --profile work --host 127.0.0.1"))
        assertNull(SuEnvGuest.launchArg("exec /usr/local/bin/dsh web --host 1 /usr/local/bin/dsh web --host 2"))
        assertTrue(SuEnvGuest.file(java.io.File("/")).path.endsWith("opt/dsh-app/suenv/suenv.patch.yml"))
    }
}
