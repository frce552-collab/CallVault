/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.updates

import android.content.Context
import android.content.pm.PackageManager
import com.baba.callvault.utils.AppLogger
import java.io.File
import java.security.MessageDigest

/**
 * Verifies a downloaded update APK BEFORE it is handed to any installer:
 *  1. it is a readable APK for OUR package name,
 *  2. its versionCode is strictly higher than the running app's,
 *  3. its signing certificate matches the installed app's certificate.
 *
 * Android itself refuses mismatched-cert updates, but verifying first fails cleanly (with a clear
 * log + user-facing error) instead of a confusing system installer failure — and protects the
 * silent shell-install path, which would otherwise happily run `pm install` on anything.
 */
object ApkVerifier {

    private const val TAG = "CV:ApkVerifier"

    /**
     * ABP Call: the pin is the certificate the INSTALLED app is signed with (our release key from
     * the fork's secrets), read at run time instead of upstream's hard-coded CallVault cert.
     * An update signed with anything else is rejected (the same rule Android applies), checked
     * before the silent `pm install`. Null (unreadable / multi-signed) means no update is accepted.
     */
    private fun installedCertSha256(context: Context): String? = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signing = info.signingInfo ?: return null
        if (signing.hasMultipleSigners()) return null
        // signingCertificateHistory[0] is the original cert; the current one is last (key rotation).
        signing.signingCertificateHistory?.lastOrNull()?.let { sha256Hex(it.toByteArray()) }
    }.getOrNull()

    /** True when [apkFile] is a genuine CallVault update: right package, newer, right signature. */
    fun isValidUpdate(context: Context, apkFile: File): Boolean {
        if (!apkFile.isFile || apkFile.length() == 0L) {
            AppLogger.w(TAG, "Update APK missing or empty: ${apkFile.path}")
            return false
        }
        val packageManager = context.packageManager
        val info = runCatching {
            @Suppress("DEPRECATION") // (Path-based overload; the flags variant needs API 33+)
            packageManager.getPackageArchiveInfo(apkFile.path, PackageManager.GET_SIGNING_CERTIFICATES)
        }.getOrNull()
        if (info == null) {
            AppLogger.w(TAG, "Update APK could not be parsed")
            return false
        }

        if (info.packageName != context.packageName) {
            AppLogger.w(TAG, "Update APK package mismatch: ${info.packageName}")
            return false
        }

        val installedVersionCode = runCatching {
            packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }.getOrDefault(Long.MAX_VALUE)
        if (info.longVersionCode <= installedVersionCode) {
            AppLogger.w(TAG, "Update APK is not newer: ${info.longVersionCode} <= $installedVersionCode")
            return false
        }

        val signingInfo = info.signingInfo
        val signers = signingInfo?.apkContentsSigners
        if (signingInfo == null || signers.isNullOrEmpty()) {
            AppLogger.w(TAG, "Update APK has no readable signature")
            return false
        }
        // Require EXACTLY the pinned cert as the sole signer. A genuine CallVault release is
        // single-signed with the pinned key; anything multiply-signed is not ours.
        if (signingInfo.hasMultipleSigners() || signers.size != 1) {
            AppLogger.e(TAG, "Update APK has an unexpected signer set — refusing to install")
            return false
        }
        val pinned = installedCertSha256(context)
        if (pinned == null) {
            AppLogger.e(TAG, "Installed app signing certificate unreadable, refusing to install")
            return false
        }
        val matches = sha256Hex(signers[0].toByteArray()) == pinned
        if (!matches) {
            AppLogger.e(TAG, "Update APK signing certificate does NOT match the installed app's cert — refusing to install")
        }
        return matches
    }

    /** Lowercase hex SHA-256 of [bytes]. */
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
