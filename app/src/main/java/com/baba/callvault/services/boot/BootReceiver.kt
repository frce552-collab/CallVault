/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.services.call.CallMonitorService
import com.baba.callvault.services.recording.DaemonKeepAliveService
import com.baba.callvault.utils.AppLogger

/**
 * On boot, if the user has set up ADB, start a foreground service that re-enables Wireless
 * debugging (if the OEM disabled it) and reconnects so recording works without opening the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return
        // Same reason as the app-start warmup: gating on pairing meant a Shizuku user got NOTHING
        // on boot — no call monitor, no recorder bind — on the one path where a reboot has just
        // stopped Shizuku too.
        if (!AppPreferences(context).isPrivilegedTransportSetUp()) return
        AppLogger.i(TAG, "Boot completed; starting ADB connection service + post-boot call monitor")
        // ABP Call: a "switched off by the user" mark from the previous boot must not hold recovery back after
        // this one (it made the keep-alive stand down for good). See PloshadkiFork.REBOOT_FORGETS_WD_OFF_BY_USER.
        runCatching {
            val prefs = AppPreferences(context)
            if (com.baba.callvault.ploshadki.AbpBootRecovery.shouldForgetWdOffByUser(prefs.wasWirelessDebuggingTurnedOffByUser())) {
                prefs.setWirelessDebuggingTurnedOffByUser(false)
                com.baba.callvault.integrations.adb.TransportTimeline.event(
                    context, "boot: forgot \"Wireless debugging off by user\" from the previous boot — recovery may switch it on",
                )
            } else {
                com.baba.callvault.integrations.adb.TransportTimeline.event(context, "boot: starting recorder recovery")
            }
        }.onFailure { AppLogger.w(TAG, "Could not reset the Wireless-debugging mark: ${it.message}") }
        AdbConnectionService.start(context)
        // Hold a live telephony listener for a bounded window so the first call(s) after a reboot are
        // detected in real time, instead of via the post-boot-delayed PHONE_STATE broadcast.
        CallMonitorService.start(context)
        // Persistent anchor: keep the recorder daemon warm from boot onward so calls record instantly
        // (and off-Wi-Fi over binder) without a per-call cold-start. Boot grants the FGS-start exemption.
        DaemonKeepAliveService.start(context)
    }

    companion object {
        private const val TAG = "CV:BootReceiver"
    }
}
