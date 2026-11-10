/*
 * ABP Call — Ploshadki.kz fork of CallVault. GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.baba.callvault.BuildConfig
import com.baba.callvault.data.recordings.RecordingDirection
import com.baba.callvault.data.recordings.RecordingsRepository
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Sends one finished recording to the company server over ANY network (Wi-Fi or mobile data), resumably:
 * create (refusals such as personal WhatsApp come back before the file is sent) → 1 MiB chunks from the
 * server's offset → the last chunk returns the receipt. A dropped connection resumes from the server's offset.
 */
class AbpUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val uri = Uri.parse(inputData.getString(KEY_URI) ?: return@withContext Result.failure())
        val name = inputData.getString(KEY_NAME) ?: return@withContext Result.failure()
        val mime = inputData.getString(KEY_MIME)
        val conn = AbpStore.connection(applicationContext)
        if (conn == null) {
            AbpStore.addPending(applicationContext, uri.toString(), name, mime.orEmpty())
            AbpStore.setQueued(applicationContext, name, false)
            RecorderEvents.emit(applicationContext, name, RecorderEvents.UploadState.WAITING_FOR_SERVER)
            AppLogger.i(TAG, "'$name' kept for later: the phone is not connected to the server yet")
            return@withContext Result.success()
        }
        try {
            upload(conn, uri, name, mime)
        } catch (e: AbpServerClient.DeviceRevokedException) {
            AbpStore.recordResult(applicationContext, name, false, e.message ?: "устройство отключено", RecorderEvents.ErrorCode.DEVICE_REVOKED)
            AbpStore.addPending(applicationContext, uri.toString(), name, mime.orEmpty())
            AbpStore.setQueued(applicationContext, name, false)
            RecorderEvents.emit(applicationContext, name, RecorderEvents.UploadState.WAITING_FOR_SERVER, RecorderEvents.ErrorCode.DEVICE_REVOKED)
            Result.failure()
        } catch (e: java.io.FileNotFoundException) {
            done(name, false, "файл записи не найден", RecorderEvents.ErrorCode.FILE_MISSING)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Upload of '$name' will be retried: ${e.message}")
            if (runAttemptCount >= MAX_ATTEMPTS) done(name, false, e.message ?: e.javaClass.simpleName, RecorderEvents.ErrorCode.SENDING_GAVE_UP)
            else Result.retry()
        }
    }

    private suspend fun upload(conn: AbpStore.Connection, uri: Uri, name: String, mime: String?): Result {
        val kind = CaptureReport.mediaKind(mime, name)
            ?: return done(name, false, "формат не поддерживается сервером ($mime)", RecorderEvents.ErrorCode.UNSUPPORTED_FORMAT)
        val bytes = applicationContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw java.io.FileNotFoundException(name)
        if (bytes.isEmpty()) return done(name, false, "пустой файл", RecorderEvents.ErrorCode.EMPTY_FILE)
        val sha = AbpCrypto.hex(AbpCrypto.sha256(bytes))
        val format = probe(uri, name)
        AbpStore.setDurationMs(applicationContext, name, format.durationMs)
        RecorderEvents.emit(applicationContext, name, RecorderEvents.UploadState.UPLOADING)
        val client = AbpServerClient(applicationContext)

        // Resume a started upload first.
        var uploadId = AbpStore.uploadId(applicationContext, name)
        var offset = -1L
        var chunkMax = DEFAULT_CHUNK
        if (uploadId != null) {
            val h = client.uploadOffset(conn, uploadId)
            if (h.code == 200) offset = h.headers["upload-offset"]?.toLongOrNull() ?: 0L else uploadId = null
        }
        if (uploadId == null) {
            val upload = buildUpload(conn, format, name, kind, bytes.size.toLong(), sha)
            val signature = AbpCrypto.signCapture(conn.secret, upload, sha)
            val r = client.createUpload(conn, upload, signature)
            when {
                r.code == 503 -> return Result.retry()
                (r.code == 200 || r.code == 201) && r.json().optString("status") == "RECEIVED" -> return done(name, true, "принято сервером (${r.json().optString("recordingId")})")
                (r.code == 200 || r.code == 201) && r.json().optString("status") == "UPLOADING" -> {
                    val j = r.json()
                    uploadId = j.getString("uploadId")
                    offset = j.optLong("offset", 0L)
                    chunkMax = j.optInt("maxChunkBytes", DEFAULT_CHUNK).coerceIn(64 * 1024, 8 * 1024 * 1024)
                    AbpStore.setUploadId(applicationContext, name, uploadId)
                }
                r.code in 400..499 -> return done(name, false, "сервер отказал: HTTP ${r.code} ${r.reason()}", RecorderEvents.ErrorCode.SERVER_REFUSED)
                else -> throw java.io.IOException("HTTP ${r.code}")
            }
        }
        val id = uploadId!!
        while (true) {
            val end = minOf(bytes.size.toLong(), offset + chunkMax).toInt()
            val chunk = bytes.copyOfRange(offset.toInt(), end)
            val r = client.appendChunk(conn, id, offset, chunk)
            when (r.code) {
                202 -> offset = r.headers["upload-offset"]?.toLongOrNull() ?: end.toLong()
                409 -> offset = r.headers["upload-offset"]?.toLongOrNull() ?: r.json().optLong("offset", 0L)
                200, 201 -> {
                    AbpStore.setUploadId(applicationContext, name, null)
                    return done(name, true, "принято сервером (${r.json().optString("recordingId")})")
                }
                503 -> return Result.retry()
                404, 410 -> { AbpStore.setUploadId(applicationContext, name, null); return Result.retry() }
                401 -> {
                    // The envelope was signed > 5 min ago (slow network): start again with a fresh signature.
                    AbpStore.setUploadId(applicationContext, name, null)
                    if (r.reason() == "SIGNATURE_STALE") return Result.retry()
                    return done(name, false, "сервер отказал: HTTP 401 ${r.reason()}", RecorderEvents.ErrorCode.SERVER_REFUSED)
                }
                else -> {
                    AbpStore.setUploadId(applicationContext, name, null)
                    return done(name, false, "сервер отказал: HTTP ${r.code} ${r.reason()}", RecorderEvents.ErrorCode.SERVER_REFUSED)
                }
            }
        }
    }

    /** [detail] is for the phone's own status screen and the log; a host only ever sees [code]. */
    private suspend fun done(name: String, ok: Boolean, detail: String, code: RecorderEvents.ErrorCode? = null): Result {
        AbpStore.recordResult(applicationContext, name, ok, detail, code)
        AbpStore.setQueued(applicationContext, name, false)
        finishDeferredLocalDelete(name)
        RecorderEvents.emit(
            applicationContext, name,
            if (ok) RecorderEvents.UploadState.SENT else RecorderEvents.UploadState.FAILED,
            if (ok) null else (code ?: RecorderEvents.ErrorCode.SERVER_REFUSED),
        )
        AppLogger.i(TAG, "'$name': $detail")
        return if (ok) Result.success() else Result.failure()
    }

    /**
     * Режим «Только в Google Drive»: копия в Drive уже сделана, а удаление файла на телефоне ждало конца
     * отправки (см. [AbpStore.holdsLocal]). Отправка закончена (принята или окончательно отклонена) — удалить
     * файл на телефоне, как требует режим, и убрать ссылку на него из каталога. Режим сменили — оставить.
     */
    private suspend fun finishDeferredLocalDelete(name: String) {
        val local = AbpStore.takeDeferredLocalDelete(applicationContext, name) ?: return
        if (com.baba.callvault.data.AppPreferences(applicationContext).getStorageTarget() != com.baba.callvault.data.StorageTarget.DRIVE) return
        runCatching {
            val uri = Uri.parse(local)
            val doc = androidx.documentfile.provider.DocumentFile.fromSingleUri(applicationContext, uri)
            if (doc != null && com.baba.callvault.system.storage.SafHelper.deleteDocument(doc, "the device copy of '$name'")) {
                com.baba.callvault.data.recordings.RecordingCatalog.removeCopyByUri(applicationContext, uri)
            }
        }.onFailure { AppLogger.w(TAG, "Deferred device-copy delete of '$name' failed: ${it.message}") }
    }

    private class AudioFormat(val channels: Int, val sampleRate: Int, val durationMs: Long)

    private fun probe(uri: Uri, name: String): AudioFormat {
        var channels = 1
        var sampleRate = 48000
        var durationMs = 0L
        runCatching {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(applicationContext, uri, null)
                if (ex.trackCount > 0) {
                    val f = ex.getTrackFormat(0)
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (f.containsKey(MediaFormat.KEY_DURATION)) durationMs = f.getLong(MediaFormat.KEY_DURATION) / 1000L
                }
            } finally { ex.release() }
        }.onFailure { AppLogger.w(TAG, "Could not read the audio format of '$name': ${it.message}") }
        return AudioFormat(channels, sampleRate, durationMs)
    }

    private suspend fun buildUpload(conn: AbpStore.Connection, format: AudioFormat, name: String, kind: Pair<String, String>, size: Long, sha: String): Map<String, Any?> {
        val channels = format.channels
        val sampleRate = format.sampleRate
        val durationMs = format.durationMs
        val parsed = RecordingsRepository.parseName(name)
        val isVoip = RecorderEvents.channelOf(name) == RecorderEvents.Channel.WA_BUSINESS
        val started = parsed.startedAtMillis ?: (System.currentTimeMillis() - durationMs)
        val direction = when (parsed.direction) {
            RecordingDirection.INCOMING -> CaptureReport.Direction.INBOUND
            RecordingDirection.OUTGOING -> CaptureReport.Direction.OUTBOUND
            else -> CaptureReport.Direction.UNKNOWN
        }
        // App calls: the near party is on the left by construction (see VoipRecordingCoordinator).
        // Carrier calls: the channel order is an OEM detail, so it is only claimed when CallVault has learned it.
        val near = if (isVoip) CaptureReport.NearChannel.FIRST else nearForCarrier()
        val autoTime = runCatching { Settings.Global.getInt(applicationContext.contentResolver, Settings.Global.AUTO_TIME) == 1 }.getOrDefault(false)
        val source = if (isVoip) CaptureReport.Source.WHATSAPP_BUSINESS else CaptureReport.Source.GSM
        val sourceVersion = if (isVoip) packageVersion(CaptureReport.WA_BUSINESS_APP_ID) else "Android ${Build.VERSION.RELEASE}"

        return CaptureReport.build(
            identity = CaptureReport.Identity(
                tenantId = conn.tenantId, deviceRef = conn.deviceId, enrollmentRef = conn.enrollmentRef,
                employeeRef = conn.employeeRef, sourceAccountId = conn.sourceAccountId,
                model = "${Build.MANUFACTURER} ${Build.MODEL}", osBuild = "Android ${Build.VERSION.RELEASE} ${Build.DISPLAY}",
            ),
            policy = CaptureReport.Policy(conn.retentionPolicyId, conn.retentionDays, conn.consentRef, conn.consentMethod),
            call = CaptureReport.Call(
                reportId = CaptureReport.reportIdFor(conn.enrollmentRef, name, sha),
                source = source, direction = direction,
                startedAtMs = started, endedAtMs = started + durationMs,
                sourceAppVersion = sourceVersion, near = near,
                clockSource = if (autoTime) "NETWORK" else "DEVICE_UNSYNCED",
            ),
            media = CaptureReport.Media(kind.first, kind.second, sampleRate, channels, durationMs, size, sha),
            signedAtMs = System.currentTimeMillis(),
            captureToolVersion = BuildConfig.VERSION_NAME,
        )
    }

    private suspend fun nearForCarrier(): CaptureReport.NearChannel = runCatching {
        when (com.baba.callvault.data.transcripts.SpeakerTurnsRepository.trustedMap(applicationContext).nearChannelKey()) {
            "A" -> CaptureReport.NearChannel.FIRST
            "B" -> CaptureReport.NearChannel.SECOND
            else -> CaptureReport.NearChannel.UNKNOWN
        }
    }.getOrDefault(CaptureReport.NearChannel.UNKNOWN)

    @Suppress("DEPRECATION")
    private fun packageVersion(pkg: String): String = runCatching {
        applicationContext.packageManager.getPackageInfo(pkg, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")

    companion object {
        private const val TAG = "ABP:Upload"
        const val KEY_URI = "uri"
        const val KEY_NAME = "name"
        const val KEY_MIME = "mime"
        private const val DEFAULT_CHUNK = 1024 * 1024
        private const val MAX_ATTEMPTS = 30

        /** Queue [name] for sending. Any network (mobile data included); retried with backoff; one job per file. */
        fun enqueue(context: Context, uri: Uri, name: String, mime: String?) {
            val data = Data.Builder()
                .putString(KEY_URI, uri.toString())
                .putString(KEY_NAME, name)
                .putString(KEY_MIME, mime)
                .build()
            val request = OneTimeWorkRequestBuilder<AbpUploadWorker>()
                .setInputData(data)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG_ALL)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("abp_upload:$name", ExistingWorkPolicy.KEEP, request)
            AbpStore.setQueued(context, name, true)
            RecorderEvents.emit(context, name, RecorderEvents.UploadState.QUEUED)
        }

        const val TAG_ALL = "abp_upload"

        /** Sends everything recorded while the phone was not connected. */
        fun flushPending(context: Context) {
            for ((uri, name, mime) in AbpStore.takePending(context)) enqueue(context, Uri.parse(uri), name, mime.ifBlank { null })
        }
    }
}
