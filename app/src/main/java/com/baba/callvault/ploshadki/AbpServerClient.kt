/*
 * ABP Call — Ploshadki.kz fork of CallVault. GPL-3.0-or-later with the Section 7 terms in LICENSE.
 *
 * HTTP client for the company server's OPEN contract only:
 *  - device login: POST /api/v1/worker-auth/enroll, POST /oauth/token (DPoP, RFC 9449);
 *  - recordings:   POST /air/capture/v1/uploads, HEAD|PATCH /air/capture/v1/uploads/:id (resumable).
 */
package com.baba.callvault.ploshadki

import android.content.Context
import android.os.Build
import com.baba.callvault.BuildConfig
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class AbpServerClient(private val context: Context) {

    class HttpResult(val code: Int, val body: String, val headers: Map<String, String>) {
        fun json(): JSONObject = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
        fun reason(): String = json().optString("reason", json().optString("error", ""))
    }

    /** The device credential is gone (revoked, or refresh refused): a new enrollment link is needed. */
    class DeviceRevokedException : IOException("устройство отключено на сервере — нужна новая ссылка подключения")

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun execute(request: Request): HttpResult = http.newCall(request).execute().use { r: Response ->
        HttpResult(r.code, r.body?.string().orEmpty(), r.headers.associate { it.first.lowercase() to it.second })
    }

    // ── enrollment ───────────────────────────────────────────────────────────────────────────

    /** Registers this phone with the one-time code. Returns the server's device id. */
    fun enroll(p: AbpStore.Provisioning): String {
        val url = "${p.server}/api/v1/worker-auth/enroll"
        val body = AbpCrypto.canonicalJson(
            mapOf(
                "tenantId" to p.tenantId,
                "enrollmentId" to p.enrollmentId,
                "enrollmentCode" to p.enrollmentCode,
                "deviceInfo" to mapOf(
                    "appVersion" to "ABP Call ${BuildConfig.VERSION_NAME}".take(100),
                    "androidVersion" to "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})".take(100),
                    "model" to "${Build.MANUFACTURER} ${Build.MODEL}".take(200),
                ),
            )
        ).toRequestBody(JSON)
        val r = execute(
            Request.Builder().url(url).post(body)
                .header("DPoP", DpopKey.proof("POST", url, null))
                .build()
        )
        if (r.code != 201 && r.code != 200) throw IOException("сервер отказал во входе (HTTP ${r.code} ${r.reason()})")
        val j = r.json()
        saveTokens(j)
        return j.getJSONObject("device").getString("id")
    }

    private fun saveTokens(j: JSONObject) {
        val expiresIn = j.optLong("expiresIn", 900L)
        val prev = AbpStore.tokens(context)
        AbpStore.saveTokens(
            context,
            AbpStore.Tokens(
                accessToken = j.getString("accessToken"),
                accessExpiresAtMs = System.currentTimeMillis() + expiresIn * 1000L,
                refreshToken = j.optString("refreshToken", "").ifBlank { prev?.refreshToken ?: throw IOException("сервер не выдал refresh-токен") },
            )
        )
    }

    /** A valid access token, refreshed when it is about to expire. */
    @Synchronized
    fun accessToken(conn: AbpStore.Connection, forceRefresh: Boolean = false): String {
        val t = AbpStore.tokens(context) ?: throw DeviceRevokedException()
        if (!forceRefresh && t.accessExpiresAtMs - System.currentTimeMillis() > 60_000L) return t.accessToken
        val url = "${conn.server}/oauth/token"
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", "android-worker")
            .add("refresh_token", t.refreshToken)
            .build()
        val r = execute(Request.Builder().url(url).post(form).header("DPoP", DpopKey.proof("POST", url, null)).build())
        if (r.code == 400 && r.reason() == "invalid_grant") throw DeviceRevokedException()
        if (r.code != 200) throw IOException("не удалось обновить вход (HTTP ${r.code})")
        saveTokens(r.json())
        return AbpStore.tokens(context)!!.accessToken
    }

    // ── recordings ───────────────────────────────────────────────────────────────────────────

    private fun authed(conn: AbpStore.Connection, method: String, path: String, body: RequestBody?, extra: Map<String, String>, retried: Boolean = false): HttpResult {
        val url = "${conn.server}$path"
        val token = accessToken(conn, forceRefresh = retried)
        val builder = Request.Builder().url(url)
            .header("Authorization", "DPoP $token")
            .header("DPoP", DpopKey.proof(method, url, token))
        extra.forEach { (k, v) -> builder.header(k, v) }
        builder.method(method, body)
        val r = execute(builder.build())
        // 401 with an expired/rotated token: refresh once and repeat (a fresh DPoP proof each time).
        if (r.code == 401 && !retried && r.reason() != "SIGNATURE_INVALID" && r.reason() != "SIGNATURE_STALE") {
            return authed(conn, method, path, body, extra, retried = true)
        }
        return r
    }

    fun createUpload(conn: AbpStore.Connection, upload: Map<String, Any?>, signature: String): HttpResult =
        authed(conn, "POST", "/air/capture/v1/uploads",
            AbpCrypto.canonicalJson(mapOf("upload" to upload, "signature" to signature)).toRequestBody(JSON), emptyMap())

    fun uploadOffset(conn: AbpStore.Connection, uploadId: String): HttpResult =
        authed(conn, "HEAD", "/air/capture/v1/uploads/$uploadId", null, emptyMap())

    fun appendChunk(conn: AbpStore.Connection, uploadId: String, offset: Long, chunk: ByteArray): HttpResult =
        authed(conn, "PATCH", "/air/capture/v1/uploads/$uploadId", chunk.toRequestBody(OFFSET_OCTETS),
            mapOf("Upload-Offset" to offset.toString()))

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val OFFSET_OCTETS = "application/offset+octet-stream".toMediaType()
    }
}
