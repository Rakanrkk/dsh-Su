package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Drives the real overlay write path ([SuEnvGuest.writeOverlay]) against temp rootfs trees. */
class SuEnvGuestTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun guestDir(rootfs: File) = File(rootfs, "opt/dsh-app/suenv")
    private fun moduleFile(rootfs: File) = File(guestDir(rootfs), "index.mjs")
    private fun patchFile(rootfs: File) = File(guestDir(rootfs), "suenv.patch.yml")

    @Test fun writesModuleAndPatchYaml() {
        val rootfs = tmp.newFolder("rootfs")
        assertTrue(SuEnvGuest.writeOverlay(rootfs, mapOf("index.mjs" to "// v1".toByteArray())))
        assertEquals("// v1", moduleFile(rootfs).readText())
        assertEquals(SuEnvGuest.PATCH_YAML, patchFile(rootfs).readText())
    }

    @Test fun patchYamlInsertsThePluginById() {
        assertTrue(SuEnvGuest.PATCH_YAML.contains("- id: ${SuEnvGuest.PLUGIN_ID}"))
        assertTrue(SuEnvGuest.PATCH_YAML.contains("name: ${SuEnvGuest.ENTRY}"))
    }

    @Test fun rewritingIdenticalContentIsANoOp() {
        val rootfs = tmp.newFolder("rootfs")
        val bytes = "// same".toByteArray()
        assertTrue(SuEnvGuest.writeOverlay(rootfs, mapOf("index.mjs" to bytes)))
        val before = moduleFile(rootfs).readBytes()
        assertTrue(SuEnvGuest.writeOverlay(rootfs, mapOf("index.mjs" to bytes)))
        assertEquals(before.toList(), moduleFile(rootfs).readBytes().toList())
    }

    @Test fun changedModuleContentIsReplaced() {
        val rootfs = tmp.newFolder("rootfs")
        assertTrue(SuEnvGuest.writeOverlay(rootfs, mapOf("index.mjs" to "// v1".toByteArray())))
        assertTrue(SuEnvGuest.writeOverlay(rootfs, mapOf("index.mjs" to "// v2".toByteArray())))
        assertEquals("// v2", moduleFile(rootfs).readText())
    }

    @Test fun writesLeaveNoTempFiles() {
        val rootfs = tmp.newFolder("rootfs")
        assertTrue(SuEnvGuest.writeOverlay(rootfs, mapOf("index.mjs" to "// m".toByteArray())))
        assertEquals(setOf("index.mjs", "suenv.patch.yml"), guestDir(rootfs).listFiles()!!.map { it.name }.toSet())
    }

    @Test fun moduleWriteFailureLeavesNoOverlayBehind() {
        val rootfs = tmp.newFolder("rootfs")
        moduleFile(rootfs).mkdirs() // a directory where the module should be: the write cannot land
        assertFalse(SuEnvGuest.writeOverlay(rootfs, mapOf("index.mjs" to "// x".toByteArray())))
        assertFalse(patchFile(rootfs).exists())
        assertTrue(guestDir(rootfs).listFiles()!!.none { it.name.startsWith(".") })
    }

    @Test fun symlinkedGuestDirIsReplacedNotFollowed() {
        val rootfs = tmp.newFolder("rootfs")
        val outside = tmp.newFolder("outside")
        File(rootfs, "opt/dsh-app").mkdirs()
        Files.createSymbolicLink(Path.of(rootfs.path, "opt/dsh-app/suenv"), outside.toPath())
        assertTrue(SuEnvGuest.writeOverlay(rootfs, mapOf("index.mjs" to "// m".toByteArray())))
        assertFalse(Files.isSymbolicLink(Path.of(rootfs.path, "opt/dsh-app/suenv")))
        assertTrue(moduleFile(rootfs).isFile)
        assertFalse(File(outside, "index.mjs").exists())
    }

    @Test fun fileMapsTheGuestPatchPath() {
        val rootfs = tmp.newFolder("rootfs")
        assertEquals(File(rootfs, "opt/dsh-app/suenv/suenv.patch.yml").path, SuEnvGuest.file(rootfs).path)
    }
}
