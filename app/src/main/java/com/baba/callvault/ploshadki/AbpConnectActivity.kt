/*
 * ABP Call — Ploshadki.kz fork of CallVault. GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.baba.callvault.ui.theme.CallVaultTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * «Подключение к серверу ABP». Opens from the enrollment link `abpcall://enroll?d=…` (QR or message from the
 * administrator / CRM) or from Settings → About. One tap registers the phone with the one-time code; from then
 * on every recording is sent to the company server automatically.
 */
class AbpConnectActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = intent?.data?.toString()
        setContent { CallVaultTheme { Surface(Modifier.fillMaxSize()) { Screen(initialLink = link) } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    @Composable
    private fun Screen(initialLink: String?) {
        val scope = rememberCoroutineScope()
        var connection by remember { mutableStateOf(AbpStore.connection(this)) }
        var link by remember { mutableStateOf(initialLink.orEmpty()) }
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        val status = remember(connection, message) { AbpStore.status(this) }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Сервер ABP", style = MaterialTheme.typography.headlineSmall)
            val c = connection
            if (c != null) {
                Text("Телефон подключён. Записи звонков отправляются на сервер автоматически, по Wi-Fi или мобильному интернету.")
                Text("Сервер: ${c.server}\nУстройство: ${c.deviceId}", style = MaterialTheme.typography.bodySmall)
                Text("Отправлено: ${status.sent}   Ошибок: ${status.failed}")
                status.last?.let {
                    Text(it + if (status.lastAt > 0) " (" + DateFormat.getDateTimeInstance().format(Date(status.lastAt)) + ")" else "",
                        style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text("Телефон не подключён. Откройте ссылку подключения от администратора (или отсканируйте QR-код) — либо вставьте её ниже.")
            }
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                label = { Text("Ссылка подключения (abpcall://enroll?…)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Button(
                enabled = !busy && link.isNotBlank(),
                onClick = {
                    busy = true
                    message = "Подключаю…"
                    scope.launch {
                        message = withContext(Dispatchers.IO) {
                            runCatching {
                                val p = AbpStore.parseLink(link)
                                // A new link replaces the old connection: fresh key, fresh device.
                                AbpStore.disconnect(this@AbpConnectActivity)
                                val deviceId = AbpServerClient(this@AbpConnectActivity).enroll(p)
                                AbpStore.saveConnection(this@AbpConnectActivity, p, deviceId)
                                AbpUploadWorker.flushPending(this@AbpConnectActivity)
                                "Готово: телефон подключён к ${p.server}"
                            }.getOrElse { "Не удалось подключить: ${it.message ?: it.javaClass.simpleName}" }
                        }
                        connection = AbpStore.connection(this@AbpConnectActivity)
                        if (connection != null) link = ""
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (connection == null) "Подключить" else "Подключить заново по новой ссылке") }
            if (connection != null) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        AbpStore.disconnect(this@AbpConnectActivity)
                        connection = null
                        message = "Телефон отключён от сервера. Записи остаются на телефоне."
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Отключить от сервера") }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
}
