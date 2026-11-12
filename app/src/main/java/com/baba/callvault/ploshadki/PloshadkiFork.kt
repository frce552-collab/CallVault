/*
 * ABP Call — Ploshadki.kz fork of CallVault (https://github.com/madkongo/CallVault),
 * itself a fork of ShizuCallRecorder. GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

/**
 * Fork-wide switches for ABP Call. One place, so every behaviour that differs from upstream
 * CallVault is visible here and easy to diff against upstream when syncing.
 */
object PloshadkiFork {
    /**
     * Upstream self-update from GitHub (madkongo/CallVault releases) is off: it would offer a
     * different app (other package id, brand and signing key) to our managers.
     */
    const val UPSTREAM_UPDATES_ENABLED = false

    /**
     * ABP Call updates itself from OUR GitHub Releases (owner decisions 2026-11-09/10):
     * upstream release → fork sync → owner merge → ploshadki-release.yml publishes a signed
     * [UPDATE_APK_ASSET] → phones pick it up (daily check, silent install over ADB).
     * The update must be signed with the same key as the installed app (see ApkVerifier).
     */
    const val UPDATES_ENABLED = true

    /**
     * GitHub "owner/repo" whose latest (non-prerelease) release is the update source. PRIVATE repo
     * (owner decision 2026-11-10): the APK is not public; phones read it with the read-only token
     * BuildConfig.UPDATE_READ_TOKEN that only ploshadki-release.yml bakes in.
     */
    const val UPDATE_REPO = "frce552-collab/abp-call-releases"

    /** Release asset name the updater downloads. ploshadki-release.yml uploads exactly this. */
    const val UPDATE_APK_ASSET = "ABP-Call.apk"

    /** Upstream donation (Ko-fi/PayPal) and community (Telegram) links are not shown. */
    const val UPSTREAM_SUPPORT_LINKS_ENABLED = false

    /**
     * App calls are recorded ONLY for these packages (owner decision 2026-10-21, #161):
     * WhatsApp Business. Personal WhatsApp (com.whatsapp) and any other or unidentified app
     * are never recorded (fail-closed).
     */
    val RECORDABLE_VOIP_PACKAGES: Set<String> = setOf("com.whatsapp.w4b")

    /**
     * Automatic short summary of every ABP call on the phone (owner decision 11.11.2026: «включить»).
     * After the on-device transcript is DONE, the on-device summariser runs once and its text travels
     * with the transcript to the server (air.wa-capture.transcript.v1 `summary`). The summary model
     * (about 2.6 GB) is downloaded on Wi-Fi only, once. Phones with too little memory send the
     * transcript without a summary. See [AutoSummaryPlan].
     */
    const val AUTO_SUMMARY_ENABLED = true

    /** Phones below this total RAM never summarise in the background (the model needs about 3.5 GB). */
    const val AUTO_SUMMARY_MIN_TOTAL_RAM_BYTES = 5_000_000_000L

    /** How long the transcript waits for its summary before it is sent without one. */
    const val AUTO_SUMMARY_WAIT_MINUTES = 40L

    /**
     * Self-healing recorder (owner request 12.11.2026, field report CallVault 2.4.9 on Xiaomi HyperOS: the helper
     * died after a day idle and the app waited for a tap and Wi-Fi).
     *
     * Off-Wi-Fi recording (the loopback listener inside adbd) is ON by default: once armed — needs Wi-Fi and
     * Wireless debugging once per boot — the helper can be relaunched over 127.0.0.1 with no Wi-Fi at all, as
     * long as USB debugging stays on. Upstream keeps it opt-in; for company phones it is the only way to
     * recover without a person. The listener is RSA-gated (only this app's paired key is accepted).
     */
    const val OFFLINE_RECORDING_BY_DEFAULT = true

    /** Liveness check from an alarm that fires in Doze (the 60 s watchdog stops in deep sleep). */
    const val LIVENESS_ALARM_MINUTES = 5L

    /** After this long down with recovery failing, a loud ongoing notification asks the manager to tap. */
    const val RECORDER_DOWN_ALERT_MINUTES = 10L
}
