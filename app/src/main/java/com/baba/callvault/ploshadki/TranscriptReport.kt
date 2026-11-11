/*
 * ABP Call — Ploshadki.kz fork of CallVault. GPL-3.0-or-later with the Section 7 terms in LICENSE.
 *
 * Builds the `air.wa-capture.transcript.v1` envelope: the on-device whisper transcript (and the on-device
 * summary, when one exists) of a recording ABP Call has uploaded, for POST /air/capture/v1/transcripts.
 * Pure Kotlin: no Android, no server code, so the whole contract is checked by JVM unit tests.
 *
 * Privacy: the envelope carries no phone number, contact name or file name — only the upload's reportId,
 * the enrollment, the audio hash, the words and the summary. The words and the summary are never logged.
 */
package com.baba.callvault.ploshadki

import java.util.Locale

object TranscriptReport {

    const val CONTRACT_VERSION = "air.wa-capture.transcript.v1"
    const val ENGINE_NAME = "callvault-whisper"

    const val MAX_SEGMENTS = 5000
    const val MAX_SEGMENT_TEXT = 2000
    const val MAX_SUMMARY_TEXT = 4000
    const val MAX_MODEL_ID = 120

    /** SHA-256 of the empty byte array: the transcript has no body, so it is signed like an upload with an empty one. */
    val EMPTY_BODY_SHA256: String = AbpCrypto.hex(AbpCrypto.sha256(ByteArray(0)))

    enum class Speaker { MANAGER, CUSTOMER, UNKNOWN }

    /**
     * One stored transcript row as CallVault keeps it: [label] is the neutral channel letter the runner wrote
     * ("A" = channel 0 / left, "B" = channel 1 / right) or null when nobody was attributed.
     */
    data class Segment(val startMs: Long, val endMs: Long, val text: String, val label: String?)

    data class Summary(val modelId: String, val text: String)

    /**
     * Who spoke a row, from the channel letter and the near (manager) channel — the same near channel the
     * upload declared (see AbpUploadWorker): app calls have the near party on channel 0 by construction,
     * carrier calls only once CallVault has learned (and trusts) the OEM's channel order. Anything not known
     * is UNKNOWN: a wrong side on a record of a real conversation is worse than no side.
     */
    fun speakerOf(label: String?, near: CaptureReport.NearChannel): Speaker {
        val channel = when (label) {
            "A" -> 0
            "B" -> 1
            else -> return Speaker.UNKNOWN
        }
        val nearChannel = when (near) {
            CaptureReport.NearChannel.FIRST -> 0
            CaptureReport.NearChannel.SECOND -> 1
            CaptureReport.NearChannel.UNKNOWN -> return Speaker.UNKNOWN
        }
        return if (channel == nearChannel) Speaker.MANAGER else Speaker.CUSTOMER
    }

    /**
     * [text] trimmed and, when longer than [max] characters, cut at the last word boundary that fits (never
     * mid-word unless one "word" is itself longer than [max]). Cut rather than split: a split would create
     * rows with invented timestamps, and whisper rows over 2000 characters are not expected in practice.
     */
    fun cutAtWord(text: String, max: Int): String {
        val t = text.trim()
        if (t.length <= max) return t
        var end = max
        // Never leave half of a UTF-16 surrogate pair.
        if (Character.isHighSurrogate(t[end - 1])) end--
        val head = t.substring(0, end)
        // The character right after the cut being a space means the cut already falls between words.
        val boundary = if (t[end].isWhitespace()) end else head.indexOfLast { it.isWhitespace() }
        return (if (boundary > 0) t.substring(0, boundary) else head).trimEnd()
    }

    /**
     * The rows the server admits: text trimmed and cut to [MAX_SEGMENT_TEXT]; rows with no text or with
     * `endMs <= startMs` (or a negative start) dropped; ordered by `startMs` (stable, so equal starts keep
     * CallVault's order); the first [MAX_SEGMENTS] kept.
     */
    fun segments(rows: List<Segment>, near: CaptureReport.NearChannel): List<Map<String, Any?>> =
        rows.asSequence()
            .filter { it.startMs >= 0L && it.endMs > it.startMs }
            .map { it to cutAtWord(it.text, MAX_SEGMENT_TEXT) }
            .filter { (_, text) -> text.isNotEmpty() }
            .sortedBy { (row, _) -> row.startMs }
            .take(MAX_SEGMENTS)
            .map { (row, text) ->
                linkedMapOf<String, Any?>(
                    "startMs" to row.startMs,
                    "endMs" to row.endMs,
                    "text" to text,
                    "speaker" to speakerOf(row.label, near).name,
                )
            }
            .toList()

