/*
 * ABP Call — Ploshadki.kz fork of CallVault. GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import android.app.ActivityManager
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.data.transcripts.db.TranscriptState
import com.baba.callvault.summary.CallSummary
import com.baba.callvault.summary.SummaryModel
import com.baba.callvault.summary.SummaryScheduler
import com.baba.callvault.transcription.model.ModelDownloadWorker
import com.baba.callvault.transcription.model.ModelRepository
import com.baba.callvault.transcription.cloud.CloudEndpoint
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Sends the on-device transcript (and the on-device summary, if one already exists) of a recording ABP Call
 * uploaded to POST /air/capture/v1/transcripts, signed like an upload with an empty body.
 *
 * Sent only when ALL hold: the phone is enrolled; this recording was uploaded by ABP Call under the current
 * enrollment ([AbpStore.uploadedRef]); its transcript is DONE; and it was made by CallVault's own whisper
 * (a transcript from a user-configured cloud service is never sent as `callvault-whisper`).
 *
 * Logs carry the reportId and HTTP codes only — never the words, the summary, the file name or a number.
 */
class AbpTranscriptWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val name = inputData.getString(KEY_NAME) ?: return@withContext Result.failure()
        val conn = AbpStore.connection(applicationContext) ?: return@withContext Result.success()
        val ref = AbpStore.uploadedRef(applicationContext, name) ?: return@withContext Result.success()
        if (ref.enrollmentRef != conn.enrollmentRef) {
            AppLogger.i(TAG, "${ref.reportId}: uploaded under another enrollment; transcript not sent")
            return@withContext Result.success()
        }
        if (!TranscriptDatabase.exists(applicationContext)) return@withContext Result.success()
        val db = TranscriptDatabase.get(applicationContext)
        val transcript = db.transcriptDao().findTranscript(name)?.takeIf { it.state == TranscriptState.DONE }
            ?: return@withContext Result.success()
        val modelId = transcript.modelId
        if (modelId.isNullOrBlank() || CloudEndpoint.isCloudEngineId(modelId)) {
            AppLogger.i(TAG, "${ref.reportId}: not an on-device whisper transcript; not sent")
            return@withContext Result.success()
        }
        try {
            val rows = db.transcriptDao().segmentsFor(name)
                .map { TranscriptReport.Segment(it.startMs, it.endMs, it.text, it.speaker) }
            val summary = runCatching { db.summaryDao().summary(name) }.getOrNull()?.let { entry ->
                val text = entry.searchText.ifBlank { CallSummary.parse(entry.document)?.searchableText().orEmpty() }
                TranscriptReport.Summary(entry.model, text)
            }
            val envelope = TranscriptReport.build(
                reportId = ref.reportId,
                enrollmentRef = conn.enrollmentRef,
                mediaSha256 = ref.mediaSha256,
                modelId = modelId,
                language = transcript.language,
                rows = rows,
                near = AbpUploadWorker.nearChannelFor(applicationContext, name),
                summary = summary,
                signedAtMs = System.currentTimeMillis(),
            )
            val body = TranscriptReport.body(envelope, TranscriptReport.sign(conn.secret, envelope))
            val r = AbpServerClient(applicationContext).postTranscript(conn, body)
            val reason = r.reason()
            when (TranscriptReport.outcomeOf(r.code, reason)) {
                TranscriptReport.Outcome.DONE -> {
                    AppLogger.i(TAG, "${ref.reportId}: HTTP ${r.code} ${r.json().optString("status")}")
                    Result.success()
                }
                TranscriptReport.Outcome.RETRY -> {
                    AppLogger.i(TAG, "${ref.reportId}: HTTP ${r.code} $reason, attempt ${runAttemptCount + 1}; will retry")
                    if (runAttemptCount + 1 >= MAX_ATTEMPTS) Result.failure() else Result.retry()
                }
                TranscriptReport.Outcome.GIVE_UP -> {
                    AppLogger.w(TAG, "${ref.reportId}: HTTP ${r.code} $reason; not resent")
                    Result.failure()
                }
            }
        } catch (e: AbpServerClient.DeviceRevokedException) {
            AppLogger.w(TAG, "${ref.reportId}: device revoked; transcript not sent")
            Result.failure()
        } catch (e: IllegalArgumentException) {
            // The envelope cannot be built (no model id, bad hash): nothing a retry would change.
            AppLogger.w(TAG, "${ref.reportId}: envelope refused locally (${e.javaClass.simpleName})")
            Result.failure()
        } catch (e: Exception) {
            // Network and other passing failures. The message is not logged: it never holds the words, but a
            // future library could put a request body in it.
            AppLogger.w(TAG, "${ref.reportId}: ${e.javaClass.simpleName}, attempt ${runAttemptCount + 1}; will retry")
            if (runAttemptCount + 1 >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    companion object {
        private const val TAG = "ABP:Transcript"
        const val KEY_NAME = "name"
        const val TAG_ALL = "abp_transcript"
        /** With 60 s exponential backoff (WorkManager caps each wait at 5 h) this covers about two days. */
        private const val MAX_ATTEMPTS = 20

        /**
         * Queues the transcript of [name] for sending when it is ours to send: enrolled, uploaded by ABP Call
         * (or uploading — the server answers 404 until the audio is in, and this retries), and not a cloud
         * transcript. Called right after the transcript is marked DONE. Cheap; never throws.
         */
        fun enqueue(context: Context, name: String, modelId: String?, delayMinutes: Long = 0) {
            runCatching {
                if (CloudEndpoint.isCloudEngineId(modelId)) return
                if (!AbpStore.isEnrolled(context) || AbpStore.uploadedRef(context, name) == null) return
                val request = OneTimeWorkRequestBuilder<AbpTranscriptWorker>()
                    .setInputData(Data.Builder().putString(KEY_NAME, name).build())
                    .setInitialDelay(delayMinutes.coerceAtLeast(0), TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
                    .addTag(TAG_ALL)
                    .build()
                // REPLACE: a re-transcription supersedes a send still waiting; the server deduplicates by reportId.
                WorkManager.getInstance(context).enqueueUniqueWork("abp_transcript:$name", ExistingWorkPolicy.REPLACE, request)
            }.onFailure { AppLogger.w(TAG, "Could not queue a transcript: ${it.javaClass.simpleName}") }
        }

        /** After an upload succeeds: queue the transcript if it was already DONE (it finished before the audio was sent). */
        suspend fun enqueueIfTranscribed(context: Context, name: String) {
            runCatching {
                if (!TranscriptDatabase.exists(context)) return
                val t = TranscriptDatabase.get(context).transcriptDao().findTranscript(name) ?: return
                if (t.state == TranscriptState.DONE) onTranscribed(context, name, t.modelId)
            }.onFailure { AppLogger.w(TAG, "Could not check for a finished transcript: ${it.javaClass.simpleName}") }
        }

        /**
         * Called when an on-device transcript is DONE (and after an upload that found one DONE). Applies the
         * automatic summary ([PloshadkiFork.AUTO_SUMMARY_ENABLED], [AutoSummaryPlan]) and queues the send:
         * with a summary to come, the send is queued with a [PloshadkiFork.AUTO_SUMMARY_WAIT_MINUTES] delay
         * as a safety net, and [onSummaryFinished] replaces it with an immediate one when the summary ends.
         * Only for recordings that are ours to send. Never throws.
         */
        suspend fun onTranscribed(context: Context, name: String, modelId: String?) {
            runCatching {
                if (CloudEndpoint.isCloudEngineId(modelId)) return
                if (!AbpStore.isEnrolled(context) || AbpStore.uploadedRef(context, name) == null) return
                val model = SummaryModel.DEFAULT
                val summaryExists = TranscriptDatabase.exists(context) &&
                    runCatching { TranscriptDatabase.get(context).summaryDao().summary(name) }.getOrNull() != null
                val step = AutoSummaryPlan.decide(
                    enabled = PloshadkiFork.AUTO_SUMMARY_ENABLED,
                    summaryExists = summaryExists,
                    modelInstalled = ModelRepository.isInstalled(context, model),
                    totalRamBytes = totalRam(context),
                )
                AppLogger.i(TAG, "Auto summary: $step")
                when (step) {
                    AutoSummaryPlan.Step.SEND_NOW -> enqueue(context, name, modelId)
                    AutoSummaryPlan.Step.DOWNLOAD_MODEL_AND_SEND_NOW -> {
                        ModelDownloadWorker.enqueue(context, model) // Wi-Fi only, KEEP: idempotent
                        enqueue(context, name, modelId)
                    }
                    AutoSummaryPlan.Step.SUMMARISE_THEN_SEND -> {
                        enqueue(context, name, modelId, PloshadkiFork.AUTO_SUMMARY_WAIT_MINUTES)
                        SummaryScheduler.runNow(context, name, model)
                    }
                }
            }.onFailure {
                AppLogger.w(TAG, "Auto summary skipped: ${it.javaClass.simpleName}")
                enqueue(context, name, modelId)
            }
        }

        /**
         * Called by the summary job when it ends, whatever the outcome: the transcript goes now (with the
         * summary if one was stored). For a call that is not ours [enqueue] does nothing; for one already
         * sent the server answers DUPLICATE. Never throws.
         */
        suspend fun onSummaryFinished(context: Context, name: String) {
            runCatching {
                if (!TranscriptDatabase.exists(context)) return
                val t = TranscriptDatabase.get(context).transcriptDao().findTranscript(name) ?: return
                if (t.state == TranscriptState.DONE) enqueue(context, name, t.modelId)
            }.onFailure { AppLogger.w(TAG, "Could not queue a transcript after its summary: ${it.javaClass.simpleName}") }
        }

        private fun totalRam(context: Context): Long = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
        }.getOrDefault(0L)
    }
}
