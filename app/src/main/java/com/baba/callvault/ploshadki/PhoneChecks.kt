/*
 * ABP Call — Ploshadki.kz fork of CallVault (https://github.com/madkongo/CallVault),
 * itself a fork of ShizuCallRecorder. GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri

/**
 * The phone settings the setup wizard checks for ABP Call ([PloshadkiFork.WIZARD_PHONE_CHECKS]).
 *
 * Where Android lets the app read a setting, the wizard reads it ([PhoneCheckState.read]). Where it does not
 * (Xiaomi autostart, the system-update switch, and USB debugging on a build that redacts it), the wizard opens
 * the right settings screen and the person marks the item done; that mark is kept in [PhoneCheckMarks].
 *
 * Paths in the menus: ploshadki-kz-platform, docs/callvault/pilot/PHONE-SETTINGS-XIAOMI-SAMSUNG.md.
 */
enum class PhoneCheck {
    /** Developer options ▸ USB debugging. Read (tri-state; unknown on a redacting build). */
    USB_DEBUGGING,

    /** Xiaomi/HyperOS: Developer options ▸ USB debugging (Security settings). Read when the phone tells. */
    XIAOMI_USB_SECURITY,

    /** Xiaomi/HyperOS: Security ▸ Autostart for ABP Call. Not readable: the person confirms. */
    XIAOMI_AUTOSTART,

    /** ABP Call ▸ "Keep Wireless debugging on for recording". Read from the app's own setting; one tap sets it. */
    KEEP_WIRELESS_DEBUGGING_ON,

    /** Battery: "No restrictions" (battery-optimisation exemption). Read. */
    BATTERY_UNRESTRICTED,

    /** Automatic Android/HyperOS system updates off. Not readable: the person confirms. */
    SYSTEM_AUTO_UPDATE_OFF,
}

/** What the wizard shows beside an item. */
enum class PhoneCheckStatus { DONE, NOT_DONE }

object PhoneCheckState {
    /**
     * The items for this phone, in the order a person walks through Settings: Developer options first, then
     * Xiaomi's security app, then the app's own switch, battery, and updates last.
     *
     * @param isXiaomi        Xiaomi/Redmi/POCO (MIUI or HyperOS).
     * @param usesEmbeddedAdb ABP Call's own ADB connection (not Shizuku): the Wireless-debugging switch only acts there.
     */
    fun checksFor(isXiaomi: Boolean, usesEmbeddedAdb: Boolean): List<PhoneCheck> = buildList {
        add(PhoneCheck.USB_DEBUGGING)
        if (isXiaomi) add(PhoneCheck.XIAOMI_USB_SECURITY)
        if (isXiaomi) add(PhoneCheck.XIAOMI_AUTOSTART)
        if (usesEmbeddedAdb) add(PhoneCheck.KEEP_WIRELESS_DEBUGGING_ON)
        add(PhoneCheck.BATTERY_UNRESTRICTED)
        add(PhoneCheck.SYSTEM_AUTO_UPDATE_OFF)
    }

    /** Items Android never lets the app read: done only by the person's mark. */
    fun isManualOnly(check: PhoneCheck): Boolean =
        check == PhoneCheck.XIAOMI_AUTOSTART || check == PhoneCheck.SYSTEM_AUTO_UPDATE_OFF

    /**
     * The status shown.
     *
     * @param read     What the phone says: true on, false off, null when it will not say.
     * @param marked   The person tapped "Done" for this item.
     *
     * A reading wins over the mark: a mark cannot hide a switch the phone says is off. The mark only answers
     * where the phone stays silent.
     */
    fun status(read: Boolean?, marked: Boolean): PhoneCheckStatus = when (read) {
        true -> PhoneCheckStatus.DONE
        false -> PhoneCheckStatus.NOT_DONE
        null -> if (marked) PhoneCheckStatus.DONE else PhoneCheckStatus.NOT_DONE
    }

    /**
     * Xiaomi's "USB debugging (Security settings)". `persist.security.adbinput` = `1` means on. A held
     * WRITE_SECURE_SETTINGS is proof too: Xiaomi refuses that grant over ADB while the switch is off. Anything
     * else is not a "no" (the property is not readable on every build), so it stays unknown.
     */
    fun xiaomiSecurityRead(adbInputProperty: String?, holdsWriteSecureSettings: Boolean): Boolean? =
        if (adbInputProperty?.trim() == "1" || holdsWriteSecureSettings) true else null

    /**
     * Settings screens to try for [check], best first; the caller launches the first that opens. Each list ends
     * in a screen every Android has, so a button never does nothing. Empty for [PhoneCheck.KEEP_WIRELESS_DEBUGGING_ON],
     * which is the app's own switch and is set in place.
     */
    fun screensFor(check: PhoneCheck, packageName: String, isXiaomi: Boolean): List<Intent> {
        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData("package:$packageName".toUri())
        val developer = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        val settings = Intent(Settings.ACTION_SETTINGS)
        return when (check) {
            PhoneCheck.USB_DEBUGGING, PhoneCheck.XIAOMI_USB_SECURITY -> listOf(developer, Intent(Settings.ACTION_DEVICE_INFO_SETTINGS), settings)
            PhoneCheck.XIAOMI_AUTOSTART -> listOf(
                Intent().setComponent(ComponentName(MIUI_SECURITY_CENTER, "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                Intent("miui.intent.action.APP_PERM_EDITOR")
                    .setClassName(MIUI_SECURITY_CENTER, "com.miui.permcenter.permissions.PermissionsEditorActivity")
                    .putExtra("extra_pkgname", packageName),
                Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", packageName),
                appDetails,
                settings,
            )
            PhoneCheck.KEEP_WIRELESS_DEBUGGING_ON -> emptyList()
            PhoneCheck.BATTERY_UNRESTRICTED -> buildList {
                if (isXiaomi) {
                    add(
                        Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
                            .putExtra("package_name", packageName)
                            .putExtra("package_label", "ABP Call")
                    )
                }
                add(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData("package:$packageName".toUri()))
                add(appDetails)
                add(settings)
            }
            PhoneCheck.SYSTEM_AUTO_UPDATE_OFF -> buildList {
                if (isXiaomi) add(Intent().setComponent(ComponentName("com.android.updater", "com.android.updater.MainActivity")))
                // AOSP / Samsung-less phones: Developer options ▸ Automatic system updates.
                add(developer)
                add(settings)
            }
        }
    }

    private const val MIUI_SECURITY_CENTER = "com.miui.securitycenter"
}

/** The person's "Done" marks for items the phone cannot report. Device-only, never exported. */
class PhoneCheckMarks(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("abp_phone_checks", Context.MODE_PRIVATE)

    fun isMarked(check: PhoneCheck): Boolean = prefs.getBoolean(check.name, false)

    fun setMarked(check: PhoneCheck, marked: Boolean) {
        prefs.edit().putBoolean(check.name, marked).apply()
    }
}