    private val LANGUAGE = Regex("^[a-z]{2,3}(-[a-z0-9]{2,8})*$")

    /** The language whisper was pinned to, as a lower-case BCP-47-ish code; null for auto-detect or anything odd. */
    fun language(code: String?): String? {
        val c = code?.trim()?.lowercase(Locale.ROOT)?.replace('_', '-').orEmpty()
        if (c.isEmpty() || c == "auto") return null
        return c.takeIf { LANGUAGE.matches(it) }
    }

    /** The summary as the contract wants it, or null when there is none worth sending. */
    fun summary(s: Summary?): Map<String, Any?>? {
        if (s == null) return null
        val model = s.modelId.trim().take(MAX_MODEL_ID)
        val text = cutAtWord(s.text, MAX_SUMMARY_TEXT)
        if (model.isEmpty() || text.isEmpty()) return null
        return linkedMapOf("modelId" to model, "text" to text)
    }

    fun build(
        reportId: String,
        enrollmentRef: String,
        mediaSha256: String,
        modelId: String,
        language: String?,
        rows: List<Segment>,
        near: CaptureReport.NearChannel,
        summary: Summary?,
        signedAtMs: Long,
    ): Map<String, Any?> {
        val model = modelId.trim().take(MAX_MODEL_ID)
        require(model.isNotEmpty()) { "no transcription model id" }
        require(SHA256_HEX.matches(mediaSha256)) { "mediaSha256 must be 64 lowercase hex" }
        require(reportId.isNotBlank() && enrollmentRef.isNotBlank()) { "reportId and enrollmentRef are required" }
        return linkedMapOf(
            "contractVersion" to CONTRACT_VERSION,
            "reportId" to reportId,
            "enrollmentRef" to enrollmentRef,
            "mediaSha256" to mediaSha256,
            "engine" to linkedMapOf("name" to ENGINE_NAME, "modelId" to model),
            "language" to language(language),
            "segments" to segments(rows, near),
            "summary" to summary(summary),
            "signedAt" to CaptureReport.isoUtc(signedAtMs),
        )
    }

    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

    /** HMAC-SHA256(secret, canonical(envelope) + "\n" + sha256hex(empty)) — the upload scheme with an empty body. */
    fun sign(secret: ByteArray, envelope: Map<String, Any?>): String =
        AbpCrypto.signCapture(secret, envelope, EMPTY_BODY_SHA256)

    /** The request body: `{"transcript": <envelope>, "signature": "<hex>"}`, canonical JSON. */
    fun body(envelope: Map<String, Any?>, signature: String): String =
        AbpCrypto.canonicalJson(mapOf("transcript" to envelope, "signature" to signature))

    /** What the phone does with the server's answer. */
    enum class Outcome {
        /** 2xx (STORED / DUPLICATE): finished. */
        DONE,
        /** 404 RECORDING_NOT_FOUND (audio still uploading), 5xx, 408/429, stale signature: try again with backoff. */
        RETRY,
        /** 409 MEDIA_MISMATCH, 422 and any other refusal: never resent. */
        GIVE_UP,
    }

    /**
     * Same rules as the upload worker for 401: the client has already refreshed the token once; a stale
     * signature (slow phone clock or a long queue) is retried with a fresh one, any other 401 is a refusal.
     */
    fun outcomeOf(code: Int, reason: String?): Outcome = when {
        code in 200..299 -> Outcome.DONE
        code == 404 -> Outcome.RETRY
        code == 401 && reason == "SIGNATURE_STALE" -> Outcome.RETRY
        code == 408 || code == 429 -> Outcome.RETRY
        code >= 500 -> Outcome.RETRY
        else -> Outcome.GIVE_UP
    }
}
