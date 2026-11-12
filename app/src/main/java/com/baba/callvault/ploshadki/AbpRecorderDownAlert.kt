/*
 * ABP Call — Ploshadki.kz fork of CallVault (https://github.com/madkongo/CallVault).
 * GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.baba.callvault.MainActivity
import com.baba.callvault.R

/**
 * The loud, ongoing "Recording stopped — tap" notification (owner request 12.11): shown when the recorder has
 * been down for [PloshadkiFork.RECORDER_DOWN_ALERT_MINUTES] and automatic recovery has not brought it back.
 * The keep-alive's own notice is IMPORTANCE_MIN on purpose (a permanent status line) and nobody sees it change.
 * One action — open the app, where Home offers the fix for the actual cause. Cancelled as soon as the recorder is up.
 */
object AbpRecorderDownAlert {
    private const val CHANNEL_ID = "abp_recorder_down"
    private const val NOTIF_ID = 0xAB0D

    fun show(context: Context) {
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.abp_recorder_down_channel), NotificationManager.IMPORTANCE_HIGH)
            )
            val open = PendingIntent.getActivity(
                context, NOTIF_ID,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle(context.getString(R.string.abp_recorder_down_title))
                .setContentText(context.getString(R.string.abp_recorder_down_text))
                .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.abp_recorder_down_text)))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(0, context.getString(R.string.abp_recorder_down_action), open)
                .build()
            nm.notify(NOTIF_ID, n)
        }
    }

    fun cancel(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) }
    }
}
