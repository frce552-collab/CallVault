/*
 * ABP Call — Ploshadki.kz fork of CallVault. GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import com.baba.callvault.services.recording.ReadinessNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AbpStatusReportTest {

    private val almaty = ZoneId.of("Asia/Almaty")
    private val now = ZonedDateTime.of(2026, 11, 12, 14, 30, 15, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
    private val min = 60_000L

    private fun input(missed: List<AbpStatusReport.MissedCall> = emptyList(), last: Long? = null, running: Boolean = true, reason: String? = null) =
        AbpStatusReport.Input("2.4.9-abp", "2312DRA50G", running, reason, now, almaty, last, missed)

    @Test
    fun `json has exactly the contract shape`() {
        val json = AbpStatusReport.toJson(
            input(
                missed = listOf(AbpStatusReport.MissedCall(now - 5 * min, incoming = true, number = "+77011234567", durationSec = 42)),
                last = now - 60 * min, running = false, reason = "helper_dead",
            )
        )
        val tz = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), almaty).offset.id
        assertEquals(
            "{\"app\":\"abp-call\",\"version\":\"2.4.9-abp\",\"device\":\"2312DRA50G\",\"recorder\":\"stopped\"," +
                "\"reason\":\"helper_dead\",\"at\":\"2026-11-12T${hour(now)}:30:15$tz\"," +
                "\"lastRecordingAt\":\"2026-11-12T${hour(now - 60 * min)}:30:15$tz\"," +
                "\"missedCalls\":[{\"at\":\"2026-11-12T${hour(now - 5 * min)}:25:15$tz\",\"direction\":\"in\"," +
                "\"number\":\"+77011234567\",\"durationSec\":42,\"type\":\"gsm\"}]}",
            json,
        )
    }

    private fun hour(ms: Long) = "%02d".format(java.time.Instant.ofEpochMilli(ms).atZone(almaty).hour)

    @Test
    fun `running with nothing to say has null reason, null lastRecordingAt and empty list`() {
        val json = AbpStatusReport.toJson(input())
        assertTrue(json.contains("\"recorder\":\"running\""))
        assertTrue(json.contains("\"reason\":null"))
        assertTrue(json.contains("\"lastRecordingAt\":null"))
        assertTrue(json.endsWith("\"missedCalls\":[]}"))
    }

    @Test
    fun `iso carries the offset, never Z or local-only`() {
        val s = AbpStatusReport.iso(now, almaty)
        assertTrue(s, Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[+-]\d{2}:\d{2}""").matches(s))
        assertEquals("2026-11-12T14:30:15Z".length + 5, s.length)
        assertEquals("2026-11-12T16:30:15+02:00", AbpStatusReport.iso(now, ZoneId.of("+02:00")))
    }

    @Test
    fun `whatsapp and outgoing are spelled per contract`() {
        val json = AbpStatusReport.toJson(input(listOf(AbpStatusReport.MissedCall(now, false, "+7", 0, whatsapp = true))))
        assertTrue(json.contains("\"direction\":\"out\""))
        assertTrue(json.contains("\"type\":\"whatsapp\""))
        assertTrue(json.contains("\"durationSec\":0"))
    }

    @Test
    fun `strings are escaped`() {
        val json = AbpStatusReport.toJson(input().copy(device = "a\"b\\c\nd"))
        assertTrue(json.contains("\"device\":\"a\\\"b\\\\c\\nd\""))
    }

    @Test
    fun `missed calls skip ones with a recording within 2 minutes`() {
        val calls = listOf(
            AbpStatusReport.CallRow(now - 10 * min, true, "87011234567", 30),   // recording 90 s later → matched
            AbpStatusReport.CallRow(now - 30 * min, false, "+77010000001", 60), // recording 3 min later → missed
            AbpStatusReport.CallRow(now - 25 * 60 * min, true, "+77010000002", 5), // older than 24 h → out
        )
        val recs = listOf(now - 10 * min + 90_000, now - 27 * min)
        val missed = AbpStatusReport.missedCalls(calls, recs, now)
        assertEquals(1, missed.size)
        assertEquals("+77010000001", missed[0].number)
        assertFalse(missed[0].incoming)
        assertFalse(missed[0].whatsapp)
    }

    @Test
    fun `missed calls are capped at 50, newest first, also in json`() {
        val calls = (1..80).map { AbpStatusReport.CallRow(now - it * min, true, "+7701000%04d".format(it), 1) }
        val missed = AbpStatusReport.missedCalls(calls, emptyList(), now)
        assertEquals(50, missed.size)
        assertEquals(now - min, missed.first().atMs)
        val tooMany = (1..70).map { AbpStatusReport.MissedCall(now, true, "+7", 1) }
        assertEquals(50, Regex("\"direction\"").findAll(AbpStatusReport.toJson(input(tooMany))).count())
    }

    @Test
    fun `numbers are normalised to +7 and masked for logs`() {
        assertEquals("+77011234567", AbpStatusReport.normalizeNumber("8 701 123-45-67"))
        assertEquals("+77011234567", AbpStatusReport.normalizeNumber("77011234567"))
        assertEquals("+77011234567", AbpStatusReport.normalizeNumber("+7 (701) 123 45 67"))
        assertEquals("", AbpStatusReport.normalizeNumber(null))
        assertEquals("…4567", AbpStatusReport.mask("+77011234567"))
        assertEquals("…", AbpStatusReport.mask("12"))
    }

    @Test
    fun `due every 10 minutes and on every state change`() {
        val ten = 10 * min
        val k = AbpStatusReport.stateKey(true, null)
        assertTrue(AbpStatusReport.isDue(null, 0, k, now, ten))
        assertFalse(AbpStatusReport.isDue(k, now - 9 * min, k, now, ten))
        assertTrue(AbpStatusReport.isDue(k, now - ten, k, now, ten))
        assertTrue(AbpStatusReport.isDue(k, now - min, AbpStatusReport.stateKey(false, "helper_dead"), now, ten))
        assertTrue(AbpStatusReport.isDue(k, now + min, k, now, ten)) // clock moved back
    }

    @Test
    fun `reasons come from the readiness notice`() {
        assertNull(AbpStatusReport.reasonFor(ReadinessNotice.READY))
        assertEquals("helper_dead", AbpStatusReport.reasonFor(ReadinessNotice.STARTING))
        assertEquals("no_wifi_after_reboot", AbpStatusReport.reasonFor(ReadinessNotice.NEEDS_WIFI_TO_RESTART))
        assertEquals("usb_debugging_off", AbpStatusReport.reasonFor(ReadinessNotice.NEEDS_WIFI))
        ReadinessNotice.values().filter { it != ReadinessNotice.READY }.forEach {
            assertTrue(it.name, Regex("[a-z_]+").matches(AbpStatusReport.reasonFor(it)!!))
        }
    }

    @Test
    fun `hourly fallback name`() {
        assertEquals("abp-call-status-20261112-14.json", AbpStatusReport.hourlyName(now, ZoneId.of("UTC")))
        assertEquals("abp-call-status.json", AbpStatusReport.FILE_NAME)
    }

    @Test
    fun `fork flag is on with a 10 minute interval`() {
        assertTrue(PloshadkiFork.STATUS_FILE_ENABLED)
        assertEquals(10L, PloshadkiFork.STATUS_FILE_INTERVAL_MINUTES)
    }
}
