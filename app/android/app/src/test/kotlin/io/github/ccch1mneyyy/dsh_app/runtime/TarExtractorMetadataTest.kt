package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Minimal metadata tests for [TarExtractor]: numeric uid/gid are consumed from the
 * ustar header (108/116) or pax keys and reported through [TarExtractor.applyOwner].
 * The archive bytes are hand-rolled so every header field is controlled.
 */
class TarExtractorMetadataTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Captures (path, uid, gid) in extraction order. */
    private val owners = mutableListOf<Triple<String, Long, Long>>()

    private fun extractor(root: File) = TarExtractor(
        root,
        applyOwner = { file, uid, gid -> owners += Triple(file.relativeTo(root).path, uid, gid) },
        onProgress = {},
    )

    /** Builds a minimal ustar archive from [entries]; no compression. */
    private fun tar(vararg entries: TarEntry): ByteArray {
        val out = ByteArrayOutputStream()
        for (entry in entries) {
            val header = ByteArray(512)
            val name = entry.name.toByteArray(Charsets.US_ASCII)
            require(name.size <= 100) { "long name not supported in these fixtures" }
            System.arraycopy(name, 0, header, 0, name.size)
            if (entry.link.isNotEmpty()) {
                val link = entry.link.toByteArray(Charsets.US_ASCII)
                require(link.size <= 100) { "long link not supported in these fixtures" }
                System.arraycopy(link, 0, header, 157, link.size)
            }
            octal(header, 100, 8, entry.mode.toLong())
            octal(header, 108, 8, entry.uid)
            octal(header, 116, 8, entry.gid)
            octal(header, 124, 12, entry.data.size.toLong())
            octal(header, 136, 12, 0) // mtime
            header[156] = entry.type[0].toByte()
            System.arraycopy(entry.uname.toByteArray(Charsets.US_ASCII), 0, header, 265, entry.uname.length)
            System.arraycopy(entry.gname.toByteArray(Charsets.US_ASCII), 0, header, 297, entry.gname.length)
            // checksum: spaces first, then the real one
            for (i in 148 until 156) header[i] = ' '.code.toByte()
            var checksum = 0L
            for (b in header) checksum += b.toLong() and 0xFF
            val octalChecksum = "0000000%06o".format(checksum).toByteArray(Charsets.US_ASCII)
            System.arraycopy(octalChecksum, octalChecksum.size - 6, header, 148, 6)
            header[154] = 0; header[155] = ' '.code.toByte()
            out.write(header)
            if (entry.data.isNotEmpty()) out.write(entry.data)
            val padding = (512 - entry.data.size % 512) % 512
            repeat(padding) { out.write(0) }
            if (entry.pax != null) throw IllegalStateException("pax entries must precede the main entry")
        }
        repeat(1024) { out.write(0) } // two zero blocks
        return out.toByteArray()
    }

    private fun octal(header: ByteArray, offset: Int, length: Int, value: Long) {
        val digits = "%0${length - 1}o".format(value).toByteArray(Charsets.US_ASCII)
        System.arraycopy(digits, 0, header, offset, length - 1)
        header[offset + length - 1] = 0
    }

    private fun base256(field: ByteArray): Long {
        val out = StringBuilder()
        for (b in field.drop(1)) {
            if (b.toInt() == 0) break
            out.append(b.toInt().toChar())
        }
        return out.toString().toLong()
    }

    private fun withBase256UidGid(header: ByteArray, uid: Long, gid: Long): ByteArray {
        val h = header.copyOf()
        // GNU base-256: high bit of first byte set, big-endian bytes after it
        val uidField = ByteArray(8)
        uidField[0] = (0x80).toByte()
        for (i in 0 until 7) uidField[i + 1] = ((uid shr ((6 - i) * 8)) and 0xFF).toByte()
        System.arraycopy(uidField, 0, h, 108, 8)
        val gidField = ByteArray(8)
        gidField[0] = (0x80).toByte()
        for (i in 0 until 7) gidField[i + 1] = ((gid shr ((6 - i) * 8)) and 0xFF).toByte()
        System.arraycopy(gidField, 0, h, 116, 8)
        // recompute checksum
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        var checksum = 0L
        for (b in h) checksum += b.toLong() and 0xFF
        val octalChecksum = "%06o".format(checksum).toByteArray(Charsets.US_ASCII)
        System.arraycopy(octalChecksum, 0, h, 148, 6)
        h[154] = 0; h[155] = ' '.code.toByte()
        return h
    }

    private fun extract(bytes: ByteArray): File {
        val root = tmp.newFolder("root")
        extractor(root).extractDecoded(bytes.inputStream())
        return root
    }

    @Test
    fun `numeric uid and gid are read from the ustar header`() {
        val root = tmp.newFolder("r1")
        val name = "opt/etc/motd"
        val header = ByteArray(512)
        val n = name.toByteArray(Charsets.US_ASCII)
        System.arraycopy(n, 0, header, 0, n.size)
        octal(header, 100, 8, 0x1A4)
        octal(header, 108, 8, 1000L)
        octal(header, 116, 8, 1001L)
        octal(header, 124, 12, 5L)
        header[156] = '0'.code.toByte()
        for (i in 148 until 156) header[i] = ' '.code.toByte()
        var checksum = 0L
        for (b in header) checksum += b.toLong() and 0xFF
        val cs = "0000000%06o".format(checksum).toByteArray(Charsets.US_ASCII)
        System.arraycopy(cs, cs.size - 6, header, 148, 6)
        header[154] = 0; header[155] = ' '.code.toByte()
        val payload = "hello".toByteArray(Charsets.US_ASCII)
        extractor(root).extractDecoded(
            (header + payload + ByteArray(512 - payload.size) + ByteArray(1024)).inputStream(),
        )
        assertEquals(listOf(Triple(name, 1000L, 1001L)), owners)
    }

    @Test
    fun `gnu base-256 large uid and gid are read`() {
        val root = tmp.newFolder("r2")
        val name = "opt/big/file"
        val header = ByteArray(512)
        val n = name.toByteArray(Charsets.US_ASCII)
        System.arraycopy(n, 0, header, 0, n.size)
        octal(header, 100, 8, 0x1A4)
        octal(header, 108, 8, 0L)
        octal(header, 116, 8, 0L)
        octal(header, 124, 12, 1L)
        header[156] = '0'.code.toByte()
        for (i in 148 until 156) header[i] = ' '.code.toByte()
        var checksum = 0L
        for (b in header) checksum += b.toLong() and 0xFF
        val cs = "0000000%06o".format(checksum).toByteArray(Charsets.US_ASCII)
        System.arraycopy(cs, cs.size - 6, header, 148, 6)
        header[154] = 0; header[155] = ' '.code.toByte()
        val big = 2_000_000_000L
        val finalHeader = withBase256UidGid(header, big, big + 1)
        val payload = "x".toByteArray(Charsets.US_ASCII)
        extractor(root).extractDecoded(
            (finalHeader + payload + ByteArray(512 - payload.size) + ByteArray(1024)).inputStream(),
        )
        assertEquals(listOf(Triple(name, big, big + 1)), owners)
    }

    @Test
    fun `uname root does not override a nonzero numeric uid`() {
        val root = tmp.newFolder("r3")
        val name = "opt/user/file"
        val header = ByteArray(512)
        val n = name.toByteArray(Charsets.US_ASCII)
        System.arraycopy(n, 0, header, 0, n.size)
        octal(header, 100, 8, 0x180)
        octal(header, 108, 8, 1500L)
        octal(header, 116, 8, 1501L)
        octal(header, 124, 12, 1L)
        header[156] = '0'.code.toByte()
        "root".toByteArray(Charsets.US_ASCII).copyInto(header, 265)
        for (i in 148 until 156) header[i] = ' '.code.toByte()
        var checksum = 0L
        for (b in header) checksum += b.toLong() and 0xFF
        val cs = "0000000%06o".format(checksum).toByteArray(Charsets.US_ASCII)
        System.arraycopy(cs, cs.size - 6, header, 148, 6)
        header[154] = 0; header[155] = ' '.code.toByte()
        val payload = "y".toByteArray(Charsets.US_ASCII)
        extractor(root).extractDecoded(
            (header + payload + ByteArray(512 - payload.size) + ByteArray(1024)).inputStream(),
        )
        assertEquals(listOf(Triple(name, 1500L, 1501L)), owners)
    }

    @Test
    fun `pax uid and gid keys override the header fields`() {
        val root = tmp.newFolder("r4")
        // pax record: "12 uid=1234\n" padded to 512; then a 1-byte regular file header
        val paxBody = "12 uid=1234\n12 gid=1235\n".toByteArray(Charsets.US_ASCII)
        val paxHeader = ByteArray(512)
        "PaxHeaders/x".toByteArray(Charsets.US_ASCII).copyInto(paxHeader, 0)
        octal(paxHeader, 100, 8, 0x1A4)
        octal(paxHeader, 108, 8, 0L)
        octal(paxHeader, 116, 8, 0L)
        octal(paxHeader, 124, 12, paxBody.size.toLong())
        paxHeader[156] = 'x'.code.toByte()
        for (i in 148 until 156) paxHeader[i] = ' '.code.toByte()
        var checksum = 0L
        for (b in paxHeader) checksum += b.toLong() and 0xFF
        val cs = "0000000%06o".format(checksum).toByteArray(Charsets.US_ASCII)
        System.arraycopy(cs, cs.size - 6, paxHeader, 148, 6)
        paxHeader[154] = 0; paxHeader[155] = ' '.code.toByte()
        val name = "opt/pax/file"
        val header = ByteArray(512)
        name.toByteArray(Charsets.US_ASCII).copyInto(header)
        octal(header, 100, 8, 0x1A4)
        octal(header, 108, 8, 1111L)
        octal(header, 116, 8, 2222L)
        octal(header, 124, 12, 1L)
        header[156] = '0'.code.toByte()
        for (i in 148 until 156) header[i] = ' '.code.toByte()
        checksum = 0L
        for (b in header) checksum += b.toLong() and 0xFF
        val cs2 = "0000000%06o".format(checksum).toByteArray(Charsets.US_ASCII)
        System.arraycopy(cs2, cs2.size - 6, header, 148, 6)
        header[154] = 0; header[155] = ' '.code.toByte()
        val payload = "z".toByteArray(Charsets.US_ASCII)
        val stream = (
            paxHeader + paxBody + ByteArray(512 - paxBody.size) +
                header + payload + ByteArray(512 - payload.size) + ByteArray(1024)
            ).inputStream()
        extractor(root).extractDecoded(stream)
        assertEquals(listOf(Triple(name, 1234L, 1235L)), owners)
    }

    @Test
    fun `legacy zero fields report root ownership`() {
        val root = tmp.newFolder("r5")
        val name = "opt/legacy/file"
        val header = ByteArray(512)
        name.toByteArray(Charsets.US_ASCII).copyInto(header)
        octal(header, 100, 8, 0x1A4)
        octal(header, 108, 8, 0L)
        octal(header, 116, 8, 0L)
        octal(header, 124, 12, 1L)
        header[156] = '0'.code.toByte()
        for (i in 148 until 156) header[i] = ' '.code.toByte()
        var checksum = 0L
        for (b in header) checksum += b.toLong() and 0xFF
        val cs = "0000000%06o".format(checksum).toByteArray(Charsets.US_ASCII)
        System.arraycopy(cs, cs.size - 6, header, 148, 6)
        header[154] = 0; header[155] = ' '.code.toByte()
        val payload = "w".toByteArray(Charsets.US_ASCII)
        extractor(root).extractDecoded(
            (header + payload + ByteArray(512 - payload.size) + ByteArray(1024)).inputStream(),
        )
        assertEquals(listOf(Triple(name, 0L, 0L)), owners)
    }

    @Test
    fun `dir entries carry their header uid and gid`() {
        val bytes = tar(TarEntry("opt/dir", type = "5", mode = 0x1EDL, uid = 400L, gid = 500L))
        val root = extract(bytes)
        assertEquals(listOf(Triple("opt/dir", 400L, 500L)), owners)
        assertTrue(File(root, "opt/dir").isDirectory)
    }

    @Test
    fun `ownership lands after child io hardlink copies and dir modes`() {
        val root = tmp.newFolder("r8")
        val applied = mutableListOf<Pair<Triple<String, Long, Long>, Boolean>>()
        val child = File(root, "opt/dir/file")
        TarExtractor(
            root,
            applyOwner = { file, uid, gid ->
                applied += Triple(file.relativeTo(root).path, uid, gid) to child.isFile
            },
            onProgress = {},
        ).extractDecoded(
            tar(
                TarEntry("opt/dir", type = "5", mode = 0x1EDL, uid = 1000L, gid = 1001L),
                TarEntry(
                    "opt/dir/file",
                    type = "0",
                    data = "hi".toByteArray(Charsets.US_ASCII),
                    mode = 0x180L,
                    uid = 1000L,
                    gid = 1001L,
                ),
                TarEntry("opt/dir/link", type = "1", link = "opt/dir/file", uid = 2000L, gid = 2001L),
            ).inputStream(),
        )
        // Children first, parents last: the app UID kept write access while the
        // extractor created the child, copied the hardlink source and set modes.
        assertEquals(
            listOf(
                Triple("opt/dir/link", 2000L, 2001L),
                Triple("opt/dir/file", 1000L, 1001L),
                Triple("opt/dir", 1000L, 1001L),
            ),
            applied.map { it.first },
        )
        // every ownership application happened after the child file was on disk
        assertTrue(applied.all { it.second })
        // the hardlink copy completed before any ownership was applied
        assertEquals("hi", File(root, "opt/dir/link").readText())
    }

    @Test
    fun `stale records are revalidated against the filesystem before ownership`() {
        val root = tmp.newFolder("r9")
        val outside = tmp.newFolder("outside")
        val applied = mutableListOf<Triple<String, Long, Long>>()
        var mutated = false
        val thrown =
            assertThrows(IOException::class.java) {
                TarExtractor(
                    root,
                    applyOwner = { file, uid, gid ->
                        applied += Triple(file.relativeTo(root).path, uid, gid)
                        // after the first application, redefine a deferred record's
                        // parent: opt/dir becomes a symlink pointing outside staging
                        if (!mutated) {
                            val dir = File(root, "opt/dir")
                            dir.deleteRecursively()
                            java.nio.file.Files.createSymbolicLink(dir.toPath(), outside.toPath())
                            mutated = true
                        }
                    },
                    onProgress = {},
                ).extractDecoded(
                    tar(
                        TarEntry("opt/dir", type = "5", mode = 0x1EDL, uid = 1000L, gid = 1001L),
                        TarEntry(
                            "opt/dir/file",
                            type = "0",
                            data = "hi".toByteArray(Charsets.US_ASCII),
                            uid = 2000L,
                            gid = 2001L,
                        ),
                        TarEntry(
                            "opt/keep",
                            type = "0",
                            data = "k".toByteArray(Charsets.US_ASCII),
                            uid = 3000L,
                            gid = 3001L,
                        ),
                    ).inputStream(),
                )
            }
        // children-first order applies opt/keep first; re-checking opt/dir/file then
        // finds its parent re-homed outside staging and the extraction aborts so an
        // owner-incomplete candidate can never be activated
        assertEquals(listOf(Triple("opt/keep", 3000L, 3001L)), applied)
        assertTrue(mutated)
        assertTrue(thrown.message!!.contains("deferred ownership"))
    }

    @Test
    fun `legal dangling leaf symlinks keep their ownership`() {
        val root = tmp.newFolder("r10")
        val outside = tmp.newFolder("outside2")
        val applied = mutableListOf<Triple<String, Long, Long>>()
        var mutated = false
        TarExtractor(
            root,
            applyOwner = { file, uid, gid ->
                applied += Triple(file.relativeTo(root).path, uid, gid)
                // after the first application, turn a deferred leaf into a dangling
                // symlink pointing outside the staging root
                if (!mutated) {
                    val leaf = File(root, "opt/dir/file")
                    leaf.delete()
                    java.nio.file.Files.createSymbolicLink(leaf.toPath(), File(outside, "gone").toPath())
                    mutated = true
                }
            },
            onProgress = {},
        ).extractDecoded(
            tar(
                TarEntry("opt/dir", type = "5", mode = 0x1EDL, uid = 1000L, gid = 1001L),
                TarEntry(
                    "opt/dir/file",
                    type = "0",
                    data = "hi".toByteArray(Charsets.US_ASCII),
                    uid = 2000L,
                    gid = 2001L,
                ),
                TarEntry(
                    "opt/keep",
                    type = "0",
                    data = "k".toByteArray(Charsets.US_ASCII),
                    uid = 3000L,
                    gid = 3001L,
                ),
            ).inputStream(),
        )
        // children-first: opt/keep applied, then opt/dir/file — its parent is still
        // the real opt/dir and the leaf itself is a symlink, so ownership is applied
        // to the link (no-follow) instead of aborting; the dir record follows normally
        assertEquals(
            listOf(
                Triple("opt/keep", 3000L, 3001L),
                Triple("opt/dir/file", 2000L, 2001L),
                Triple("opt/dir", 1000L, 1001L),
            ),
            applied,
        )
        assertTrue(java.nio.file.Files.isSymbolicLink(File(root, "opt/dir/file").toPath()))
        assertTrue(mutated)
    }

    @Test
    fun `escaped paths never reach the owner callback`() {
        val root = tmp.newFolder("r7")
        val header = ByteArray(512)
        "../evil".toByteArray(Charsets.US_ASCII).copyInto(header)
        octal(header, 100, 8, 0x1A4)
        octal(header, 108, 8, 999L)
        octal(header, 116, 8, 999L)
        octal(header, 124, 12, 1L)
        header[156] = '0'.code.toByte()
        for (i in 148 until 156) header[i] = ' '.code.toByte()
        var checksum = 0L
        for (b in header) checksum += b.toLong() and 0xFF
        val cs = "0000000%06o".format(checksum).toByteArray(Charsets.US_ASCII)
        System.arraycopy(cs, cs.size - 6, header, 148, 6)
        header[154] = 0; header[155] = ' '.code.toByte()
        val payload = "e".toByteArray(Charsets.US_ASCII)
        extractor(root).extractDecoded(
            (header + payload + ByteArray(512 - payload.size) + ByteArray(1024)).inputStream(),
        )
        assertTrue(owners.isEmpty())
    }
}

private class TarEntry(
    val name: String,
    val type: String,
    val link: String = "",
    val data: ByteArray = ByteArray(0),
    val mode: Long = 0x1A4,
    val uid: Long = 0,
    val gid: Long = 0,
    val uname: String = "",
    val gname: String = "",
    val pax: Map<String, String>? = null,
)
