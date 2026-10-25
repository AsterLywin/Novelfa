package com.novelfa.reader.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.zip.Deflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted-PDF tests. The encrypt side below is written independently
 * from the spec (not by calling the production code), so a passing
 * round-trip means the two implementations agree — the strongest check
 * available offline, since no qpdf/pikepdf exists in this environment
 * to produce a third-party fixture.
 *
 * Fixed salts/keys/IVs keep every fixture deterministic.
 */
class PdfEncryptionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun fallback(n: Int) = "Section $n"

    // ---------- independent test-side crypto ----------

    private fun md5(vararg parts: ByteArray): ByteArray {
        val d = MessageDigest.getInstance("MD5")
        for (p in parts) d.update(p)
        return d.digest()
    }

    private fun sha256(vararg parts: ByteArray): ByteArray {
        val d = MessageDigest.getInstance("SHA-256")
        for (p in parts) d.update(p)
        return d.digest()
    }

    private fun rc4(key: ByteArray, data: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
        }
        val out = ByteArray(data.size)
        var x = 0
        var y = 0
        for (n in data.indices) {
            x = (x + 1) and 0xFF
            y = (y + s[x]) and 0xFF
            val t = s[x]; s[x] = s[y]; s[y] = t
            out[n] = (data[n].toInt() xor s[(s[x] + s[y]) and 0xFF]).toByte()
        }
        return out
    }

    private fun aesEcb(key: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray {
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return c.doFinal(data)
    }

    private fun aesCbc(key: ByteArray, iv: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray {
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(
            if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"), IvParameterSpec(iv)
        )
        return c.doFinal(data)
    }

    /** AES-CBC with a fixed IV prefix and PKCS#7 padding (AESV2/AESV3 layout). */
    private fun aesWithIv(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val pad = 16 - (data.size % 16)
        val padded = data + ByteArray(pad) { pad.toByte() }
        return iv + aesCbc(key, iv, padded, true)
    }

    private val PADDING: ByteArray = intArrayOf(
        0x28, 0xBF, 0x4E, 0x5E, 0x4E, 0x75, 0x8A, 0x41,
        0x64, 0x00, 0x4E, 0x56, 0xFF, 0xFA, 0x01, 0x08,
        0x2E, 0x2E, 0x00, 0xB6, 0xD0, 0x68, 0x3E, 0x80,
        0x2F, 0x0C, 0xA9, 0xFE, 0x64, 0x53, 0x69, 0x7A
    ).map { it.toByte() }.toByteArray()

    private fun pad32(pw: ByteArray): ByteArray {
        val t = pw.copyOf(minOf(pw.size, 32))
        return if (t.size == 32) t else t + PADDING.copyOf(32 - t.size)
    }

    /** R6 iterative hash (Algorithm 2.B), written from the spec text. */
    private fun r6Hash(pw: ByteArray, salt: ByteArray, userKey: ByteArray): ByteArray {
        var k = sha256(pw, salt, userKey)
        var e = ByteArray(0)
        var round = 0
        while (true) {
            val base = pw + k + userKey
            val k1 = ByteArray(base.size * 64)
            for (i in 0 until 64) base.copyInto(k1, i * base.size)
            e = aesCbc(k.copyOfRange(0, 16), k.copyOfRange(16, 32), k1, true)
            var rem = 0
            for (i in 0 until 16) rem = (rem * 256 + (e[i].toInt() and 0xFF)) % 3
            k = when (rem) {
                0 -> sha256(e)
                1 -> MessageDigest.getInstance("SHA-384").digest(e)
                else -> MessageDigest.getInstance("SHA-512").digest(e)
            }
            round++
            if (round >= 64 && (e[e.size - 1].toInt() and 0xFF) <= round - 32) break
        }
        return k.copyOf(32)
    }

    private fun le32(v: Int): ByteArray = byteArrayOf(
        v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()
    )

    private fun hex(b: ByteArray): String =
        b.joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xFF) }

    private fun flate(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            out.write(buf, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    // ---------- R2/R3/R4 key material ----------

    /** R2 O: single RC4 pass. R3/R4 O: 20 passes with XORed keys. */
    private fun rO(userPw: String, ownerPw: String, iterations: Int): ByteArray {
        val key = md5(pad32(ownerPw.toByteArray(Charsets.UTF_8))).copyOf(5)
        var x = pad32(userPw.toByteArray(Charsets.UTF_8))
        for (i in 0 until iterations) {
            val k = ByteArray(5) { (key[it].toInt() xor i).toByte() }
            x = rc4(k, x)
        }
        return x
    }

    private fun rDigest(
        userPw: String, o: ByteArray, p: Int, id0: ByteArray, meta: ByteArray
    ): ByteArray = md5(
        pad32(userPw.toByteArray(Charsets.UTF_8)), o, le32(p), id0, meta
    )

    private fun r34U(
        userPw: String, o: ByteArray, p: Int, id0: ByteArray, meta: ByteArray, keyLen: Int
    ): ByteArray {
        val digest = rDigest(userPw, o, p, id0, meta)
        var x = digest.copyOf()
        for (i in 0 until 20) {
            val k = ByteArray(keyLen) { (digest[it].toInt() xor i).toByte() }
            x = rc4(k, x)
        }
        return x.copyOf(16) + ByteArray(16)
    }

    private fun rObjKey(
        fileKey: ByteArray, objNum: Int, keyLen: Int, aesSalt: Boolean
    ): ByteArray {
        val mat = fileKey +
            byteArrayOf(
                (objNum and 0xFF).toByte(), ((objNum shr 8) and 0xFF).toByte(),
                ((objNum shr 16) and 0xFF).toByte()
            ) +
            byteArrayOf(0, 0) +
            if (aesSalt) "sAlT".toByteArray(Charsets.ISO_8859_1) else ByteArray(0)
        return md5(mat).copyOf(minOf(keyLen + 5, 16))
    }

    // ---------- R5/R6 key material ----------

    private data class R56(
        val u: ByteArray, val ue: ByteArray, val o: ByteArray,
        val oe: ByteArray, val perms: ByteArray, val fileKey: ByteArray
    )

    private fun r56(userPw: String, ownerPw: String, isR6: Boolean, p: Int): R56 {
        val fileKey = ByteArray(32) { it.toByte() }
        fun hash(pw: ByteArray, salt: ByteArray, userKey: ByteArray): ByteArray =
            if (isR6) r6Hash(pw, salt, userKey) else sha256(pw, salt, userKey)
        val uValSalt = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val uKeySalt = byteArrayOf(11, 12, 13, 14, 15, 16, 17, 18)
        val u48 = hash(userPw.toByteArray(Charsets.UTF_8), uValSalt, ByteArray(0)) +
            uValSalt + uKeySalt
        val ue = aesEcb(
            hash(userPw.toByteArray(Charsets.UTF_8), uKeySalt, ByteArray(0)), fileKey, true
        )
        val oValSalt = byteArrayOf(21, 22, 23, 24, 25, 26, 27, 28)
        val oKeySalt = byteArrayOf(31, 32, 33, 34, 35, 36, 37, 38)
        val o48 = hash(ownerPw.toByteArray(Charsets.UTF_8), oValSalt, u48) +
            oValSalt + oKeySalt
        val oe = aesEcb(
            hash(ownerPw.toByteArray(Charsets.UTF_8), oKeySalt, u48), fileKey, true
        )
        val permsPlain = le32(p) +
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()) +
            byteArrayOf(0x54, 0x61, 0x64, 0x62) +
            byteArrayOf(1, 2, 3, 4)
        val perms = aesEcb(fileKey, permsPlain, true)
        return R56(u48, ue, o48, oe, perms, fileKey)
    }

    // ---------- fixture builder ----------

    private val ID0 = "test-id-00000001".toByteArray(Charsets.ISO_8859_1)
    private val ID1 = "test-id-00000002".toByteArray(Charsets.ISO_8859_1)
    private val FIXED_IV = ByteArray(16) { (0x10 + it).toByte() }

    private data class EncSpec(
        val encryptDict: String,
        val encryptString: (ByteArray, Int) -> ByteArray,
        val encryptStream: (ByteArray, Int) -> ByteArray,
        val flatePage1: Boolean = false
    )

    private fun streamObj(dict: String, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(dict.toByteArray(Charsets.ISO_8859_1))
        out.write("\nstream\n".toByteArray(Charsets.ISO_8859_1))
        out.write(content)
        out.write("\nendstream".toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    private fun buildPdf(objects: Map<Int, ByteArray>, trailerExtra: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.4\n".toByteArray(Charsets.ISO_8859_1))
        val offsets = mutableMapOf<Int, Int>()
        for ((num, body) in objects.toSortedMap()) {
            offsets[num] = out.size()
            out.write("$num 0 obj\n".toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            out.write("\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        }
        val xrefPos = out.size()
        val max = objects.keys.max()
        out.write("xref\n".toByteArray(Charsets.ISO_8859_1))
        out.write("0 ${max + 1}\n".toByteArray(Charsets.ISO_8859_1))
        out.write("0000000000 65535 f \n".toByteArray(Charsets.ISO_8859_1))
        for (n in 1..max) {
            val off = offsets[n] ?: 0
            out.write(
                String.format(Locale.US, "%010d 00000 n \n", off)
                    .toByteArray(Charsets.ISO_8859_1)
            )
        }
        out.write(
            "trailer\n<< /Size ${max + 1} /Root 1 0 R $trailerExtra >>\nstartxref\n$xrefPos\n%%EOF\n"
                .toByteArray(Charsets.ISO_8859_1)
        )
        return out.toByteArray()
    }

    private fun encryptedPdf(spec: EncSpec): ByteArray {
        fun hexStr(b: ByteArray) = "<${hex(b)}>"
        val title1 = spec.encryptString("Chapter One".toByteArray(Charsets.ISO_8859_1), 9)
        val title2 = spec.encryptString("Chapter Two".toByteArray(Charsets.ISO_8859_1), 10)
        val infoTitle = spec.encryptString("Secret Book".toByteArray(Charsets.ISO_8859_1), 11)
        var c1 = "BT /F1 12 Tf (Hello one) Tj ET".toByteArray(Charsets.ISO_8859_1)
        if (spec.flatePage1) c1 = flate(c1)
        val c1enc = spec.encryptStream(c1, 6)
        val c2enc = spec.encryptStream(
            "BT /F1 12 Tf (Hello two) Tj ET".toByteArray(Charsets.ISO_8859_1), 7
        )
        val objects = mutableMapOf<Int, ByteArray>()
        fun txt(s: String) = s.toByteArray(Charsets.ISO_8859_1)
        objects[1] = txt("<< /Type /Catalog /Pages 2 0 R /Outlines 8 0 R >>")
        objects[2] = txt("<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>")
        objects[3] = txt(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                "/Resources << /Font << /F1 5 0 R >> >> /Contents 6 0 R >>"
        )
        objects[4] = txt(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                "/Resources << /Font << /F1 5 0 R >> >> /Contents 7 0 R >>"
        )
        objects[5] = txt(
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"
        )
        val c1Dict = "<< /Length ${c1enc.size}" +
            (if (spec.flatePage1) " /Filter /FlateDecode" else "") + " >>"
        objects[6] = streamObj(c1Dict, c1enc)
        objects[7] = streamObj("<< /Length ${c2enc.size} >>", c2enc)
        objects[8] = txt("<< /Type /Outlines /First 9 0 R /Last 10 0 R /Count 2 >>")
        objects[9] = txt(
            "<< /Title ${hexStr(title1)} /Parent 8 0 R /Next 10 0 R /Dest [3 0 R /XYZ 0 700 0] >>"
        )
        objects[10] = txt(
            "<< /Title ${hexStr(title2)} /Parent 8 0 R /Prev 9 0 R /Dest [4 0 R /XYZ 0 700 0] >>"
        )
        objects[11] = txt("<< /Title ${hexStr(infoTitle)} >>")
        objects[12] = txt(spec.encryptDict)
        return buildPdf(
            objects,
            "/Info 11 0 R /Encrypt 12 0 R /ID [<${hex(ID0)}><${hex(ID1)}>]"
        )
    }

    private fun assertBookContent(pdf: ByteArray, password: String?) {
        // A fresh folder per call: several tests parse the same fixture
        // more than once (user password, owner password, decrypted copy).
        val out = tempFolder.newFolder()
        val book = PdfParser.parse(pdf, "book.pdf", out, ::fallback, password)
        assertEquals("Secret Book", book.title)
        assertEquals(listOf("Chapter One", "Chapter Two"), book.chapters.map { it.title })
        assertTrue(book.chapters[0].file.readText().contains("Hello one"))
        assertTrue(book.chapters[1].file.readText().contains("Hello two"))
    }

    // ---------- tests ----------

    @Test
    fun r2_rc4_emptyUserPassword() {
        val p = -44
        val o = rO("", "ownerpw", iterations = 1)
        val fileKey = rDigest("", o, p, ID0, ByteArray(0)).copyOf(5)
        val u = rc4(fileKey, PADDING)
        val encDict = "<< /Filter /Standard /V 1 /R 2 " +
            "/O <${hex(o)}> /U <${hex(u)}> /P $p >>"
        val spec = EncSpec(
            encryptDict = encDict,
            encryptString = { data, obj -> rc4(rObjKey(fileKey, obj, 5, false), data) },
            encryptStream = { data, obj -> rc4(rObjKey(fileKey, obj, 5, false), data) }
        )
        val pdf = encryptedPdf(spec)
        // Level 1: empty user password opens with no password argument.
        assertBookContent(pdf, null)
        assertNotNull(PdfParser.decryptToPlainPdf(pdf, null))
    }

    @Test
    fun r3_rc4_userPassword_promptFlow() {
        val p = -44
        val o = rO("userpw", "ownerpw", iterations = 20)
        val meta = ByteArray(0)
        val fileKey = rDigest("userpw", o, p, ID0, meta).copyOf(16)
        val u = r34U("userpw", o, p, ID0, meta, 16)
        val encDict = "<< /Filter /Standard /V 2 /R 3 /Length 128 " +
            "/O <${hex(o)}> /U <${hex(u)}> /P $p >>"
        val spec = EncSpec(
            encryptDict = encDict,
            encryptString = { data, obj -> rc4(rObjKey(fileKey, obj, 16, false), data) },
            encryptStream = { data, obj -> rc4(rObjKey(fileKey, obj, 16, false), data) }
        )
        val pdf = encryptedPdf(spec)
        try {
            PdfParser.parse(pdf, "book.pdf", tempFolder.newFolder("o1"), ::fallback, null)
            fail("expected PdfPasswordRequiredException")
        } catch (e: PdfPasswordRequiredException) {
            // Expected: user password required.
        }
        try {
            PdfParser.parse(pdf, "book.pdf", tempFolder.newFolder("o2"), ::fallback, "wrongpw")
            fail("expected PdfPasswordWrongException")
        } catch (e: PdfPasswordWrongException) {
            // Expected.
        }
        assertBookContent(pdf, "userpw")
        // Decrypted storage: no /Encrypt left, parses without a password,
        // and carries the same chapters.
        val plain = PdfParser.decryptToPlainPdf(pdf, "userpw")
        assertNotNull(plain)
        assertFalse(String(plain!!, Charsets.ISO_8859_1).contains("/Encrypt"))
        assertBookContent(plain, null)
        try {
            PdfParser.decryptToPlainPdf(pdf, null)
            fail("expected PdfPasswordRequiredException")
        } catch (e: PdfPasswordRequiredException) {
            // Expected.
        }
    }

    @Test
    fun r4_aesv2_withFlateAndMetadataFlag() {
        val p = -44
        val o = rO("userpw", "ownerpw", iterations = 20)
        val meta = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val fileKey = rDigest("userpw", o, p, ID0, meta).copyOf(16)
        val u = r34U("userpw", o, p, ID0, meta, 16)
        val encDict = "<< /Filter /Standard /V 4 /R 4 /Length 128 " +
            "/O <${hex(o)}> /U <${hex(u)}> /P $p /EncryptMetadata false " +
            "/CF << /StdCF << /AuthEvent /DocOpen /CFM /AESV2 /Length 16 >> >> " +
            "/StrF /StdCF /StmF /StdCF >>"
        val spec = EncSpec(
            encryptDict = encDict,
            encryptString = { data, obj -> aesWithIv(rObjKey(fileKey, obj, 16, true), FIXED_IV, data) },
            encryptStream = { data, obj -> aesWithIv(rObjKey(fileKey, obj, 16, true), FIXED_IV, data) },
            flatePage1 = true
        )
        // Flate first, then AES: proves decrypt-before-inflate order.
        assertBookContent(encryptedPdf(spec), "userpw")
    }

    @Test
    fun r5_aes256_userAndOwnerPasswords() {
        val p = -44
        val m = r56("userpw", "ownerpw", isR6 = false, p)
        val encDict = "<< /Filter /Standard /V 5 /R 5 /Length 256 " +
            "/O <${hex(m.o)}> /U <${hex(m.u)}> /OE <${hex(m.oe)}> /UE <${hex(m.ue)}> " +
            "/Perms <${hex(m.perms)}> /P $p " +
            "/CF << /StdCF << /AuthEvent /DocOpen /CFM /AESV3 /Length 32 >> >> " +
            "/StrF /StdCF /StmF /StdCF >>"
        val spec = EncSpec(
            encryptDict = encDict,
            encryptString = { data, _ -> aesWithIv(m.fileKey, FIXED_IV, data) },
            encryptStream = { data, _ -> aesWithIv(m.fileKey, FIXED_IV, data) }
        )
        val pdf = encryptedPdf(spec)
        assertBookContent(pdf, "userpw")
        // An owner password also opens the document.
        assertBookContent(pdf, "ownerpw")
        try {
            PdfParser.parse(pdf, "book.pdf", tempFolder.newFolder("o3"), ::fallback, "wrongpw")
            fail("expected PdfPasswordWrongException")
        } catch (e: PdfPasswordWrongException) {
            // Expected.
        }
        val plain = PdfParser.decryptToPlainPdf(pdf, "userpw")
        assertNotNull(plain)
        assertBookContent(plain!!, null)
    }

    @Test
    fun r6_aes256_userAndOwnerPasswords() {
        val p = -44
        val m = r56("userpw", "ownerpw", isR6 = true, p)
        val encDict = "<< /Filter /Standard /V 5 /R 6 /Length 256 " +
            "/O <${hex(m.o)}> /U <${hex(m.u)}> /OE <${hex(m.oe)}> /UE <${hex(m.ue)}> " +
            "/Perms <${hex(m.perms)}> /P $p " +
            "/CF << /StdCF << /AuthEvent /DocOpen /CFM /AESV3 /Length 32 >> >> " +
            "/StrF /StdCF /StmF /StdCF >>"
        val spec = EncSpec(
            encryptDict = encDict,
            encryptString = { data, _ -> aesWithIv(m.fileKey, FIXED_IV, data) },
            encryptStream = { data, _ -> aesWithIv(m.fileKey, FIXED_IV, data) }
        )
        val pdf = encryptedPdf(spec)
        assertBookContent(pdf, "userpw")
        assertBookContent(pdf, "ownerpw")
        try {
            PdfParser.parse(pdf, "book.pdf", tempFolder.newFolder("o4"), ::fallback, "wrongpw")
            fail("expected PdfPasswordWrongException")
        } catch (e: PdfPasswordWrongException) {
            // Expected.
        }
        val plain = PdfParser.decryptToPlainPdf(pdf, "ownerpw")
        assertNotNull(plain)
        assertBookContent(plain!!, null)
    }

    @Test
    fun nonStandardFilter_unsupported() {
        val dummy = ByteArray(32)
        SecureRandom().nextBytes(dummy)
        val encDict = "<< /Filter /CustomDRM /V 4 /R 4 /Length 128 " +
            "/O <${hex(dummy)}> /U <${hex(dummy)}> /P -44 >>"
        val spec = EncSpec(
            encryptDict = encDict,
            encryptString = { data, _ -> data },
            encryptStream = { data, _ -> data }
        )
        val pdf = encryptedPdf(spec)
        try {
            PdfParser.decryptToPlainPdf(pdf, "whatever")
            fail("expected PdfUnsupportedEncryptionException")
        } catch (e: PdfUnsupportedEncryptionException) {
            assertTrue(e.message!!.contains("Standard"))
        }
    }
}
