/*
 * ABP Call — Ploshadki.kz fork of CallVault (https://github.com/madkongo/CallVault).
 * GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.baba.callvault.BuildConfig
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.data.recordings.RecordingsRepository
import com.baba.callvault.integrations.adb.TransportTimeline
import com.baba.callvault.system.permissions.PermissionChecks
import com.baba.callvault.system.storage.SafHelper
import com.baba.callvault.utils.AppLogger
import java.time.ZoneId

/**
 * Writes [AbpStatusReport] into the Drive folder the recordings go to (owner decision 12.11.2026, variant "A").
 *
 * [note] is called by the keep-alive on every liveness look (60 s watchdog, and the Doze alarm every 5–15 min).
 * It is cheap: only when the state changed or [PloshadkiFork.STATUS_FILE_INTERVAL_MINUTES] passed does it hand
 * the write to [AbpStatusWorker], off the main thread.
 *
 * The file is overwritten in place (same document, Drive keeps its id). If the provider refuses to overwrite,
 * the phone switches for good to one new `abp-call-status-YYYYMMDD-HH.json` per hour ([AbpStatusReport.hourlyName]).
 */
object AbpStatusFile {

    private const val TAG = "ABP:StatusFile"
    private const val PREFS = "abp_status_file"
    private const val K_LAST_KEY = "last_key"
    private const val K_LAST_AT = "last_at"
    private const val K_URI = "uri"
    private const val K_HOURLY = "hourly"
    private const val K_LAST_HOURLY = "last_hourly"
    private const val WORK = "abp-status-file"

