/*
 * ABP Call — Ploshadki.kz fork of CallVault (https://github.com/madkongo/CallVault).
 * GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import com.baba.callvault.integrations.adb.TransportTimeline
import com.baba.callvault.services.recording.DaemonKeepAliveService
import com.baba.callvault.utils.AppLogger

/**
 * A liveness check that also runs in Doze / HyperOS deep sleep, where the keep-alive's 60 s Handler watchdog
 * does not tick (Handler delays are uptime-based and stop while the CPU sleeps).
 *
 * Uses `setAndAllowWhileIdle` (no special permission; Android batches it, roughly every 9–15 min in deep Doze). Each firing holds a partial wake lock
 * while the keep-alive checks the recorder and relaunches it if it is down; every firing is written to the
 * always-on transport timeline, which is part of "Share debug report".
 */
class AbpLivenessAlarm : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val app = context.applicationContext
        val wl = (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "abpcall:liveness")
        runCatching { wl.acquire(WAKE_MS) }
        val pending = goAsync()
        Thread {
            try {
                val handled = DaemonKeepAliveService.livenessCheckFromAlarm(app)
                if (!handled) {
                    // The keep-alive is not running (killed by the system): start it; it checks on start.
                    runCatching { TransportTimeline.event(app, "liveness alarm: keep-alive not running — starting it") }
                    DaemonKeepAliveService.start(app)
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Liveness check failed: ${e.javaClass.simpleName}")
            } finally {
                // Keep the lock for the relaunch the check may have started (it runs on its own thread).
                pending.finish()
            }
        }.apply { isDaemon = true; name = "abp-liveness" }.start()
    }

    companion object {
        private const val TAG = "ABP:Liveness"
        const val ACTION = "kz.ploshadki.recorder.LIVENESS_CHECK"
        private const val REQUEST_CODE = 0xAB9
        /** Long enough for one bounded relaunch (45 s) plus ADB connect. */
        private const val WAKE_MS = 90_000L

        private fun pendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context, REQUEST_CODE,
                Intent(context, AbpLivenessAlarm::class.java).setAction(ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        /** (Re)schedules the next check. Idempotent: one pending alarm per app. Never throws. */
        fun schedule(context: Context, delayMs: Long) {
            runCatching {
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val at = SystemClock.elapsedRealtime() + delayMs
                val pi = pendingIntent(context)
                // Inexact on purpose: no SCHEDULE_EXACT_ALARM permission (denied by default on Android 14). Still fires
                // in Doze; Android may batch it (about every 9–15 min in deep idle).
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            }.onFailure { AppLogger.w(TAG, "Could not schedule the liveness alarm: ${it.javaClass.simpleName}") }
        }

        fun cancel(context: Context) {
            runCatching { (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pendingIntent(context)) }
        }
    }
}
