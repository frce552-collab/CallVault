/*
 * ABP Call — Ploshadki.kz fork of CallVault. GPL-3.0-or-later with the Section 7 terms in LICENSE.
 *
 * Builds the upload envelope of the OPEN data contract (#139 `air.wa-capture.source.v1` report inside the
 * #140 `air.wa-capture.upload.v1` envelope). Pure Kotlin: no Android, no server code.
 *
 * Phone numbers: the ONLY number that leaves the phone is the other party's, in E.164, in the optional top-level
 * `counterparty.phoneE164` of the signed envelope (sent over TLS only), so the server can link the recording to the
 * CRM client and deal. It is omitted when the number is hidden, unknown or not a valid E.164. Contact names are
 * never sent. The number never goes into RecorderEvents, logs or status screens.
 */
package com.baba.callvault.ploshadki

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object CaptureReport {

    const val UPLOAD_VERSION = "air.wa-capture.upload.v1"
    const val SOURCE_VERSION = "air.wa-capture.source.v1"
    /** Pseudo app id of a carrier (GSM/VoLTE) call in the contract. */
    const val GSM_APP_ID = "android.telephony"
    const val WA_BUSINESS_APP_ID = "com.whatsapp.w4b"

    enum class Source { GSM, WHATSAPP_BUSINESS }
    enum class Direction { INBOUND, OUTBOUND, UNKNOWN }

    /** Which captured channel carries the manager's own voice (the near side), when known. */
    enum class NearChannel { FIRST, SECOND, UNKNOWN }

    data class Media(
        val mimeType: String,
        val codec: String,
        val sampleRateHz: Int,
        val channels: Int,
        val durationMs: Long,
        val sizeBytes: Long,
        val sha256Hex: String,
    )

    data class Identity(
        val tenantId: String,
        val deviceRef: String,
        val enrollmentRef: String,
        val employeeRef: String,
        val sourceAccountId: String,
        val model: String,
        val osBuild: String,
    )

    data class Policy(
        val retentionPolicyId: String,
        val retentionDays: Int,
        val consentRef: String,
        val consentMethod: String,
    )

    data class Call(
        val reportId: String,
        val source: Source,
        val direction: Direction,
        val startedAtMs: Long,
        val endedAtMs: Long,
        val sourceAppVersion: String,
        val near: NearChannel,
        val clockSource: String,
        /** The other party's number as the recording knows it (raw); see [counterpartyPhoneE164]. Never logged. */
        val counterpartyPhone: String? = null,
    )

    private val E164 = Regex("^\\+[1-9][0-9]{6,14}$")
    /** Characters a written phone number may hold besides digits; anything else (a name, "private", a JID) is refused. */
    private const val PHONE_PUNCTUATION = "+-() ."
    private val WA_USER_JID = Regex("^([0-9]{7,15})@s\\.whatsapp\\.net$")

    /**
     * The other party's number in E.164, or null when it is hidden, unknown or not a phone number.
     * Digits only; Kazakhstan/Russia: 11 digits starting with 8 → leading 7; 10 digits starting with 7 → prefixed 7.
     * A WhatsApp user JID ("77011234567@s.whatsapp.net") is read as its number. Any other value with letters or
     * symbols (a contact name, "private", a group or lid JID) is refused, never mined for digits.
     */
    fun counterpartyPhoneE164(raw: String?): String? {
        val s = raw?.trim().orEmpty().let { WA_USER_JID.matchEntire(it)?.groupValues?.get(1) ?: it }
        if (s.isEmpty() || s.any { !it.isDigit() && it !in PHONE_PUNCTUATION }) return null
        var digits = s.filter { it in '0'..'9' }
        if (digits.length == 11 && digits.startsWith("8")) digits = "7" + digits.substring(1)
        else if (digits.length == 10 && digits.startsWith("7")) digits = "7$digits"
        val e164 = "+$digits"
        return if (E164.matches(e164)) e164 else null
    }

    /** Container/codec pair the server admits, from a MIME type or file name. Null = not uploadable. */
    fun mediaKind(mime: String?, fileName: String): Pair<String, String>? {
        val m = mime?.lowercase(Locale.ROOT).orEmpty()
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            m.contains("opus") || m == "audio/ogg" || ext == "ogg" || ext == "opus" || ext == "oga" -> "audio/ogg" to "opus"
            m.contains("mp4a") || m == "audio/mp4" || m == "audio/aac" || ext == "m4a" || ext == "mp4" || ext == "aac" -> "audio/mp4" to "aac"
            m == "audio/wav" || m == "audio/x-wav" || ext == "wav" -> "audio/wav" to "pcm_s16le"
            else -> null
        }
    }

    /** Stable report id for one recording: the same file always gets the same id (server dedup). */
    fun reportIdFor(enrollmentRef: String, fileName: String, sha256Hex: String): String =
        "abp-" + AbpCrypto.hex(AbpCrypto.sha256("$enrollmentRef\n$fileName\n$sha256Hex".toByteArray(Charsets.UTF_8))).substring(0, 40)

    fun isoUtc(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(ms))

    /**
     * The channel map. Stereo with a known near side → DIGITAL_DUAL (manager = microphone, customer = far side).
     * Anything else (mono, or the near side not known) → MIXED: honest, still admitted to STT by the server.
     */
    fun channelPlan(channels: Int, near: NearChannel): Pair<String, List<Map<String, Any?>>> {
        if (channels == 2 && near != NearChannel.UNKNOWN) {
            val managerCh = if (near == NearChannel.FIRST) 0 else 1
            val customerCh = 1 - managerCh
            return "DIGITAL_DUAL" to listOf(
                mapOf("channel" to managerCh, "role" to "MANAGER", "origin" to "MICROPHONE"),
                mapOf("channel" to customerCh, "role" to "CUSTOMER", "origin" to "APP_AUDIO"),
            ).sortedBy { it["channel"] as Int }
        }
        val n = channels.coerceIn(1, 8)
        return "MIXED" to (0 until n).map { mapOf("channel" to it, "role" to "UNKNOWN", "origin" to "MIXED") }
    }

    fun build(identity: Identity, policy: Policy, call: Call, media: Media, signedAtMs: Long, captureToolVersion: String): Map<String, Any?> {
        val (mode, channelMap) = channelPlan(media.channels, call.near)
        val ended = if (call.endedAtMs > call.startedAtMs) call.endedAtMs else call.startedAtMs + maxOf(1L, media.durationMs)
        val report = linkedMapOf<String, Any?>(
            "contractVersion" to SOURCE_VERSION,
            "kind" to "CaptureSourceReport",
            "reportId" to call.reportId,
            "tenantId" to identity.tenantId,
            "device" to mapOf(
                "deviceRef" to identity.deviceRef,
                "enrollmentRef" to identity.enrollmentRef,
                "platform" to "ANDROID",
                "model" to identity.model.take(120).ifBlank { "android" },
                "osBuild" to identity.osBuild.take(120).ifBlank { "android" },
            ),
            "employeeRef" to identity.employeeRef,
            "sourceAccountId" to identity.sourceAccountId,
            "sourceApp" to mapOf(
                "appId" to if (call.source == Source.GSM) GSM_APP_ID else WA_BUSINESS_APP_ID,
                "appVersion" to call.sourceAppVersion.take(60).ifBlank { "unknown" },
            ),
            "captureTool" to mapOf("name" to "ABP Call", "version" to captureToolVersion.take(60).ifBlank { "unknown" }),
            "captureMode" to mode,
            // Carrier: the telephony voice streams; WhatsApp Business: the app's own call stream (fail-closed allow-list).
            "isolation" to if (call.source == Source.GSM) "PROCESS_ISOLATED" else "APP_FILTERED",
            "boundarySource" to "AUTO",
            "direction" to call.direction.name,
            "callRef" to null,
            "startedAt" to isoUtc(call.startedAtMs),
            "endedAt" to isoUtc(ended),
            "clock" to mapOf("source" to call.clockSource, "measuredSkewMs" to null),
            "media" to mapOf(
                "mimeType" to media.mimeType,
                "codec" to media.codec,
                "sampleRateHz" to media.sampleRateHz.coerceIn(8000, 192000),
                "channels" to media.channels.coerceIn(1, 8),
                "durationMs" to maxOf(1L, media.durationMs),
                "sizeBytes" to media.sizeBytes,
                "sha256" to media.sha256Hex,
            ),
            "channelMap" to channelMap,
            "completeness" to "COMPLETE",
            "gaps" to emptyList<Any>(),
            "captureConfidence" to null,
            "consent" to mapOf("consentRef" to policy.consentRef, "method" to policy.consentMethod, "noticeAt" to null),
        )
        val upload = linkedMapOf<String, Any?>(
            "contractVersion" to UPLOAD_VERSION,
            "report" to report,
            "retention" to mapOf(
                "policyId" to policy.retentionPolicyId,
                "deleteAfter" to isoUtc(ended + policy.retentionDays.toLong() * 24L * 3600L * 1000L),
            ),
            "signedAt" to isoUtc(signedAtMs),
        )
        // Optional, for CRM linking; the whole field is omitted (never empty or partial) when there is no valid number.
        counterpartyPhoneE164(call.counterpartyPhone)?.let { upload["counterparty"] = mapOf("phoneE164" to it) }
        return upload
    }
}
