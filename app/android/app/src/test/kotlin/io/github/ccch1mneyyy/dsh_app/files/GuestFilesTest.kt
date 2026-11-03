package io.github.ccch1mneyyy.dsh_app.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuestFilesTest {
    @Test
    fun resolvesRelativeSymlinkFromItsGuestParent() {
        val result = GuestFiles.resolveGuestSegments("/root/link/item") { path ->
            if (path == listOf("root", "link")) "../var" else null
        }

        assertEquals(listOf("var", "item"), result)
    }

    @Test
    fun resolvesAbsoluteSymlinkFromGuestRoot() {
        val result = GuestFiles.resolveGuestSegments("/root/link/item") { path ->
            if (path == listOf("root", "link")) "/usr/share" else null
        }

        assertEquals(listOf("usr", "share", "item"), result)
    }

    @Test
    fun limitsSymlinkLoopsAndClampsParentTraversalAtGuestRoot() {
        val loop = GuestFiles.resolveGuestSegments("/loop") { path ->
            if (path == listOf("loop")) "/loop" else null
        }
        val clamped = GuestFiles.resolveGuestSegments("/../../etc/file") { null }

        assertNull(loop)
        assertEquals(listOf("etc", "file"), clamped)
    }

    @Test
    fun sharedStoragePathsDoNotUseRootExport() {
        assertEquals(false, GuestFiles.isRootfsPath("/sdcard/Download/file"))
        assertEquals(false, GuestFiles.isRootfsPath("/storage/emulated/0/file"))
        assertEquals(true, GuestFiles.isRootfsPath("/sdcard/../root/workspace/file"))
        assertEquals(true, GuestFiles.isRootfsPath("/root/workspace/file"))
    }
}
