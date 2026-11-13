/*
 * ABP Call — Ploshadki.kz fork of CallVault (https://github.com/madkongo/CallVault).
 * GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.baba.callvault.BuildConfig
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.RecordingState
import com.baba.callvault.integrations.adb.TransportTimeline
import com.baba.callvault.server.RecorderBackend
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.services.recording.DaemonKeepAliveService
import com.baba.callvault.services.recording.KeepAlivePolicy
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Owner request 13.11.2026: after an update, check that recording came back; otherwise the loud "Recording stopped".
 *
 * The replace receiver already restarts the recorder and the keep-alive (`PostUpdateRecovery`), and the keep-alive
 * raises [AbpRecorderDownAlert] after a long outage. But that alert lives *inside* the keep-alive: if the replace left
 * the keep-alive itself down (a foreground-service start refused, a Xiaomi autostart block), nobody would ever say
 * so. This is an independent check, [PloshadkiFork.POST_UPDATE_CHECK_MINUTES] after the replace, run by WorkManager:
 * recorder up → note it; down → one more restart, then the alert and a "stopped" line in the CRM status file.
 */
object AbpPostUpdateCheck {

    enum class Verdict { SKIPPED_BY_USER, UP, RECOVERED, DOWN }

    /** Pure; unit-tested. [aliveAfterRetry] is null when no retry was made. */
    fun verdict(stoppedByUser: Boolean, aliveFirst: Boolean, aliveAfterRetry: Boolean?): Verdict = when {
        stoppedByUser -> Verdict.SKIPPED_BY_USER
        aliveFirst -> Verdict.UP
        aliveAfterRetry == true -> Verdict.RECOVERED
        else -> Verdict.DOWN
    }

    /** Whether the alert must be raised. Only [Verdict.DOWN]: a user's own "Stop" is not an outage. */
    fun mustAlert(v: Verdict): Boolean = v == Verdict.DOWN

    const val WORK_NAME = "abp_post_update_check"

    /** Queued by the replace receiver. REPLACE: a second update restarts the countdown. Never throws. */
    fun enqueue(context: Context) {
        if (!PloshadkiFork.POST_UPDATE_CHECK) return
        runCatching {
            val request = OneTimeWorkRequestBuilder<AbpPostUpdateCheckWorker>()
                .setInitialDelay(PloshadkiFork.POST_UPDATE_CHECK_MINUTES, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }.onFailure { AppLogger.w("ABP:PostUpdate", "Could not queue the post-update check: ${it.javaClass.simpleName}") }
    }
}

class AbpPostUpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private fun recorderAlive(): Boolean = runCatching {
        RecorderConnection.service?.asBinder()?.pingBinder() == true
    }.getOrDefault(false)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val prefs = AppPreferences(ctx)
        val stopped = prefs.getRecordingState() == RecordingState.STOPPED
        var aliveFirst = false
        var aliveAfterRetry: Boolean? = null
        if (!stopped) {
            aliveFirst = recorderAlive()
            if (!aliveFirst) {
                // One more try from here, in case the replace receiver's restart was the thing that failed.
                runCatching { RecorderBackend.ensureRunning(ctx) }
                if (KeepAlivePolicy.shouldRun(prefs.getPrivilegedMode(), prefs.isVoipRecordingEnabled())) {
                    runCatching { DaemonKeepAliveService.start(ctx) }
                }
                delay(RETRY_SETTLE_MS)
                aliveAfterRetry = recorderAlive()
            }
        }
        val v = AbpPostUpdateCheck.verdict(stopped, aliveFirst, aliveAfterRetry)
        runCatching { TransportTimeline.event(ctx, "post-update check ${BuildConfig.VERSION_NAME}: ${v.name.lowercase()}") }
        if (AbpPostUpdateCheck.mustAlert(v)) {
            AbpRecorderDownAlert.show(ctx)
            AbpStatusFile.note(ctx, running = false, reason = REASON)
        } else if (v != AbpPostUpdateCheck.Verdict.SKIPPED_BY_USER) {
            AbpRecorderDownAlert.cancel(ctx)
        }
        Result.success()
    }

    private companion object {
        const val RETRY_SETTLE_MS = 30_000L
        /** An existing reason code of abp-call-status.json (the recorder helper is not running), known to the CRM. */
        const val REASON = "helper_dead"
    }
}
