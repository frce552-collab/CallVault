/*
 * ABP Call — Ploshadki.kz fork of CallVault (https://github.com/madkongo/CallVault).
 * GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.integrations.adb.TransportTimeline
import com.baba.callvault.system.updates.UpdateNotifications
import com.baba.callvault.system.updates.UpdateScheduler

/**
 * Owner request 13.11.2026: "ABP Call update available — Update", like CallVault. The "update available"
 * notification carries an **Update** button that installs at once (the same path as the Home banner's button:
 * [UpdateScheduler.enqueueInstallNow] → `UpdateInstallWorker`, never during a call). Not tapped → the night
 * auto-install ([AbpAutoInstallWorker]) still installs it. Only main-channel (Latest) releases ever reach this:
 * the check reads `/releases/latest` of our private repo, pilots (Pre-release) are invisible.
 */
object AbpUpdatePrompt {
    /** Explicit-component broadcast; the receiver is not exported, so no other app can trigger an install. */
    const val ACTION_UPDATE_NOW = "kz.ploshadki.recorder.action.ABP_UPDATE_NOW"
    private const val REQUEST_CODE = 0xAB0E
    private const val CHANNEL_ID = "abp_update_wait"
    private const val WAIT_NOTIF_ID = 0xAB0F

    /** The "Update" action for the "update available" notification, or null when the PIN gate is on. */
    fun updateNowIntent(context: Context): PendingIntent? {
        if (!PloshadkiFork.UPDATE_NOW_BUTTON || PloshadkiFork.ADMIN_PIN_FOR_UPDATES) return null
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE,
            Intent(context, AbpUpdateNowReceiver::class.java).setAction(ACTION_UPDATE_NOW),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Starts the install the person asked for (consent flag armed for this one tap). Never throws. */
    fun installNow(context: Context) {
        runCatching {
            AppPreferences(context).setUpdateInstallArmed(true)
            UpdateScheduler.enqueueInstallNow(context)
            TransportTimeline.event(context, "update: Update tapped in the notification")
        }
    }

    /** "A call is in progress, ABP Call updates right after it" — so a tap during a call is not met with silence. */
    fun showWaitingForCall(context: Context) {
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.update_notif_channel), NotificationManager.IMPORTANCE_LOW)
            )
            val text = context.getString(R.string.abp_update_waiting_call)
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle(context.getString(R.string.abp_update_notif_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build()
            nm.notify(WAIT_NOTIF_ID, n)
        }
    }

    fun cancelWaitingForCall(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(WAIT_NOTIF_ID) }
    }
}

/** Handles the notification's **Update** button. Not exported (see the manifest). */
class AbpUpdateNowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AbpUpdatePrompt.ACTION_UPDATE_NOW) return
        if (!PloshadkiFork.UPDATE_NOW_BUTTON || PloshadkiFork.ADMIN_PIN_FOR_UPDATES) return
        val app = context.applicationContext
        // The progress notification ("Downloading update… N%") takes over from here.
        UpdateNotifications.cancelAvailable(app)
        AbpUpdatePrompt.installNow(app)
    }
}
