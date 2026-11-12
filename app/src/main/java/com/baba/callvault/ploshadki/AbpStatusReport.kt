/*
 * ABP Call — Ploshadki.kz fork of CallVault (https://github.com/madkongo/CallVault).
 * GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import com.baba.callvault.services.recording.ReadinessNotice
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The recorder status file for the CRM (owner decision 12.11.2026, variant "A": through Google Drive).
 *
 * Pure: builds `abp-call-status.json` and decides when to write it. No Android here, so the exact shape the CRM
 * reads is proved by unit tests. Writing it to Drive is [AbpStatusFile] / [AbpStatusWorker].
 *
 * Contract (UTF-8):
 * `{"app":"abp-call","version":"…","device":"<model>","recorder":"running"|"stopped","reason":"…"|null,
 *   "at":"ISO8601+tz","lastRecordingAt":"ISO"|null,
 *   "missedCalls":[{"at":"ISO","direction":"in|out","number":"+7…","durationSec":0,"type":"gsm|whatsapp"}]}`
 */
object AbpStatusReport {

    const val FILE_NAME = "abp-call-status.json"
    const val MIME = "application/json"
    const val MAX_MISSED_CALLS = 50
    const val MISSED_WINDOW_MS = 24L * 60 * 60 * 1000
    const val MATCH_TOLERANCE_MS = 2L * 60 * 1000

    /** One phone call from the system call log. */
    data class CallRow(val atMs: Long, val incoming: Boolean, val number: String?, val durationSec: Long)

    data class MissedCall(
        val atMs: Long,
        val incoming: Boolean,
        val number: String,
        val durationSec: Long,
        val whatsapp: Boolean = false,
    )

    data class Input(
        val version: String,
        val device: String,
        val running: Boolean,
        val reason: String?,
        val nowMs: Long,
        val zone: ZoneId,
        val lastRecordingAtMs: Long?,
        val missedCalls: List<MissedCall>,
    )

    /** Machine reason for the CRM. Null while the recorder runs with nothing to report. */
    fun reasonFor(notice: ReadinessNotice): String? = when (notice) {
        ReadinessNotice.READY -> null
        ReadinessNotice.READY_OFFLINE_PAUSED -> "usb_debugging_off"
        ReadinessNotice.STARTING -> "helper_dead"
        ReadinessNotice.STUCK -> "recovery_stuck"
        ReadinessNotice.NO_DEBUGGING -> "debugging_off"
        ReadinessNotice.NEEDS_WIFI -> "usb_debugging_off"
        ReadinessNotice.NEEDS_WIFI_TO_RESTART -> "no_wifi_after_reboot"
        ReadinessNotice.WD_OFF_BY_USER -> "wireless_debugging_off_by_user"
        ReadinessNotice.PAIRING_LOST -> "pairing_lost"
    }

    /** Recording switched off by the user: the keep-alive does not run at all. */
    const val REASON_RECORDING_OFF = "recording_off"

    /** "2026-11-12T10:15:30+05:00" — seconds, with the phone's offset. */
    fun iso(ms: Long, zone: ZoneId): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(ms).truncatedTo(ChronoUnit.SECONDS).atZone(zone))

    /** Kazakhstan / Russia: "87011234567" or "77011234567" → "+77011234567"; anything else is kept (spaces, dashes dropped). */
    fun normalizeNumber(raw: String?): String {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return ""
        val plus = s.startsWith("+")
        val digits = s.filter { it.isDigit() }
        if (digits.isEmpty()) return s
        return when {
            plus -> "+$digits"
            digits.length == 11 && (digits[0] == '8' || digits[0] == '7') -> "+7" + digits.substring(1)
            else -> digits
        }
    }

    /** For app logs: never the number, only "…1234". */
    fun mask(number: String?): String {
        val digits = number.orEmpty().filter { it.isDigit() }
        return if (digits.length < 4) "…" else "…" + digits.takeLast(4)
    }

    /**
     * Calls from the last 24 h with no recording started within ±2 min of them, newest first, at most 50.
     * [recordingStarts] are the start times of every recording the catalog knows (phone and app calls alike).
     * Call-log rows are carrier calls, so their type is always "gsm".
     */
    fun missedCalls(
        calls: List<CallRow>,
        recordingStarts: List<Long>,
        nowMs: Long,
        windowMs: Long = MISSED_WINDOW_MS,
        toleranceMs: Long = MATCH_TOLERANCE_MS,
        limit: Int = MAX_MISSED_CALLS,
    ): List<MissedCall> {
        val starts = recordingStarts.sorted()
        return calls.asSequence()
            .filter { it.atMs in (nowMs - windowMs)..nowMs }
            .filter { call -> !hasNear(starts, call.atMs, toleranceMs) }
            .sortedByDescending { it.atMs }
            .take(limit)
            .map { MissedCall(it.atMs, it.incoming, normalizeNumber(it.number), it.durationSec.coerceAtLeast(0)) }
            .toList()
    }

    private fun hasNear(sorted: List<Long>, at: Long, tol: Long): Boolean {
        var lo = 0
        var hi = sorted.size
        val from = at - tol
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < from) lo = mid + 1 else hi = mid
        }
        return lo < sorted.size && sorted[lo] <= at + tol
    }

    fun toJson(input: Input): String = buildString {
        append('{')
        field("app", "abp-call"); append(',')
        field("version", input.version); append(',')
        field("device", input.device); append(',')
        field("recorder", if (input.running) "running" else "stopped"); append(',')
        field("reason", input.reason); append(',')
        field("at", iso(input.nowMs, input.zone)); append(',')
        field("lastRecordingAt", input.lastRecordingAtMs?.let { iso(it, input.zone) }); append(',')
        str("missedCalls"); append(":[")
        input.missedCalls.take(MAX_MISSED_CALLS).forEachIndexed { i, c ->
            if (i > 0) append(',')
            append('{')
            field("at", iso(c.atMs, input.zone)); append(',')
            field("direction", if (c.incoming) "in" else "out"); append(',')
            field("number", c.number); append(',')
            str("durationSec"); append(':').append(c.durationSec); append(',')
            field("type", if (c.whatsapp) "whatsapp" else "gsm")
            append('}')
        }
        append("]}")
    }

    /** The overwritten file is due: first time, after [intervalMs], or when "running/reason" changed. */
    fun isDue(lastKey: String?, lastAtMs: Long, key: String, nowMs: Long, intervalMs: Long): Boolean =
        lastKey != key || lastAtMs <= 0L || nowMs - lastAtMs >= intervalMs || nowMs < lastAtMs

    fun stateKey(running: Boolean, reason: String?): String = (if (running) "running" else "stopped") + "|" + reason.orEmpty()

    /** Fallback when the Drive provider cannot overwrite: one new file per hour, "abp-call-status-20261112-14.json". */
    fun hourlyName(ms: Long, zone: ZoneId): String =
        "abp-call-status-" + DateTimeFormatter.ofPattern("yyyyMMdd-HH").format(Instant.ofEpochMilli(ms).atZone(zone)) + ".json"

    private fun StringBuilder.field(name: String, value: String?) {
        str(name); append(':')
        if (value == null) append("null") else str(value)
    }

    private fun StringBuilder.str(s: String) {
        append('"')
        for (ch in s) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append(String.format("\\u%04x", ch.code)) else append(ch)
            }
        }
        append('"')
    }
}
