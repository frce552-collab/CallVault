/*
 * ABP Call — Ploshadki.kz fork of CallVault. GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONObject

/**
 * Connection to the company server, stored in app-private preferences (no secrets ship in the APK:
 * everything here arrives with the one-time enrollment link the administrator gives the manager).
 */
object AbpStore {
    private const val FILE = "abp_call_server"

    /** What the enrollment link (`abpcall://enroll?d=…`) carries. Version 1. */
    data class Provisioning(
        val server: String,
        val tenantId: String,
        val enrollmentId: String,
        val enrollmentCode: String,
        val enrollmentRef: String,
        val employeeRef: String,
        val sourceAccountId: String,
        val secret: ByteArray,
        val retentionPolicyId: String,
        val retentionDays: Int,
        val consentRef: String,
        val consentMethod: String,
    )

    data class Connection(
        val server: String,
        val tenantId: String,
        val deviceId: String,
        val enrollmentRef: String,
        val employeeRef: String,
        val sourceAccountId: String,
        val secret: ByteArray,
        val retentionPolicyId: String,
        val retentionDays: Int,
        val consentRef: String,
        val consentMethod: String,
    )

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Parses `abpcall://enroll?d=<base64url(JSON)>` (or the bare base64url / JSON text, for manual paste). */
    fun parseLink(text: String): Provisioning {
        val trimmed = text.trim()
        val payload = when {
            trimmed.startsWith("{") -> trimmed
            trimmed.contains("://") -> {
                val d = Uri.parse(trimmed).getQueryParameter("d") ?: throw IllegalArgumentException("в ссылке нет данных подключения")
                String(AbpCrypto.b64urlDecode(d), Charsets.UTF_8)
            }
            else -> String(AbpCrypto.b64urlDecode(trimmed), Charsets.UTF_8)
        }
        val j = JSONObject(payload)
        require(j.optInt("v", 1) == 1) { "неизвестная версия ссылки" }
        val server = j.getString("server").trimEnd('/')
        require(server.startsWith("https://") || server.startsWith("http://")) { "адрес сервера должен начинаться с https://" }
        val secret = AbpCrypto.b64urlDecode(j.getString("secret"))
        require(secret.size >= 16) { "секрет записи слишком короткий" }
        return Provisioning(
            server = server,
            tenantId = j.getString("tenantId"),
            enrollmentId = j.getString("enrollmentId"),
            enrollmentCode = j.getString("enrollmentCode"),
            enrollmentRef = j.getString("enrollmentRef"),
            employeeRef = j.getString("employeeRef"),
            sourceAccountId = j.getString("sourceAccountId"),
            secret = secret,
            retentionPolicyId = j.optString("retentionPolicyId", "callvault-180d"),
            retentionDays = j.optInt("retentionDays", 180),
            consentRef = j.optString("consentRef", "tenant-policy"),
            consentMethod = j.optString("consentMethod", "TENANT_POLICY"),
        )
    }

    fun saveConnection(context: Context, p: Provisioning, deviceId: String) {
        prefs(context).edit()
            .putString("server", p.server)
            .putString("tenantId", p.tenantId)
            .putString("deviceId", deviceId)
            .putString("enrollmentRef", p.enrollmentRef)
            .putString("employeeRef", p.employeeRef)
            .putString("sourceAccountId", p.sourceAccountId)
            .putString("secret", AbpCrypto.b64url(p.secret))
            .putString("retentionPolicyId", p.retentionPolicyId)
            .putInt("retentionDays", p.retentionDays)
            .putString("consentRef", p.consentRef)
            .putString("consentMethod", p.consentMethod)
            .apply()
    }

    fun connection(context: Context): Connection? {
        val sp = prefs(context)
        val server = sp.getString("server", null) ?: return null
        val deviceId = sp.getString("deviceId", null) ?: return null
        return Connection(
            server = server,
            tenantId = sp.getString("tenantId", null) ?: return null,
            deviceId = deviceId,
            enrollmentRef = sp.getString("enrollmentRef", null) ?: return null,
            employeeRef = sp.getString("employeeRef", null) ?: return null,
            sourceAccountId = sp.getString("sourceAccountId", null) ?: return null,
            secret = AbpCrypto.b64urlDecode(sp.getString("secret", null) ?: return null),
            retentionPolicyId = sp.getString("retentionPolicyId", "callvault-180d")!!,
            retentionDays = sp.getInt("retentionDays", 180),
            consentRef = sp.getString("consentRef", "tenant-policy")!!,
            consentMethod = sp.getString("consentMethod", "TENANT_POLICY")!!,
        )
    }

    fun disconnect(context: Context) {
        prefs(context).edit().clear().apply()
        runCatching { DpopKey.reset() }
    }

    // ── tokens ────────────────────────────────────────────────────────────────────────────────
    data class Tokens(val accessToken: String, val accessExpiresAtMs: Long, val refreshToken: String)

    fun saveTokens(context: Context, t: Tokens) {
        prefs(context).edit()
            .putString("accessToken", t.accessToken)
            .putLong("accessExpiresAtMs", t.accessExpiresAtMs)
            .putString("refreshToken", t.refreshToken)
            .apply()
    }

    fun tokens(context: Context): Tokens? {
        val sp = prefs(context)
        return Tokens(
            sp.getString("accessToken", null) ?: return null,
            sp.getLong("accessExpiresAtMs", 0L),
            sp.getString("refreshToken", null) ?: return null,
        )
    }

    // ── per-recording upload state ───────────────────────────────────────────────────────────
    fun uploadId(context: Context, name: String): String? = prefs(context).getString("up:$name", null)

    fun setUploadId(context: Context, name: String, uploadId: String?) {
        prefs(context).edit().apply { if (uploadId == null) remove("up:$name") else putString("up:$name", uploadId) }.apply()
    }

    /** Recordings made before the phone was connected: sent once it is. */
    fun addPending(context: Context, uri: String, name: String, mime: String) {
        val sp = prefs(context)
        val set = sp.getStringSet("pending", emptySet())!!.toMutableSet()
        set += JSONObject().put("uri", uri).put("name", name).put("mime", mime).toString()
        sp.edit().putStringSet("pending", set).apply()
    }

    fun takePending(context: Context): List<Triple<String, String, String>> {
        val sp = prefs(context)
        val set = sp.getStringSet("pending", emptySet())!!
        sp.edit().remove("pending").apply()
        return set.mapNotNull { runCatching { JSONObject(it).let { j -> Triple(j.getString("uri"), j.getString("name"), j.getString("mime")) } }.getOrNull() }
    }

    // ── status shown to the manager ──────────────────────────────────────────────────────────
    fun recordResult(context: Context, name: String, ok: Boolean, detail: String) {
        val sp = prefs(context)
        sp.edit()
            .putInt(if (ok) "sentCount" else "failedCount", sp.getInt(if (ok) "sentCount" else "failedCount", 0) + 1)
            .putString("lastResult", "${if (ok) "отправлено" else "ошибка"}: $name — $detail")
            .putLong("lastResultAt", System.currentTimeMillis())
            .apply()
    }

    data class Status(val sent: Int, val failed: Int, val last: String?, val lastAt: Long)

    fun status(context: Context): Status {
        val sp = prefs(context)
        return Status(sp.getInt("sentCount", 0), sp.getInt("failedCount", 0), sp.getString("lastResult", null), sp.getLong("lastResultAt", 0L))
    }
}
