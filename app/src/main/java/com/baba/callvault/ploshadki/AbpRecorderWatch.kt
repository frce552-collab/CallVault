/*
 * ABP Call — Ploshadki.kz fork of CallVault (https://github.com/madkongo/CallVault).
 * GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

/**
 * When the recorder has been down long enough to tell the manager. Pure, unit-tested.
 * [downSinceMs] is elapsedRealtime of the first "down" reading (null while up).
 */
object AbpRecorderWatch {
    fun shouldAlert(downSinceMs: Long?, nowMs: Long, alertAfterMs: Long = PloshadkiFork.RECORDER_DOWN_ALERT_MINUTES * 60_000L): Boolean =
        downSinceMs != null && nowMs - downSinceMs >= alertAfterMs

    /** Next alarm delay: the regular interval, shorter (1 min) while the recorder is down so retries come sooner. */
    fun nextCheckDelayMs(recorderUp: Boolean): Long =
        if (recorderUp) PloshadkiFork.LIVENESS_ALARM_MINUTES * 60_000L else 60_000L
}
