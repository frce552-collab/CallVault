/*
 * ABP Call — Ploshadki.kz fork of CallVault. GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RecorderEventsTest {

    @Test
    fun `app calls are WhatsApp Business, everything else is GSM`() {
        assertEquals(RecorderEvents.Channel.WA_BUSINESS, RecorderEvents.channelOf("20261108_101500_out_voip_WhatsApp Business.m4a"))
        assertEquals(RecorderEvents.Channel.GSM, RecorderEvents.channelOf("20261108_101500_in_+77011234567.m4a"))
    }

    @Test
    fun `an event for the bridge carries no number, name or file name`() {
        val e = RecorderEvents.RecordingEvent(
            id = "6f1c2c1e-0000-4000-8000-000000000001",
            channel = RecorderEvents.Channel.GSM,
            durationMs = 61_000L,
            state = RecorderEvents.UploadState.QUEUED,
            error = null,
            atMs = 1_762_590_000_000L,
        )
        val m = e.toMap()
        assertEquals(listOf("id", "channel", "durationMs", "state", "error", "atMs"), m.keys.toList())
        assertEquals("GSM", m["channel"])
        assertEquals("QUEUED", m["state"])
        assertEquals(61_000L, m["durationMs"])
        assertFalse(m.values.any { it is String && it.contains("+7") })
    }

    @Test
    fun `queue status has the counters the CRM shows`() {
        val m = RecorderEvents.QueueStatus(2, 1, 10, 3, "SERVER_REFUSED", 5L, true).toMap()
        assertEquals(listOf("waiting", "waitingForServer", "sentTotal", "failedTotal", "lastError", "lastErrorAtMs", "serverConnected"), m.keys.toList())
        assertEquals(2, m["waiting"])
    }

    @Test
    fun `an error reaches the host as a code, never as text`() {
        val e = RecorderEvents.RecordingEvent("x", RecorderEvents.Channel.WA_BUSINESS, null,
            RecorderEvents.UploadState.FAILED, RecorderEvents.ErrorCode.FILE_MISSING, 1L)
        assertEquals("FILE_MISSING", e.toMap()["error"])
        assertEquals(null, e.toMap()["durationMs"])
    }
}
