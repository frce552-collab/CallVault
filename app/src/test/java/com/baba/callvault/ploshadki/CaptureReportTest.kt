/*
 * ABP Call — Ploshadki.kz fork of CallVault. GPL-3.0-or-later with the Section 7 terms in LICENSE.
 * Checks the envelope against the server's rules (#139 schema + #140 admission) that a phone can break.
 */
package com.baba.callvault.ploshadki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureReportTest {

    private val identity = CaptureReport.Identity("11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222",
        "enr-1", "emp-1", "acc-1", "Google Pixel 8", "Android 15")
    private val policy = CaptureReport.Policy("callvault-180d", 180, "tenant-policy", "TENANT_POLICY")
    private fun call(source: CaptureReport.Source, near: CaptureReport.NearChannel, phone: String? = null) = CaptureReport.Call(
        "abp-1", source, CaptureReport.Direction.INBOUND, 1_730_000_000_000L, 1_730_000_065_000L, "2.24", near, "NETWORK", phone)
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
    fun `WhatsApp Business app id`() {
        val r = report(CaptureReport.build(identity, policy, call(CaptureReport.Source.WHATSAPP_BUSINESS, CaptureReport.NearChannel.FIRST), media, 0L, "x"))
        assertEquals("com.whatsapp.w4b", (r["sourceApp"] as Map<*, *>)["appId"])
        assertEquals("APP_FILTERED", r["isolation"])
    }

    @Test
    fun `ordinary WhatsApp gets its own app id, not WhatsApp Business`() {
        val r = report(CaptureReport.build(identity, policy, call(CaptureReport.Source.WHATSAPP, CaptureReport.NearChannel.FIRST), media, 0L, "x"))
        assertEquals("com.whatsapp", (r["sourceApp"] as Map<*, *>)["appId"])
        assertEquals("APP_FILTERED", r["isolation"])
    }

    @Test
    fun `which WhatsApp, by the app name on the file`() {
        assertEquals(CaptureReport.Source.WHATSAPP, CaptureReport.voipSource("WhatsApp"))
        assertEquals(CaptureReport.Source.WHATSAPP, CaptureReport.voipSource(" whatsapp "))
        assertEquals(CaptureReport.Source.WHATSAPP_BUSINESS, CaptureReport.voipSource("WhatsApp Business"))
        assertEquals(CaptureReport.Source.WHATSAPP_BUSINESS, CaptureReport.voipSource("WA Business"))
        // No app on the name (old file): as before 13.11, WhatsApp Business.
        assertEquals(CaptureReport.Source.WHATSAPP_BUSINESS, CaptureReport.voipSource(null))
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
    fun `times are UTC with Z, end after start, no phone number when none is known`() {
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

    @Test
    fun `counterparty phone is a top-level sibling inside the signed envelope`() {
        val u = CaptureReport.build(identity, policy, call(CaptureReport.Source.GSM, CaptureReport.NearChannel.FIRST, "87011234567"), media, 1_730_000_070_000L, "x")
        assertEquals(setOf("contractVersion", "report", "retention", "signedAt", "counterparty"), u.keys)
        assertEquals(mapOf("phoneE164" to "+77011234567"), u["counterparty"])
        assertNull(report(u)["counterparty"])
        val json = AbpCrypto.canonicalJson(u)
        assertTrue(json.contains("\"counterparty\":{\"phoneE164\":\"+77011234567\"}"))
        // Signed: the same envelope without the field gives a different signature.
        val secret = ByteArray(32) { it.toByte() }
        val without = CaptureReport.build(identity, policy, call(CaptureReport.Source.GSM, CaptureReport.NearChannel.FIRST), media, 1_730_000_070_000L, "x")
        assertNotEquals(AbpCrypto.signCapture(secret, without, "a".repeat(64)), AbpCrypto.signCapture(secret, u, "a".repeat(64)))
    }

    @Test
    fun `WhatsApp Business sends the caller only when it is a phone number`() {
        val wa = CaptureReport.Source.WHATSAPP_BUSINESS
        val n = CaptureReport.NearChannel.FIRST
        assertEquals(mapOf("phoneE164" to "+77011234567"), CaptureReport.build(identity, policy, call(wa, n, "+7 701 123 45 67"), media, 0L, "x")["counterparty"])
        assertEquals(mapOf("phoneE164" to "+77011234567"), CaptureReport.build(identity, policy, call(wa, n, "77011234567@s.whatsapp.net"), media, 0L, "x")["counterparty"])
        for (caller in listOf("Иван Петров", "123456789012345678@g.us", "1234567@lid", "Office 87011234567")) {
            val u = CaptureReport.build(identity, policy, call(wa, n, caller), media, 0L, "x")
            assertFalse(caller, u.containsKey("counterparty"))
        }
    }

    @Test
    fun `hidden, unknown or invalid numbers omit the whole field`() {
        for (raw in listOf(null, "", "   ", "-1", "-2", "private", "Unknown", "anonymous", "123", "0123456789", "+0 701 123", "1234567890123456")) {
            val u = CaptureReport.build(identity, policy, call(CaptureReport.Source.GSM, CaptureReport.NearChannel.FIRST, raw), media, 0L, "x")
            assertFalse("$raw", u.containsKey("counterparty"))
            assertEquals(setOf("contractVersion", "report", "retention", "signedAt"), u.keys)
        }
    }

    @Test
    fun `phone normalisation to E164`() {
        assertEquals("+77011234567", CaptureReport.counterpartyPhoneE164("87011234567"))
        assertEquals("+77011234567", CaptureReport.counterpartyPhoneE164("8 (701) 123-45-67"))
        assertEquals("+77011234567", CaptureReport.counterpartyPhoneE164("+77011234567"))
        assertEquals("+77011234567", CaptureReport.counterpartyPhoneE164("77011234567"))
        assertEquals("+77011234567", CaptureReport.counterpartyPhoneE164("7011234567"))
        assertEquals("+79161234567", CaptureReport.counterpartyPhoneE164("89161234567"))
        assertEquals("+442079460958", CaptureReport.counterpartyPhoneE164("+44 20 7946 0958"))
        // An international number that happens to have 11 digits starting with 8 is not a Kazakh one.
        assertEquals("+84912345678", CaptureReport.counterpartyPhoneE164("+84 912 345 678"))
        assertNull(CaptureReport.counterpartyPhoneE164("701123"))
        assertNull(CaptureReport.counterpartyPhoneE164("Аноним"))
        for (ok in listOf("87011234567", "7011234567", "+77011234567")) {
            assertTrue(CaptureReport.counterpartyPhoneE164(ok)!!.matches(Regex("^\\+[1-9][0-9]{6,14}$")))
        }
    }
}
