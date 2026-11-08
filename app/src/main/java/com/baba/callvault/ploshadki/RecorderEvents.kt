/*
 * ABP Call — Ploshadki.kz fork of CallVault. GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import android.content.Context
import android.content.Intent
import com.baba.callvault.utils.AppLogger
import java.util.concurrent.CopyOnWriteArraySet

/**
 * What the recorder tells a host app (the CRM bridge) about recordings going to the company server.
 *
 * Privacy rule: an event never carries a phone number, a contact name or a file name. The [RecordingEvent.id]
 * is a random id made on this phone for one recording; the server learns the call from the upload itself.
 *
 * Two ways to listen, same content:
 *  - in-process: [addListener] (called on the recorder's background thread — hop to the UI thread yourself);
 *  - broadcast: action [action] (`<package>.ABP_RECORDING_EVENT`), sent only to this app's own package,
 *    extras [EXTRA_ID], [EXTRA_CHANNEL], [EXTRA_DURATION_MS] (-1 = not known yet), [EXTRA_STATE],
 *    [EXTRA_ERROR] (only for FAILED / WAITING_FOR_SERVER), [EXTRA_AT_MS].
 */
object RecorderEvents {

    /** Where the call came from. Personal WhatsApp is never recorded, so it never appears. */
    enum class Channel { GSM, WA_BUSINESS }

    enum class UploadState {
        /** Recording saved on the phone and put in the sending queue. */
        QUEUED,
        /** Phone not connected to the server (or its access was revoked): kept, sent after connecting. */
        WAITING_FOR_SERVER,
        /** Sending (also after a dropped connection, from where it stopped). */
        UPLOADING,
        /** The server accepted it. */
        SENT,
        /** The server refused it or sending gave up; [RecordingEvent.error] says why. */
        FAILED,
    }

    data class RecordingEvent(
        val id: String,
        val channel: Channel,
        /** Call length; null until the recorder has read the file (the QUEUED event usually has none). */
        val durationMs: Long?,
        val state: UploadState,
        val error: String?,
        val atMs: Long,
    ) {
        /** Plain map for a bridge (Capacitor `JSObject`, JSON): only strings, numbers and null. */
        fun toMap(): Map<String, Any?> = linkedMapOf(
            "id" to id,
            "channel" to channel.name,
            "durationMs" to durationMs,
            "state" to state.name,
            "error" to error,
            "atMs" to atMs,
        )
    }

    data class QueueStatus(
        /** Saved recordings still to be sent (queued or sending). */
        val waiting: Int,
        /** Recordings kept until the phone is connected to the server. */
        val waitingForServer: Int,
        val sentTotal: Int,
        val failedTotal: Int,
        /** Last refusal or give-up, without file names; null if none yet. */
        val lastError: String?,
        val lastErrorAtMs: Long?,
        val serverConnected: Boolean,
    ) {
        fun toMap(): Map<String, Any?> = linkedMapOf(
            "waiting" to waiting,
            "waitingForServer" to waitingForServer,
            "sentTotal" to sentTotal,
            "failedTotal" to failedTotal,
            "lastError" to lastError,
            "lastErrorAtMs" to lastErrorAtMs,
            "serverConnected" to serverConnected,
        )
    }

    fun interface Listener {
        fun onRecordingEvent(event: RecordingEvent)
    }

    const val ACTION_SUFFIX = ".ABP_RECORDING_EVENT"
    const val EXTRA_ID = "id"
    const val EXTRA_CHANNEL = "channel"
    const val EXTRA_DURATION_MS = "durationMs"
    const val EXTRA_STATE = "state"
    const val EXTRA_ERROR = "error"
    const val EXTRA_AT_MS = "atMs"

    private const val TAG = "ABP:Events"
    private val listeners = CopyOnWriteArraySet<Listener>()

    fun action(context: Context): String = context.packageName + ACTION_SUFFIX

    fun addListener(listener: Listener) { listeners += listener }

    fun removeListener(listener: Listener) { listeners -= listener }

    /** The channel a recording came from, by CallVault's file naming (app calls carry "_voip"). */
    fun channelOf(recordingName: String): Channel =
        if (recordingName.contains("_voip")) Channel.WA_BUSINESS else Channel.GSM

    fun queueStatus(context: Context): QueueStatus {
        val s = AbpStore.status(context)
        return QueueStatus(
            waiting = AbpStore.queuedCount(context),
            waitingForServer = AbpStore.pendingCount(context),
            sentTotal = s.sent,
            failedTotal = s.failed,
            lastError = AbpStore.lastError(context)?.first,
            lastErrorAtMs = AbpStore.lastError(context)?.second,
            serverConnected = AbpStore.connection(context) != null,
        )
    }

    /** Called by the upload code. Never throws: a broken listener must not stop a recording from being sent. */
    internal fun emit(context: Context, recordingName: String, state: UploadState, error: String? = null) {
        val event = RecordingEvent(
            id = AbpStore.recordingId(context, recordingName),
            channel = channelOf(recordingName),
            durationMs = AbpStore.durationMs(context, recordingName),
            state = state,
            error = error,
            atMs = System.currentTimeMillis(),
        )
        for (l in listeners) runCatching { l.onRecordingEvent(event) }
            .onFailure { AppLogger.w(TAG, "A recording listener failed: ${it.message}") }
        runCatching {
            context.sendBroadcast(
                Intent(action(context)).setPackage(context.packageName)
                    .putExtra(EXTRA_ID, event.id)
                    .putExtra(EXTRA_CHANNEL, event.channel.name)
                    .putExtra(EXTRA_DURATION_MS, event.durationMs ?: -1L)
                    .putExtra(EXTRA_STATE, event.state.name)
                    .putExtra(EXTRA_ERROR, event.error)
                    .putExtra(EXTRA_AT_MS, event.atMs)
            )
        }.onFailure { AppLogger.w(TAG, "Could not broadcast a recording event: ${it.message}") }
    }
}
