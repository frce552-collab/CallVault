/*
 * ABP Call — Ploshadki.kz fork of CallVault (https://github.com/madkongo/CallVault).
 * GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.baba.callvault.R

/**
 * Administrator PIN, set once when the phone is set up. Shown on Home while no PIN exists; it cannot be dismissed,
 * so the person setting the phone up (the administrator) chooses it before handing the phone over.
 */
@Composable
fun AdminPinSetupDialog(onDone: () -> Unit) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val badFormat = stringResource(R.string.abp_admin_pin_bad_format, AdminPin.MIN_LENGTH, AdminPin.MAX_LENGTH)
    val mismatch = stringResource(R.string.abp_admin_pin_mismatch)
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.abp_admin_pin_setup_title)) },
        text = {
            Column {
                Text(stringResource(R.string.abp_admin_pin_setup_text))
                Spacer(Modifier.height(8.dp))
                PinField(pin, { pin = it; error = null }, stringResource(R.string.abp_admin_pin_label))
                PinField(again, { again = it; error = null }, stringResource(R.string.abp_admin_pin_again_label))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    !AdminPin.isValidFormat(pin) -> error = badFormat
                    pin != again -> error = mismatch
                    AdminPin.set(AdminPin.store(context), pin) -> { pin = ""; again = ""; onDone() }
                    else -> error = badFormat
                }
            }) { Text(stringResource(R.string.abp_admin_pin_save)) }
        },
    )
}

/** Asks for the administrator PIN before an update is installed. [onVerified] runs only on the right PIN. */
@Composable
fun AdminPinGateDialog(version: String?, onVerified: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val wrong = stringResource(R.string.abp_admin_pin_wrong)
    val lockedFmt = stringResource(R.string.abp_admin_pin_locked)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.abp_admin_pin_gate_title)) },
        text = {
            Column {
                Text(stringResource(R.string.abp_admin_pin_gate_text, version?.removePrefix("v") ?: ""))
                Spacer(Modifier.height(8.dp))
                PinField(pin, { pin = it; error = null }, stringResource(R.string.abp_admin_pin_label))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (val r = AdminPin.verify(AdminPin.store(context), pin)) {
                    AdminPin.Check.Ok -> { pin = ""; onVerified() }
                    AdminPin.Check.Wrong -> { pin = ""; error = wrong }
                    is AdminPin.Check.Locked -> { pin = ""; error = String.format(lockedFmt, (r.secondsLeft + 59) / 60) }
                }
            }) { Text(stringResource(R.string.abp_admin_pin_install)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.abp_admin_pin_cancel)) } },
    )
}

@Composable
private fun PinField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it in '0'..'9' }.take(AdminPin.MAX_LENGTH)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    )
}
