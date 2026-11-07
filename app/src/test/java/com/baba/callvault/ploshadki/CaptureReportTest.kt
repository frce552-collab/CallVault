/*
 * ABP Call — Ploshadki.kz fork of CallVault. GPL-3.0-or-later with the Section 7 terms in LICENSE.
 * Checks the envelope against the server's rules (#139 schema + #140 admission) that a phone can break.
 */
package com.baba.callvault.ploshadki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureReportTest {

    private val identity = CaptureReport.Identity("11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222",
        "enr-1", "emp-1", "acc-1", "Google Pixel 8", "Android 15")
    private val policy = CaptureReport.Policy("callvault-180d", 180, "tenant-policy", "TENANT_POLICY")
    private fun call(source: CaptureReport.Source, near: CaptureReport.NearChannel) = CaptureReport.Call(
        "abp-1", source, CaptureReport.Direction.INBOUND, 1_730_000_000_000L, 1_730_000_065_000L, "2.24", near, "NETWORK")
    private val media = CaptureReport.Media("audio/ogg", "opus", 48000, 2, 65_000L, 1234L, "a".repeat(64))

    @Suppress("UNCHECKED_CAST")
    private fun report(u: Map<String, Any?>) = u["report"] as Map<String, Any?>

    @Test
    fun `GSM stereo with known near side is DIGITAL_DUAL with manager mic and customer far side`() {
        val r = report(CaptureReport.build(identity, policy, call(CaptureReport.Source.GSM, CaptureReport.NearChannel.SECOND), media, 1_730_000_070_000L, "2.4.9"))
        assertEquals("DIGITAL_DUAL", r["captureMode"])
        assertEquals(
            listOf(mapOf("channel" to 0, "role" to "CUSTOMER", "origin" to "APP_AUDIO"), mapOf("channel" to 1, "role" to "MANAGER", "origin" to "MICROPHONE")),
            r["channelMap"],
        )
        assertEquals(mapOf("appId" to "android.telephony", "appVersion" to "2.24"), r["sourceApp"])
        assertEquals("COMPLETE", r["completeness"])
    }

    @Test
    fun `WhatsApp Business app id, never personal WhatsApp`() {
        val r = report(CaptureReport.build(identity, policy, call(CaptureReport.Source.WHATSAPP_BUSINESS, CaptureReport.NearChannel.FIRST), media, 0L, "x"))
        assertEquals("com.whatsapp.w4b", (r["sourceApp"] as Map<*, *>)["appId"])
        assertEquals("APP_FILTERED", r["isolation"])
    }

    @Test
    fun `mono or unknown near side is MIXED, still COMPLETE with consent`() {
        val r = report(CaptureReport.build(identity, policy, call(CaptureReport.Source.GSM, CaptureReport.NearChannel.UNKNOWN), media.copy(channels = 1), 0L, "x"))
        assertEquals("MIXED", r["captureMode"])
        assertEquals(listOf(mapOf("channel" to 0, "role" to "UNKNOWN", "origin" to "MIXED")), r["channelMap"])
        assertEquals(mapOf("consentRef" to "tenant-policy", "method" to "TENANT_POLICY", "noticeAt" to null), r["consent"])
        assertEquals(emptyList<Any>(), r["gaps"])
    }

    @Test
    fun `times are UTC with Z, end after start, no phone number anywhere`() {
        val u = CaptureReport.build(identity, policy, call(CaptureReport.Source.GSM, CaptureReport.NearChannel.FIRST), media, 1_730_000_070_000L, "x")
        val r = report(u)
        assertEquals("2024-10-27T03:33:20.000Z", r["startedAt"])
        assertEquals("2024-10-27T03:34:25.000Z", r["endedAt"])
        assertEquals("2024-10-27T03:34:30.000Z", u["signedAt"])
        assertNull(r["callRef"])
        val json = AbpCrypto.canonicalJson(u)
        assertTrue(!json.contains("number") && !json.contains("contact"))
        assertEquals(setOf("contractVersion", "report", "retention", "signedAt"), u.keys)
    }

    @Test
    fun `media kinds the server admits`() {
        assertEquals("audio/ogg" to "opus", CaptureReport.mediaKind("audio/opus", "a.ogg"))
        assertEquals("audio/mp4" to "aac", CaptureReport.mediaKind("audio/mp4a-latm", "a.m4a"))
        assertEquals("audio/mp4" to "aac", CaptureReport.mediaKind(null, "a.m4a"))
        assertNull(CaptureReport.mediaKind("audio/amr", "a.amr"))
    }

    @Test
    fun `report id is stable for the same file`() {
        assertEquals(CaptureReport.reportIdFor("e", "n.ogg", "a".repeat(64)), CaptureReport.reportIdFor("e", "n.ogg", "a".repeat(64)))
        assertTrue(CaptureReport.reportIdFor("e", "n.ogg", "a".repeat(64)).matches(Regex("^abp-[0-9a-f]{40}$")))
    }
}
