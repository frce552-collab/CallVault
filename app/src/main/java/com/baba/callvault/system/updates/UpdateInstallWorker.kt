/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.updates

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The user-initiated install (Home banner "Update"). Runs in WorkManager rather than a ViewModel
 * scope so the 80+ MB download and install SURVIVE the user navigating away or a config change —
 * the outcome is reported through the updater's notifications, and the Home banner spinner is driven
 * by this work's observable state.
 *
 * [UpdateManager.checkForUpdate] is called with reconcile=false: the user is actively retrying, so a
 * stale failure from a previous attempt must not surface a scary notification mid-retry.
 */
class UpdateInstallWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val preferences = AppPreferences(applicationContext)
        // Explicit-consent gate: only run when the user actually tapped Update (armed just before
        // enqueue). Consume the flag immediately so a WorkManager re-run of THIS work after an
        // interruption (process killed mid-install) becomes a no-op instead of silently reinstalling.
        if (!preferences.isUpdateInstallArmed()) {
            AppLogger.i(TAG, "Install work ran without a fresh tap (stale re-run); skipping")
            return@withContext Result.success()
        }
        preferences.setUpdateInstallArmed(false)

        val release = UpdateManager.checkForUpdate(applicationContext, reconcile = false)
            ?: return@withContext Result.success()
        // ABP Call (owner 13.11): a tap during a call installs right after the call, not never and not now.
        if (!waitForNoCall()) return@withContext deferForCall(preferences)
        com.baba.callvault.ploshadki.AbpUpdatePrompt.cancelWaitingForCall(applicationContext)
        // User-tapped install: reopen the UI on the new version when it lands.
        val dispatched = UpdateManager.downloadAndInstall(
            applicationContext, release, allowInteractiveFallback = true
        ) { percent ->
            // Publish to WorkManager so the Home banner can show the download percentage.
            setProgressAsync(workDataOf(KEY_PROGRESS to percent))
        }
        // A call that started during the download: the installer refused (shell) or was not offered (system
        // installer); try again after the call instead of leaving the tap unanswered.
        if (!dispatched && !CallInProgressGate.mayInstall(applicationContext)) return@withContext deferForCall(preferences)
        Result.success()
    }

    /**
     * Waits inside this run (WorkManager stops a run at 10 minutes) while a call is in progress, telling the person.
     * @return true once no call is in progress.
     */
    private suspend fun waitForNoCall(): Boolean {
        if (!com.baba.callvault.ploshadki.PloshadkiFork.UPDATE_NOW_BUTTON) return true
        val until = System.currentTimeMillis() + WAIT_IN_RUN_MS
        while (!CallInProgressGate.mayInstall(applicationContext)) {
            com.baba.callvault.ploshadki.AbpUpdatePrompt.showWaitingForCall(applicationContext)
            setProgress(workDataOf(KEY_PROGRESS to WAITING_FOR_CALL))
            if (System.currentTimeMillis() >= until) return false
            kotlinx.coroutines.delay(CALL_POLL_MS)
        }
        return true
    }

    /** Still in a call: re-arm the one-tap consent for THIS tap and come back shortly (backoff in UpdateScheduler). */
    private fun deferForCall(preferences: AppPreferences): Result {
        if (runAttemptCount + 1 >= MAX_CALL_DEFERRALS) {
            // Hours of calls: give up on this tap; the banner, the notification and the night install remain.
            com.baba.callvault.ploshadki.AbpUpdatePrompt.cancelWaitingForCall(applicationContext)
            return Result.success()
        }
        preferences.setUpdateInstallArmed(true)
        com.baba.callvault.ploshadki.AbpUpdatePrompt.showWaitingForCall(applicationContext)
        return Result.retry()
    }

    companion object {
        private const val TAG = "CV:UpdateInstallWorker"

        /** WorkManager progress key: download percentage (0-100). */
        const val KEY_PROGRESS = "progress_percent"

        /** [KEY_PROGRESS] value while the install waits for a call to end (ABP Call). */
        const val WAITING_FOR_CALL = -2
        private const val CALL_POLL_MS = 15_000L
        private const val WAIT_IN_RUN_MS = 9 * 60_000L
        /** ~9 min per run + backoff: about 3 hours of back-to-back calls before this tap is dropped. */
        private const val MAX_CALL_DEFERRALS = 18
    }
}
