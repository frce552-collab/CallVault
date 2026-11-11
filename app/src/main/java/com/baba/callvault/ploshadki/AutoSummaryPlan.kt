/*
 * ABP Call — Ploshadki.kz fork of CallVault (https://github.com/madkongo/CallVault).
 * GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

/**
 * What happens to a finished ABP transcript with respect to the automatic summary.
 * Pure, so the decision is unit-tested without WorkManager, a model or a phone.
 */
object AutoSummaryPlan {

    enum class Step {
        /** Send the transcript now (no automatic summary for this call). */
        SEND_NOW,
        /** Start the on-device summary; the transcript is sent when it ends, or after the wait at the latest. */
        SUMMARISE_THEN_SEND,
        /** The model is missing: queue its Wi-Fi download for later calls and send this transcript now. */
        DOWNLOAD_MODEL_AND_SEND_NOW,
    }

    fun decide(
        enabled: Boolean,
        summaryExists: Boolean,
        modelInstalled: Boolean,
        totalRamBytes: Long,
        minRamBytes: Long = PloshadkiFork.AUTO_SUMMARY_MIN_TOTAL_RAM_BYTES,
    ): Step = when {
        !enabled -> Step.SEND_NOW
        summaryExists -> Step.SEND_NOW
        totalRamBytes in 1 until minRamBytes -> Step.SEND_NOW
        !modelInstalled -> Step.DOWNLOAD_MODEL_AND_SEND_NOW
        else -> Step.SUMMARISE_THEN_SEND
    }
}
