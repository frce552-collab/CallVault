/*
 * ABP Call — Ploshadki.kz fork of CallVault. GPL-3.0-or-later with the Section 7 terms in LICENSE.
 * Checks the `air.wa-capture.transcript.v1` envelope against the fixed server contract.
 */
package com.baba.callvault.ploshadki

import com.baba.callvault.ploshadki.CaptureReport.NearChannel
import com.baba.callvault.ploshadki.TranscriptReport.Outcome
import com.baba.callvault.ploshadki.TranscriptReport.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptReportTest {

    private val secret = "0123456789abcdef0123456789abcdef".toByteArray()
    private val sha = "b".repeat(64)
    private val rows = listOf(
        Segment(0L, 1500L, "  Здравствуйте, это Ploshadki.  ", "B"),
        Segment(1500L, 4000L, "Добрый день, хотел узнать про площадку", "A"),
    )

    private fun envelope(
        rows: List<Segment> = this.rows,
        near: NearChannel = NearChannel.FIRST,
        summary: TranscriptReport.Summary? = null,
        language: String? = "ru",
    ) = TranscriptReport.build("abp-123", "enr-1", sha, "large-v3-turbo-q8_0", language, rows, near, summary, 1_793_008_740_000L)

    @Suppress("UNCHECKED_CAST")
    private fun segs(e: Map<String, Any?>) = e["segments"] as List<Map<String, Any?>>

    @Test
    fun `envelope has exactly the contract keys`() {
        val e = envelope()
        assertEquals(
            setOf("contractVersion", "reportId", "enrollmentRef", "mediaSha256", "engine", "language", "segments", "summary", "signedAt"),
            e.keys,
        )
        assertEquals("air.wa-capture.transcript.v1", e["contractVersion"])
        assertEquals("abp-123", e["reportId"])
        assertEquals("enr-1", e["enrollmentRef"])
        assertEquals(sha, e["mediaSha256"])
        assertEquals(mapOf("name" to "callvault-whisper", "modelId" to "large-v3-turbo-q8_0"), e["engine"])
        assertEquals("ru", e["language"])
        assertEquals("2026-10-26T09:59:00.000Z", e["signedAt"])
        segs(e).forEach { assertEquals(setOf("startMs", "endMs", "text", "speaker"), it.keys) }
        assertEquals(mapOf("startMs" to 0L, "endMs" to 1500L, "text" to "Здравствуйте, это Ploshadki.", "speaker" to "CUSTOMER"), segs(e)[0])
    }

    @Test
    fun `summary is null when there is none, and present with model and text when there is`() {
        assertTrue(envelope().containsKey("summary"))
        assertNull(envelope()["summary"])
        assertNull(envelope(summary = TranscriptReport.Summary("qwen", "   "))["summary"])
        assertEquals(
            mapOf("modelId" to "qwen", "text" to "Клиент хочет площадку на 200 гостей"),
            envelope(summary = TranscriptReport.Summary("qwen", " Клиент хочет площадку на 200 гостей "))["summary"],
        )
        val long = (envelope(summary = TranscriptReport.Summary("m", "слово ".repeat(1000)))["summary"] as Map<*, *>)["text"] as String
        assertTrue(long.length <= 4000)
        assertTrue(long.endsWith("слово"))
    }

    @Test
    fun `canonical body is transcript plus signature, keys sorted`() {
        val e = envelope()
        val sig = TranscriptReport.sign(secret, e)
        val body = TranscriptReport.body(e, sig)
        assertTrue(body.startsWith("{\"signature\":\"$sig\",\"transcript\":{\"contractVersion\":"))
        assertTrue(body.contains("\"engine\":{\"modelId\":\"large-v3-turbo-q8_0\",\"name\":\"callvault-whisper\"}"))
        assertTrue(body.contains("\"summary\":null"))
    }

    @Test
    fun `signature is the upload scheme with the empty-body hash`() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", TranscriptReport.EMPTY_BODY_SHA256)
        val e = envelope()
        val sig = TranscriptReport.sign(secret, e)
        assertTrue(Regex("^[0-9a-f]{64}$").matches(sig))
        assertEquals(AbpCrypto.signCapture(secret, e, TranscriptReport.EMPTY_BODY_SHA256), sig)
        // Independently: HMAC over canonical + "\n" + sha256hex("").
        val manual = AbpCrypto.hmacSha256Hex(
            secret,
            (AbpCrypto.canonicalJson(e) + "\n" + "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855").toByteArray(Charsets.UTF_8),
        )
        assertEquals(manual, sig)
    }

    @Test
    fun `changing one segment changes the signature`() {
        val a = TranscriptReport.sign(secret, envelope())
        val b = TranscriptReport.sign(secret, envelope(rows = rows.mapIndexed { i, s -> if (i == 1) s.copy(text = s.text + "?") else s }))
        val c = TranscriptReport.sign(secret, envelope(rows = rows.mapIndexed { i, s -> if (i == 1) s.copy(endMs = 4001L) else s }))
        val d = TranscriptReport.sign(secret, envelope(near = NearChannel.SECOND))
        assertNotEquals(a, b)
        assertNotEquals(a, c)
        assertNotEquals(a, d)
        assertEquals(a, TranscriptReport.sign(secret, envelope()))
    }

    @Test
    fun `speaker mapping follows the near channel and never guesses`() {
        assertEquals(TranscriptReport.Speaker.MANAGER, TranscriptReport.speakerOf("A", NearChannel.FIRST))
        assertEquals(TranscriptReport.Speaker.CUSTOMER, TranscriptReport.speakerOf("B", NearChannel.FIRST))
        assertEquals(TranscriptReport.Speaker.CUSTOMER, TranscriptReport.speakerOf("A", NearChannel.SECOND))
        assertEquals(TranscriptReport.Speaker.MANAGER, TranscriptReport.speakerOf("B", NearChannel.SECOND))
        assertEquals(TranscriptReport.Speaker.UNKNOWN, TranscriptReport.speakerOf("A", NearChannel.UNKNOWN))
        assertEquals(TranscriptReport.Speaker.UNKNOWN, TranscriptReport.speakerOf("B", NearChannel.UNKNOWN))
        assertEquals(TranscriptReport.Speaker.UNKNOWN, TranscriptReport.speakerOf(null, NearChannel.FIRST))
        assertEquals(TranscriptReport.Speaker.UNKNOWN, TranscriptReport.speakerOf("speaker_3", NearChannel.FIRST))
        assertEquals(listOf("CUSTOMER", "MANAGER"), segs(envelope()).map { it["speaker"] })
        assertEquals(listOf("UNKNOWN", "UNKNOWN"), segs(envelope(near = NearChannel.UNKNOWN)).map { it["speaker"] })
    }

    @Test
    fun `long text is cut at a word boundary, never mid-word`() {
        val words = "площадка ".repeat(300) // 9 chars per word, 2700 total
        val cut = TranscriptReport.cutAtWord(words, 2000)
        assertTrue(cut.length <= 2000)
        assertTrue(cut.endsWith("площадка"))
        assertEquals(cut.trim(), cut)
        assertTrue(words.startsWith(cut))
        // One "word" longer than the limit is hard-cut.
        assertEquals(2000, TranscriptReport.cutAtWord("я".repeat(2500), 2000).length)
        // A cut that already falls on a space keeps the whole last word.
        assertEquals("ab cd", TranscriptReport.cutAtWord("ab cd ef", 5))
        assertEquals("ab", TranscriptReport.cutAtWord("ab cde", 4))
        val row = segs(envelope(rows = listOf(Segment(0, 10, words, null))))[0]
        assertEquals(cut, row["text"])
    }

    @Test
    fun `invalid and empty rows are dropped, rows ordered by start, at most 5000 kept`() {
        val messy = listOf(
            Segment(3000, 4000, "третий", "A"),
            Segment(1000, 1000, "нулевая длина", "A"),
            Segment(2000, 1500, "конец раньше начала", "A"),
            Segment(500, 900, "   ", "A"),
            Segment(0, 500, "первый", "B"),
            Segment(-5, 10, "отрицательный", "B"),
        )
        assertEquals(listOf("первый", "третий"), segs(envelope(rows = messy)).map { it["text"] })
        val many = (0 until 6000).map { Segment(it * 10L, it * 10L + 5, "s$it", null) }.reversed()
        val kept = segs(envelope(rows = many))
        assertEquals(5000, kept.size)
        assertEquals("s0", kept.first()["text"])
        assertEquals("s4999", kept.last()["text"])
        val starts = kept.map { it["startMs"] as Long }
        assertEquals(starts.sorted(), starts)
        assertTrue(kept.all { (it["endMs"] as Long) > (it["startMs"] as Long) })
        assertEquals(emptyList<Any>(), segs(envelope(rows = emptyList())))
    }

    @Test
    fun `language is a code or null`() {
        assertEquals("ru", envelope(language = "ru")["language"])
        assertEquals("kk", envelope(language = "KK")["language"])
        assertNull(envelope(language = null)["language"])
        assertNull(envelope(language = "auto")["language"])
        assertNull(envelope(language = "русский")["language"])
        assertEquals("pt-br", TranscriptReport.language("pt_BR"))
    }

    @Test
    fun `no phone number or file name can reach the envelope`() {
        val body = TranscriptReport.body(envelope(), "x")
        assertFalse(body.contains("phone"))
        assertFalse(body.contains("counterparty"))
        assertFalse(body.contains("+7"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `missing model id is refused`() {
        TranscriptReport.build("abp-1", "enr-1", sha, "  ", null, rows, NearChannel.FIRST, null, 0L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `bad media hash is refused`() {
        TranscriptReport.build("abp-1", "enr-1", "ABC", "m", null, rows, NearChannel.FIRST, null, 0L)
    }

    @Test
    fun `model id is limited to 120 characters`() {
        val e = TranscriptReport.build("abp-1", "enr-1", sha, "m".repeat(200), null, rows, NearChannel.FIRST, TranscriptReport.Summary("s".repeat(200), "ok"), 0L)
        assertEquals(120, ((e["engine"] as Map<*, *>)["modelId"] as String).length)
        assertEquals(120, ((e["summary"] as Map<*, *>)["modelId"] as String).length)
    }

    @Test
    fun `server answers map to stop, retry or give up`() {
        assertEquals(Outcome.DONE, TranscriptReport.outcomeOf(200, null))
        assertEquals(Outcome.RETRY, TranscriptReport.outcomeOf(404, "RECORDING_NOT_FOUND"))
        assertEquals(Outcome.GIVE_UP, TranscriptReport.outcomeOf(409, "MEDIA_MISMATCH"))
        assertEquals(Outcome.GIVE_UP, TranscriptReport.outcomeOf(422, "SCHEMA"))
        assertEquals(Outcome.GIVE_UP, TranscriptReport.outcomeOf(401, "SIGNATURE_INVALID"))
        assertEquals(Outcome.RETRY, TranscriptReport.outcomeOf(401, "SIGNATURE_STALE"))
        assertEquals(Outcome.RETRY, TranscriptReport.outcomeOf(500, null))
        assertEquals(Outcome.RETRY, TranscriptReport.outcomeOf(503, null))
        assertEquals(Outcome.RETRY, TranscriptReport.outcomeOf(429, null))
        assertEquals(Outcome.GIVE_UP, TranscriptReport.outcomeOf(400, null))
        assertEquals(Outcome.GIVE_UP, TranscriptReport.outcomeOf(403, null))
    }
}
