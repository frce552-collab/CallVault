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
     * different app (other package id, brand and signing key) to our managers. ABP Call is
     * distributed by the company (MDM / direct APK).
     */
    const val UPSTREAM_UPDATES_ENABLED = false

    /** Upstream donation (Ko-fi/PayPal) and community (Telegram) links are not shown. */
    const val UPSTREAM_SUPPORT_LINKS_ENABLED = false

    /**
     * App calls are recorded ONLY for these packages (owner decision 2026-10-21, #161):
     * WhatsApp Business. Personal WhatsApp (com.whatsapp) and any other or unidentified app
     * are never recorded (fail-closed).
     */
    val RECORDABLE_VOIP_PACKAGES: Set<String> = setOf("com.whatsapp.w4b")
}
