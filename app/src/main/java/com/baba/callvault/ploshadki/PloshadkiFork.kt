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
}
