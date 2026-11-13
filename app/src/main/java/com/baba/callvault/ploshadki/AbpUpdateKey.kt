/*
 * ABP Call — Ploshadki.kz fork of CallVault (https://github.com/madkongo/CallVault).
 * GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.baba.callvault.R
import com.baba.callvault.integrations.adb.TransportTimeline
import com.baba.callvault.system.permissions.PermissionChecks
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The read-only key phones use to fetch updates from our private releases repo expires on a date
 * ([PloshadkiFork.UPDATE_KEY_EXPIRES]). The owner is reminded by the workflow ploshadki-key-reminder.yml;
 * the phone only says something once the key is already dead. Logic here is pure and unit-tested.
 */
object AbpUpdateKey {

    enum class State { UNKNOWN, OK, REMIND, EXPIRED }

    /** Parses the ISO date; blank or malformed = unknown (never treated as expired). */
    fun expiry(raw: String = PloshadkiFork.UPDATE_KEY_EXPIRES): LocalDate? =
        raw.trim().takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** Days from [today] to the expiry day; 0 = expires today, negative = already expired. */
    fun daysLeft(expires: LocalDate, today: LocalDate): Long = ChronoUnit.DAYS.between(today, expires)

    /**
     * The key works through its expiry day and is dead the day after (GitHub expires fine-grained tokens at the end
     * of the day). [authFailed] = GitHub answered 401 to the last update check: dead whatever the date says.
     */
    fun state(
        expires: LocalDate?,
        today: LocalDate,
        authFailed: Boolean = false,
        remindDays: Long = PloshadkiFork.UPDATE_KEY_REMIND_DAYS,
    ): State {
        if (authFailed) return State.EXPIRED
        if (expires == null) return State.UNKNOWN
        val left = daysLeft(expires, today)
        return when {
            left < 0 -> State.EXPIRED
            left <= remindDays -> State.REMIND
            else -> State.OK
        }
    }

    /** The phone speaks only when the key is dead, at most once per [NOTICE_EVERY_DAYS]. */
    fun shouldNotifyPhone(state: State, lastNoticeDay: LocalDate?, today: LocalDate): Boolean =
        PloshadkiFork.UPDATE_KEY_EXPIRED_NOTICE && state == State.EXPIRED &&
            (lastNoticeDay == null || ChronoUnit.DAYS.between(lastNoticeDay, today) >= NOTICE_EVERY_DAYS)

    const val NOTICE_EVERY_DAYS = 7L

    // ---- Android side --------------------------------------------------------------------------

    /** Set by GitHubReleases when the releases API answers 401 (bad or expired key); cleared on 200. */
    @Volatile
    var lastAuthFailed: Boolean = false

    /** Called from GitHubReleases with the HTTP code of the latest-release query. */
    fun onReleaseQuery(httpCode: Int) {
        if (httpCode == 401) lastAuthFailed = true
        else if (httpCode == 200) lastAuthFailed = false
    }

    private const val PREFS = "abp_update_key"
    private const val KEY_LAST_NOTICE = "last_notice_day"
    private const val CHANNEL_ID = "app_updates" // same channel as UpdateNotifications
    private const val NOTIFICATION_ID = 4719      // 47xx block after UpdateNotifications (4716–4718)

    /** Run after the daily update check. */
    fun checkAndNotify(context: Context, today: LocalDate = LocalDate.now(AbpAutoUpdateWindow.zone())) {
        val state = state(expiry(), today, lastAuthFailed)
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getString(KEY_LAST_NOTICE, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (!shouldNotifyPhone(state, last, today)) return
        runCatching { TransportTimeline.event(context, "update key expired: updates no longer arrive") }
        if (!PermissionChecks.hasNotificationPermission(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.update_notif_channel), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { setShowBadge(false); setSound(null, null) }
            )
            val text = context.getString(R.string.abp_update_key_expired_text)
            manager.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_mic)
                    .setContentTitle(context.getString(R.string.abp_update_key_expired_title))
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .build()
            )
            prefs.edit().putString(KEY_LAST_NOTICE, today.toString()).apply()
        }
    }
}
