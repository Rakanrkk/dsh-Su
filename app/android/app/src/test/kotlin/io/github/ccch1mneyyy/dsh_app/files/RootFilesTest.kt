package io.github.ccch1mneyyy.dsh_app.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootFilesTest {
    @Test fun ordinaryNonGuestPathsNeverRequestRoot() {
        assertFalse(GuestFiles.isRootfsPath("ordinary.txt"))
        assertFalse(GuestFiles.isGuestRootPath("ordinary.txt"))
    }

    @Test fun guestRootfsAbsolutePathsUseTheRootNamespaceWorkflow() {
        assertTrue(GuestFiles.isGuestRootPath("/root/protected.bin"))
        assertEquals(true, GuestFiles.isRootfsPath("/root/protected.bin"))
    }

    @Test fun sharedGuestPathsAreKeptOutOfRootfsResolution() {
        assertFalse(GuestFiles.isRootfsPath("/sdcard/Download/file"))
        assertTrue(GuestFiles.isGuestRootPath("/sdcard/Download/file"))
    }
}