    internal fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Never throws. [running]/[reason] are the keep-alive's own reading of the recorder. */
    fun note(context: Context, running: Boolean, reason: String?) {
        if (!PloshadkiFork.STATUS_FILE_ENABLED) return
        runCatching {
            val p = prefs(context)
            val key = AbpStatusReport.stateKey(running, reason)
            val now = System.currentTimeMillis()
            val due = AbpStatusReport.isDue(
                p.getString(K_LAST_KEY, null), p.getLong(K_LAST_AT, 0L), key, now,
                PloshadkiFork.STATUS_FILE_INTERVAL_MINUTES * 60_000L,
            )
            if (!due) return
            p.edit().putString(K_LAST_KEY, key).putLong(K_LAST_AT, now).apply()
            val request = OneTimeWorkRequestBuilder<AbpStatusWorker>()
                .setInputData(workDataOf(AbpStatusWorker.IN_RUNNING to running, AbpStatusWorker.IN_REASON to reason))
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request)
        }.onFailure { AppLogger.w(TAG, "Could not schedule the status file: ${it.javaClass.simpleName}") }
    }

    /** Builds the report and writes it. Worker thread. Never throws; returns a line for the timeline. */
    internal suspend fun write(context: Context, running: Boolean, reason: String?): String {
        val prefs = AppPreferences(context)
        val folder = prefs.getDriveFolderUri() ?: return "status file: Drive not configured — not written"
        val dir = runCatching { DocumentFile.fromTreeUri(context, folder) }.getOrNull()
        if (dir == null || !runCatching { dir.canWrite() }.getOrDefault(false)) return "status file: Drive folder not writable — not written"

        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val recordingStarts = runCatching {
            RecordingCatalog.all(context).mapNotNull { row ->
                runCatching { RecordingsRepository.parseName(row.displayName).startedAtMillis }.getOrNull()
                    ?: row.lastModified.takeIf { it > 0L }
            }
        }.getOrDefault(emptyList())
        val callLog = readCallLog(context, now - AbpStatusReport.MISSED_WINDOW_MS)
        val missed = AbpStatusReport.missedCalls(callLog.orEmpty(), recordingStarts, now)
        val json = AbpStatusReport.toJson(
            AbpStatusReport.Input(
                version = BuildConfig.VERSION_NAME,
                device = Build.MODEL.orEmpty(),
                running = running,
                reason = reason,
                nowMs = now,
                zone = zone,
                lastRecordingAtMs = recordingStarts.filter { it <= now }.maxOrNull(),
                missedCalls = missed,
            )
        )
        val bytes = json.toByteArray(Charsets.UTF_8)
        val p = prefs(context)
        val summary = "recorder=${if (running) "running" else "stopped"} reason=${reason ?: "-"} missed=${callLog?.let { missed.size } ?: "none (no call-log permission)"}" +
            (missed.firstOrNull()?.let { " newest=${AbpStatusReport.mask(it.number)}" } ?: "")

        if (!p.getBoolean(K_HOURLY, false)) {
            when (overwrite(context, dir, p, bytes)) {
                true -> return "status file: written ($summary)"
                false -> {
                    p.edit().putBoolean(K_HOURLY, true).apply()
                    TransportTimeline.event(context, "status file: Drive refused to overwrite — switching to one file per hour")
                }
                null -> return "status file: could not be created in Drive"
            }
        }
        val name = AbpStatusReport.hourlyName(now, zone)
        if (p.getString(K_LAST_HOURLY, null) == name) return "status file: hourly $name already written"
        if (runCatching { dir.findFile(name) }.getOrNull() == null) {
            val doc = runCatching { dir.createFile(AbpStatusReport.MIME, name) }.getOrNull()
                ?: return "status file: hourly $name could not be created"
            if (!writeTo(context, doc.uri, "w", bytes)) {
                runCatching { doc.delete() }
                return "status file: hourly $name write failed"
            }
        }
        p.edit().putString(K_LAST_HOURLY, name).apply()
        return "status file: hourly $name written ($summary)"
    }

    /** true = written; false = the file is there but the provider will not overwrite it; null = could not create. */
    private fun overwrite(context: Context, dir: DocumentFile, p: SharedPreferences, bytes: ByteArray): Boolean? {
        val cloud = SafHelper.isCloudFolder(dir.uri)
        fun replace(uri: Uri): Boolean =
            writeTo(context, uri, "wt", bytes) || (cloud && writeTo(context, uri, "w", bytes))

        p.getString(K_URI, null)?.let { saved ->
            if (replace(Uri.parse(saved))) return true
        }
        val existing = runCatching { dir.findFile(AbpStatusReport.FILE_NAME) }.getOrNull()
        if (existing != null) {
            if (existing.uri.toString() != p.getString(K_URI, null) && replace(existing.uri)) {
                p.edit().putString(K_URI, existing.uri.toString()).apply()
                return true
            }
            return false
        }
        val created = runCatching { dir.createFile(AbpStatusReport.MIME, AbpStatusReport.FILE_NAME) }.getOrNull() ?: return null
        if (!writeTo(context, created.uri, "w", bytes)) {
            runCatching { created.delete() }
            return null
        }
        p.edit().putString(K_URI, created.uri.toString()).apply()
        return true
    }

    private fun writeTo(context: Context, uri: Uri, mode: String, bytes: ByteArray): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri, mode)?.use { it.write(bytes); it.flush() } != null
    }.getOrElse { false }

    /** Incoming and outgoing carrier calls since [since]; null without READ_CALL_LOG (said in the timeline line). */
    private fun readCallLog(context: Context, since: Long): List<AbpStatusReport.CallRow>? {
        if (!PermissionChecks.hasCallLogPermission(context)) return null
        return runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE, CallLog.Calls.NUMBER),
                "${CallLog.Calls.DATE} > ?", arrayOf(since.toString()), "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val type = c.getInt(2)
                        if (type != CallLog.Calls.INCOMING_TYPE && type != CallLog.Calls.OUTGOING_TYPE) continue
                        add(AbpStatusReport.CallRow(c.getLong(0), type == CallLog.Calls.INCOMING_TYPE, c.getString(3), c.getLong(1)))
                    }
                }
            }.orEmpty()
        }.getOrElse {
            AppLogger.w(TAG, "Call log unreadable: ${it.javaClass.simpleName}")
            emptyList()
        }
    }
}

/** Off-main-thread writer for [AbpStatusFile]. One at a time (unique work, REPLACE). */
class AbpStatusWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val line = runCatching {
            AbpStatusFile.write(
                applicationContext,
                inputData.getBoolean(IN_RUNNING, false),
                inputData.getString(IN_REASON),
            )
        }.getOrElse { "status file: failed (${it.javaClass.simpleName})" }
        // Only changes go to the timeline, like the rest of it: a quiet day costs a few lines, not 144.
        val p = AbpStatusFile.prefs(applicationContext)
        if (p.getString(LAST_LINE, null) != line) {
            p.edit().putString(LAST_LINE, line).apply()
            runCatching { TransportTimeline.event(applicationContext, line) }
        }
        return Result.success()
    }

    companion object {
        const val IN_RUNNING = "running"
        const val IN_REASON = "reason"
        private const val LAST_LINE = "last_timeline_line"
    }
}
