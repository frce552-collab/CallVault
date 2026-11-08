/*
 * ABP Call — Ploshadki.kz fork of CallVault. GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import com.baba.callvault.MainActivity
import com.baba.callvault.RecorderRuntime

/**
 * The recorder's public surface for a host app that embeds it (the CRM APK). Everything else in the
 * library is internal detail. ABP Call itself does not need this: it has its own icon and Application.
 *
 * Host checklist:
 *  1. `Application.onCreate()`: `super.onCreate(); RecorderEntry.init(this)`.
 *  2. Resources: `<bool name="abp_show_launcher_icon">false</bool>` to hide the recorder's own icon.
 *  3. Manifest placeholder `recorderAuthority = "${applicationId}.recorder"` (build file, S5).
 *  4. A button in the host UI → [openRecorder]; server connection → [openServerConnection].
 *  5. Sending status → [addRecordingListener] / [queueStatus] (or the [recordingEventAction] broadcast).
 */
object RecorderEntry {

    /** Same start-up work ABP Call's own Application does. Idempotent. */
    fun init(app: Application) = RecorderRuntime.init(app)

    /** Recorder home: setup wizard on first run, then recordings, status and settings. */
    fun openRecorder(context: Context) = context.startActivity(intent(context, MainActivity::class.java))

    /** «Сервер ABP»: connect this phone with the administrator's link and see sending status. */
    fun openServerConnection(context: Context) = context.startActivity(intent(context, AbpConnectActivity::class.java))

    /** Hand an enrollment link (`abpcall://enroll?d=…`) the host already has, e.g. from its own QR scanner. */
    fun openServerConnection(context: Context, enrollmentLink: String) = context.startActivity(
        intent(context, AbpConnectActivity::class.java).setData(android.net.Uri.parse(enrollmentLink))
    )

    /** Whether this phone is connected to the company server (recordings are being sent). */
    fun isServerConnected(context: Context): Boolean = AbpStore.connection(context) != null

    // ── events for the host (CRM bridge). No phone numbers, contact names or file names. ──────────

    /** Called for every recording that is saved and queued, then for each sending step. Background thread. */
    fun addRecordingListener(listener: RecorderEvents.Listener) = RecorderEvents.addListener(listener)

    fun removeRecordingListener(listener: RecorderEvents.Listener) = RecorderEvents.removeListener(listener)

    /** Same events as a broadcast to this app only: `<package>.ABP_RECORDING_EVENT`, extras in [RecorderEvents]. */
    fun recordingEventAction(context: Context): String = RecorderEvents.action(context)

    /** How many recordings wait to be sent, how many wait for the server connection, totals and the last error. */
    fun queueStatus(context: Context): RecorderEvents.QueueStatus = RecorderEvents.queueStatus(context)

    private fun intent(context: Context, cls: Class<*>): Intent =
        Intent(context, cls).apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
}
