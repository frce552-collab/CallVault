/*
 * ABP Call — Ploshadki.kz fork of CallVault. GPL-3.0-or-later with the Section 7 terms in LICENSE.
 *
 * Pure JVM helpers for the open server contract (no Android, no server code):
 *  - air.wa-capture.upload.v1 (#140): canonical JSON + HMAC-SHA256 signature;
 *  - RFC 9449 DPoP / RFC 7515 JWS ES256 encoding.
 */
package com.baba.callvault.ploshadki

import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object AbpCrypto {

    fun b64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun b64urlDecode(text: String): ByteArray = Base64.getUrlDecoder().decode(text.trim().trimEnd('='))

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return out.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    fun hmacSha256Hex(secret: ByteArray, vararg parts: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        for (p in parts) mac.update(p)
        return hex(mac.doFinal())
    }

    /**
     * Device signature of an upload envelope, exactly as the server verifies it:
     * HMAC-SHA256(secret, canonical(metadata) + "\n" + sha256hex(body)).
     */
    fun signCapture(secret: ByteArray, metadata: Any?, bodySha256Hex: String): String =
        hmacSha256Hex(
            secret,
            canonicalJson(metadata).toByteArray(Charsets.UTF_8),
            "\n".toByteArray(Charsets.UTF_8),
            bodySha256Hex.toByteArray(Charsets.UTF_8),
        )

    /**
     * Canonical JSON: object keys sorted (UTF-16 order, like JS `Array.prototype.sort`), no spaces,
     * strings escaped like JS `JSON.stringify`. Only maps, lists, strings, booleans, whole numbers and null.
     */
    fun canonicalJson(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> jsonString(out, value)
            is Boolean -> out.append(value.toString())
            is Int, is Long, is Short, is Byte -> out.append(value.toString())
            is Number -> {
                val d = value.toDouble()
                require(d.isFinite() && d == Math.floor(d) && Math.abs(d) < 9.0e15) { "only whole numbers are supported" }
                out.append(d.toLong().toString())
            }
            is Map<*, *> -> {
                out.append('{')
                val keys = value.keys.map { it as String }.sorted()
                keys.forEachIndexed { i, k ->
                    if (i > 0) out.append(',')
                    jsonString(out, k)
                    out.append(':')
                    write(out, value[k])
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    write(out, v)
                }
                out.append(']')
            }
            else -> throw IllegalArgumentException("unsupported JSON value: ${value::class.java.name}")
        }
    }

    private fun jsonString(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c.code < 0x20) {
                    out.append("\\u00").append(HEX[c.code ushr 4]).append(HEX[c.code and 0x0f])
                } else {
                    out.append(c)
                }
            }
        }
        out.append('"')
    }

    /** Big-endian unsigned integer as exactly [length] bytes (JWK `x`/`y`, JWS `r`/`s`). */
    fun fixedLength(unsigned: ByteArray, length: Int): ByteArray {
        var start = 0
        while (start < unsigned.size - 1 && unsigned[start] == 0.toByte() && unsigned.size - start > length) start++
        val trimmed = unsigned.copyOfRange(start, unsigned.size)
        require(trimmed.size <= length) { "integer longer than $length bytes" }
        return ByteArray(length - trimmed.size) + trimmed
    }

    fun fixedLength(value: BigInteger, length: Int): ByteArray = fixedLength(value.toByteArray(), length)

    /** DER `SEQUENCE { INTEGER r, INTEGER s }` (what `SHA256withECDSA` returns) → JOSE `r || s`, 64 bytes for P-256. */
    fun derToJose(der: ByteArray, partLength: Int = 32): ByteArray {
        var i = 0
        require(der[i++] == 0x30.toByte()) { "not a DER sequence" }
        var seqLen = der[i++].toInt() and 0xff
        if (seqLen and 0x80 != 0) {
            val n = seqLen and 0x7f
            seqLen = 0
            repeat(n) { seqLen = (seqLen shl 8) or (der[i++].toInt() and 0xff) }
        }
        fun readInt(): ByteArray {
            require(der[i++] == 0x02.toByte()) { "not a DER integer" }
            val len = der[i++].toInt() and 0xff
            val v = der.copyOfRange(i, i + len)
            i += len
            return v
        }
        val r = readInt()
        val s = readInt()
        return fixedLength(r, partLength) + fixedLength(s, partLength)
    }
}
