/*
 * ABP Call — Ploshadki.kz fork of CallVault (https://github.com/madkongo/CallVault).
 * GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.baba.callvault.integrations.adb.TransportTimeline
import com.baba.callvault.system.updates.UpdateManager
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** When an unattended update may run. Pure; unit-tested. */
object AbpAutoUpdateWindow {
    fun isOpen(now: ZonedDateTime, from: Int = PloshadkiFork.AUTO_INSTALL_FROM_HOUR, until: Int = PloshadkiFork.AUTO_INSTALL_UNTIL_HOUR): Boolean {
        val h = now.hour
        return if (from > until) h >= from || h < until else h in from until until
    }

    /** Zero inside the window, otherwise the time until it next opens. */
    fun delayUntilOpen(now: ZonedDateTime, from: Int = PloshadkiFork.AUTO_INSTALL_FROM_HOUR, until: Int = PloshadkiFork.AUTO_INSTALL_UNTIL_HOUR): Duration {
        if (isOpen(now, from, until)) return Duration.ZERO
        var open = now.withHour(from).withMinute(0).withSecond(0).withNano(0)
        if (!open.isAfter(now)) open = open.plusDays(1)
        return Duration.between(now, open)
    }

    fun zone(): ZoneId = ZoneId.of(PloshadkiFork.AUTO_INSTALL_ZONE)
}

/**
 * Installs the newest main-channel release by itself (owner decision 12.11). Queued by the update check when a
 * release is found; waits for the night window, unmetered Wi-Fi and the charger (WorkManager constraints), then
 * installs silently through the recorder's shell ([UpdateManager.downloadAndInstall] with no interactive fallback:
 * no shell or a call in progress → it simply tries again later). After the update the keep-alive brings the
 * recorder back, and the "Recording stopped" alert fires if it does not come back.
 */
class AbpAutoInstallWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!PloshadkiFork.AUTO_INSTALL_UPDATES || !PloshadkiFork.UPDATES_ENABLED) return@withContext Result.success()
        val now = ZonedDateTime.now(AbpAutoUpdateWindow.zone())
        if (!AbpAutoUpdateWindow.isOpen(now)) {
            enqueue(applicationContext) // outside the window: come back when it opens
            return@withContext Result.success()
        }
        val release = UpdateManager.checkForUpdate(applicationContext, reconcile = false)
            ?: return@withContext Result.success()
        runCatching { TransportTimeline.event(applicationContext, "auto-update ${release.tag}: installing (night, Wi-Fi, charging)") }
        val dispatched = runCatching {
            UpdateManager.downloadAndInstall(applicationContext, release, allowInteractiveFallback = false)
        }.onFailure { AppLogger.w(TAG, "Auto-update failed: ${it.javaClass.simpleName}") }.getOrDefault(false)
        if (!dispatched) {
            runCatching { TransportTimeline.event(applicationContext, "auto-update ${release.tag}: not installed now, will retry") }
            if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.success()
        } else Result.success()
    }

    companion object {
        private const val TAG = "ABP:AutoUpdate"
        const val WORK_NAME = "abp_auto_update"
        private const val MAX_ATTEMPTS = 6

        /** Queues the unattended install for the next window. Idempotent (KEEP). Never throws. */
        fun enqueue(context: Context) {
            if (!PloshadkiFork.AUTO_INSTALL_UPDATES || !PloshadkiFork.UPDATES_ENABLED) return
            runCatching {
                val delay = AbpAutoUpdateWindow.delayUntilOpen(ZonedDateTime.now(AbpAutoUpdateWindow.zone()))
                val request = OneTimeWorkRequestBuilder<AbpAutoInstallWorker>()
                    .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.UNMETERED)
                            .setRequiresCharging(true)
                            .build()
                    )
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
            }.onFailure { AppLogger.w(TAG, "Could not queue the auto-update: ${it.javaClass.simpleName}") }
        }
    }
}
