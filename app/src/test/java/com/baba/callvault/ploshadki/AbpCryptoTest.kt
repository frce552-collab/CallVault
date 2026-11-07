/*
 * ABP Call — Ploshadki.kz fork of CallVault. GPL-3.0-or-later with the Section 7 terms in LICENSE.
 * Vectors produced with the server's own formula (canonical JSON + HMAC-SHA256, contract #140) in Node.js.
 */
package com.baba.callvault.ploshadki

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AbpCryptoTest {

    private val meta = linkedMapOf<String, Any?>(
        "b" to 1,
        "a" to linkedMapOf("z" to null, "y" to listOf(true, false, "x"), "Ä" to "é"),
        "s" to "q\"\\\n\t\u0001/€",
        "n" to 1730000000123L,
        "A" to "upper",
    )

    @Test
    fun `canonical JSON matches the server byte for byte`() {
        assertEquals(
            "{\"A\":\"upper\",\"a\":{\"y\":[true,false,\"x\"],\"z\":null,\"Ä\":\"é\"},\"b\":1,\"n\":1730000000123,\"s\":\"q\\\"\\\\\\n\\t\\u0001/€\"}",
            AbpCrypto.canonicalJson(meta),
        )
    }

    @Test
    fun `capture signature matches the server`() {
        val bodySha = AbpCrypto.hex(AbpCrypto.sha256("hello".toByteArray()))
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", bodySha)
        assertEquals(
            "f010cdd5f87b658aa79041dd454f9b43513bdccdb3aadc05e8ad7eb6356eaae8",
            AbpCrypto.signCapture("0123456789abcdef0123456789abcdef".toByteArray(), meta, bodySha),
        )
    }

    @Test
    fun `DER ECDSA signature becomes 64-byte JOSE r and s`() {
        val r = ByteArray(32) { 0x80.toByte() }          // high bit set → DER adds a 0x00
        val s = ByteArray(31) { 0x11 }                    // short → JOSE pads to 32
        val der = byteArrayOf(0x30, (2 + 33 + 2 + 31).toByte(), 0x02, 33, 0x00) + r + byteArrayOf(0x02, 31) + s
        val jose = AbpCrypto.derToJose(der)
        assertEquals(64, jose.size)
        assertArrayEquals(r, jose.copyOfRange(0, 32))
        assertArrayEquals(byteArrayOf(0) + s, jose.copyOfRange(32, 64))
    }

    @Test
    fun `base64url has no padding and round-trips`() {
        val bytes = byteArrayOf(-1, -2, -3, 0, 1)
        val text = AbpCrypto.b64url(bytes)
        assertEquals(false, text.contains('=') || text.contains('+') || text.contains('/'))
        assertArrayEquals(bytes, AbpCrypto.b64urlDecode(text))
    }
}
