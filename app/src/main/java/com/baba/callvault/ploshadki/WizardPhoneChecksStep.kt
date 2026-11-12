/*
 * ABP Call — Ploshadki.kz fork of CallVault (https://github.com/madkongo/CallVault),
 * itself a fork of ShizuCallRecorder. GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.integrations.adb.AdbShell
import com.baba.callvault.integrations.adb.ShellGrantGate
import com.baba.callvault.system.permissions.PermissionChecks
import com.baba.callvault.ui.common.CvCard
import com.baba.callvault.ui.common.rememberResumeCount
import com.baba.callvault.ui.screens.NavigationRow
import com.baba.callvault.utils.AppLogger

/**
 * ABP Call wizard step "Phone check" ([PloshadkiFork.WIZARD_PHONE_CHECKS]): every phone setting recording depends
 * on, each with its state and a button to the screen that changes it. Re-read whenever the person comes back from
 * Settings. Never withholds Next: a reading can be wrong on a ROM we have not seen.
 *
 * "Keep recording off Wi-Fi" is not repeated here: the reliability step just before shows the real switch.
 */
@Composable
fun WizardPhoneChecksStep(usesEmbeddedAdb: Boolean) {
    val context = LocalContext.current
    val resume = rememberResumeCount()
    var bump by remember { mutableIntStateOf(0) }
    val marks = remember { PhoneCheckMarks(context) }
    val isXiaomi = remember { runCatching { AdbShell.oemGate(context) == ShellGrantGate.OemGate.XIAOMI }.getOrDefault(false) }
    val checks = remember(isXiaomi, usesEmbeddedAdb) { PhoneCheckState.checksFor(isXiaomi, usesEmbeddedAdb) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (check in checks) {
            val read = remember(resume, bump) { readState(context, check) }
            val marked = remember(resume, bump) { marks.isMarked(check) }
            val status = PhoneCheckState.status(read, marked)
            CvCard {
                NavigationRow(
                    icon = iconFor(check),
                    label = stringResource(labelFor(check)),
                    value = stringResource(
                        when {
                            status == PhoneCheckStatus.DONE -> R.string.abp_phone_check_done
                            check == PhoneCheck.KEEP_WIRELESS_DEBUGGING_ON -> R.string.abp_phone_check_turn_on
                            else -> R.string.abp_phone_check_open
                        }
                    ),
                    valueColor = if (status == PhoneCheckStatus.DONE) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.secondary,
                    onClick = if (status == PhoneCheckStatus.DONE && check == PhoneCheck.KEEP_WIRELESS_DEBUGGING_ON) null else {
                        {
                            if (check == PhoneCheck.KEEP_WIRELESS_DEBUGGING_ON) {
                                AppPreferences(context).setWirelessDebuggingEnforced(true)
                                bump++
                            } else {
                                openFirst(context, PhoneCheckState.screensFor(check, context.packageName, isXiaomi))
                            }
                        }
                    },
                    showChevron = !(status == PhoneCheckStatus.DONE && check == PhoneCheck.KEEP_WIRELESS_DEBUGGING_ON),
                    infoExtra = stringResource(howToFor(check)),
                )
                // Where the phone will not say, the person's word is the answer — and can be taken back.
                if (read == null) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { marks.setMarked(check, !marked); bump++ }) {
                            Text(stringResource(if (marked) R.string.abp_phone_check_unmark else R.string.abp_phone_check_mark_done))
                        }
                    }
                }
            }
        }
    }
}

/** What the phone says about [check]: true on, false off, null when it will not say. */
private fun readState(context: Context, check: PhoneCheck): Boolean? = runCatching {
    when (check) {
        PhoneCheck.USB_DEBUGGING -> AdbShell.usbDebuggingState(context).let { if (it.isOn) true else if (it.isOff) false else null }
        PhoneCheck.XIAOMI_USB_SECURITY -> PhoneCheckState.xiaomiSecurityRead(
            systemProperty(ShellGrantGate.XIAOMI_PROPERTY),
            AdbShell.hasWriteSecureSettings(context),
        )
        PhoneCheck.KEEP_WIRELESS_DEBUGGING_ON -> AppPreferences(context).isWirelessDebuggingEnforced()
        PhoneCheck.BATTERY_UNRESTRICTED -> PermissionChecks.hasBatteryExemption(context)
        PhoneCheck.XIAOMI_AUTOSTART, PhoneCheck.SYSTEM_AUTO_UPDATE_OFF -> null
    }
}.getOrNull()

private fun systemProperty(key: String): String = runCatching {
    Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as? String ?: ""
}.getOrDefault("")

/** Launches the first screen that opens; OEM screens are missing on other ROMs, so the list carries fallbacks. */
private fun openFirst(context: Context, intents: List<Intent>) {
    for (intent in intents) {
        if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
    AppLogger.w("ABP:PhoneChecks", "No settings screen opened")
}

private fun iconFor(check: PhoneCheck) = when (check) {
    PhoneCheck.USB_DEBUGGING -> Icons.Filled.Usb
    PhoneCheck.XIAOMI_USB_SECURITY -> Icons.Filled.Security
    PhoneCheck.XIAOMI_AUTOSTART -> Icons.Filled.PlayCircle
    PhoneCheck.KEEP_WIRELESS_DEBUGGING_ON -> Icons.Filled.Wifi
    PhoneCheck.BATTERY_UNRESTRICTED -> Icons.Filled.BatteryStd
    PhoneCheck.SYSTEM_AUTO_UPDATE_OFF -> Icons.Filled.SystemUpdate
}

private fun labelFor(check: PhoneCheck): Int = when (check) {
    PhoneCheck.USB_DEBUGGING -> R.string.abp_phone_check_usb_debugging
    PhoneCheck.XIAOMI_USB_SECURITY -> R.string.abp_phone_check_xiaomi_security
    PhoneCheck.XIAOMI_AUTOSTART -> R.string.abp_phone_check_xiaomi_autostart
    PhoneCheck.KEEP_WIRELESS_DEBUGGING_ON -> R.string.settings_wd_enforce_label
    PhoneCheck.BATTERY_UNRESTRICTED -> R.string.abp_phone_check_battery
    PhoneCheck.SYSTEM_AUTO_UPDATE_OFF -> R.string.abp_phone_check_system_updates
}

private fun howToFor(check: PhoneCheck): Int = when (check) {
    PhoneCheck.USB_DEBUGGING -> R.string.abp_phone_check_usb_debugging_how
    PhoneCheck.XIAOMI_USB_SECURITY -> R.string.abp_phone_check_xiaomi_security_how
    PhoneCheck.XIAOMI_AUTOSTART -> R.string.abp_phone_check_xiaomi_autostart_how
    PhoneCheck.KEEP_WIRELESS_DEBUGGING_ON -> R.string.abp_phone_check_keep_wd_how
    PhoneCheck.BATTERY_UNRESTRICTED -> R.string.abp_phone_check_battery_how
    PhoneCheck.SYSTEM_AUTO_UPDATE_OFF -> R.string.abp_phone_check_system_updates_how
}
