/*
 * ABP Call — Ploshadki.kz fork of CallVault. GPL-3.0-or-later with the Section 7 terms in LICENSE.
 *
 * Capacitor bridge "AbpRecorder" (S5, docs/callvault/S5-INTERFACES.md I11): lets the web UI of a host app
 * (the CRM APK) see and drive the embedded recorder. Only status and navigation cross the bridge:
 * no phone numbers, contact names, file names, free-text errors or audio ever go to JavaScript.
 */
package kz.ploshadki.recorder.capacitor

import android.content.Context
import com.baba.callvault.ploshadki.RecorderEntry
import com.baba.callvault.ploshadki.RecorderEvents
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

@CapacitorPlugin(name = "AbpRecorder")
class AbpRecorderPlugin : Plugin() {

    /** Recorder events → JS event "recordingSaved" (called on a background thread; Capacitor posts to the WebView). */
    private val listener = RecorderEvents.Listener { event -> notifyListeners(EVENT_RECORDING, toJs(event)) }

    override fun load() {
        RecorderEntry.addRecordingListener(listener)
    }

    override fun handleOnDestroy() {
        RecorderEntry.removeRecordingListener(listener)
        super.handleOnDestroy()
    }

    /** Present only in a build that embeds the recorder; the JS side falls back when the plugin is missing. */
    @PluginMethod
    fun isAvailable(call: PluginCall) {
        call.resolve(JSObject().put("available", true).put("bridgeVersion", BRIDGE_VERSION))
    }

    /**
     * serverConnected — the phone is enrolled with the company server.
     * queue — pending (to send), waitingForServer (kept until connected), sent / failed totals,
     * hasError, lastErrorCode (RecorderEvents.ErrorCode name, no free text) and lastErrorAt (epoch ms).
     */
    @PluginMethod
    fun status(call: PluginCall) {
        val q = RecorderEntry.queueStatus(context)
        val queue = JSObject()
            .put("pending", q.waiting)
            .put("waitingForServer", q.waitingForServer)
            .put("sent", q.sentTotal)
            .put("failed", q.failedTotal)
            .put("hasError", q.lastError != null)
            .put("lastErrorCode", q.lastError)
            .put("lastErrorAt", q.lastErrorAtMs)
        call.resolve(JSObject().put("serverConnected", q.serverConnected).put("queue", queue))
    }

    /** Recorder home: setup wizard on first run (permissions, Wireless debugging), then recordings. */
    @PluginMethod
    fun openSetup(call: PluginCall) {
        RecorderEntry.openRecorder(hostContext())
        call.resolve()
    }

    /** «Сервер ABP» screen; with {link: "abpcall://enroll?d=…"} it opens pre-filled (no QR inside one app). */
    @PluginMethod
    fun connect(call: PluginCall) {
        val link = call.getString("link")
        val host = hostContext()
        if (link.isNullOrBlank()) {
            RecorderEntry.openServerConnection(host)
        } else {
            if (!link.startsWith("abpcall://enroll?")) {
                call.reject("not an ABP enrollment link", "BAD_LINK")
                return
            }
            RecorderEntry.openServerConnection(host, link)
        }
        call.resolve()
    }

    /** The bridge activity when there is one (no NEW_TASK flag needed), else the application context. */
    private fun hostContext(): Context = activity ?: context

    companion object {
        const val BRIDGE_VERSION = 2 // v2: queue status, recordingSaved, error codes
        const val EVENT_RECORDING = "recordingSaved"

        /** Contract channel names (#492): mobile_gsm / whatsapp; upload states: queued|uploading|uploaded|failed. */
        fun toJs(e: RecorderEvents.RecordingEvent): JSObject = JSObject()
            .put("id", e.id)
            .put("channel", if (e.channel == RecorderEvents.Channel.GSM) "mobile_gsm" else "whatsapp")
            .put("durationSec", e.durationMs?.let { (it + 500) / 1000 })
            .put("uploadState", when (e.state) {
                RecorderEvents.UploadState.QUEUED, RecorderEvents.UploadState.WAITING_FOR_SERVER -> "queued"
                RecorderEvents.UploadState.UPLOADING -> "uploading"
                RecorderEvents.UploadState.SENT -> "uploaded"
                RecorderEvents.UploadState.FAILED -> "failed"
            })
            .put("waitingForServer", e.state == RecorderEvents.UploadState.WAITING_FOR_SERVER)
            .put("errorCode", e.error?.name)
            .put("at", e.atMs)
    }
}
