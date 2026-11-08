/*
 * ABP Call — Ploshadki.kz fork of CallVault. GPL-3.0-or-later with the Section 7 terms in LICENSE.
 *
 * Capacitor bridge "AbpRecorder" (S5, docs/callvault/S5-INTERFACES.md I11): lets the web UI of a host app
 * (the CRM APK) see and drive the embedded recorder. Only status and navigation cross the bridge:
 * no phone numbers, contact names, file names or audio ever go to JavaScript.
 */
package kz.ploshadki.recorder.capacitor

import android.content.Context
import com.baba.callvault.ploshadki.AbpStore
import com.baba.callvault.ploshadki.RecorderEntry
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

@CapacitorPlugin(name = "AbpRecorder")
class AbpRecorderPlugin : Plugin() {

    /** Present only in a build that embeds the recorder; the JS side falls back when the plugin is missing. */
    @PluginMethod
    fun isAvailable(call: PluginCall) {
        call.resolve(JSObject().put("available", true).put("bridgeVersion", BRIDGE_VERSION))
    }

    /**
     * serverConnected — the phone is enrolled with the company server (recordings are being sent).
     * sent / failed — upload counters; lastResultAt — epoch ms of the last attempt, lastOk — its outcome.
     * The free-text last result is NOT passed: it carries a file name, which may hold a number or a name.
     */
    @PluginMethod
    fun status(call: PluginCall) {
        val ctx = context
        val st = AbpStore.status(ctx)
        val last = st.last
        val queue = JSObject()
            .put("sent", st.sent)
            .put("failed", st.failed)
            .put("lastResultAt", if (st.lastAt > 0) st.lastAt else null)
            .put("lastOk", last?.startsWith("отправлено"))
        call.resolve(
            JSObject()
                .put("serverConnected", RecorderEntry.isServerConnected(ctx))
                .put("queue", queue)
        )
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
        const val BRIDGE_VERSION = 1
    }
}
