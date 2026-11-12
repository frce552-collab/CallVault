/*
 * ABP Call — Ploshadki.kz fork of CallVault. GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import android.content.Intent
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneChecksTest {

    @Test
    fun `wizard phone checks are on in the fork`() {
        assertTrue(PloshadkiFork.WIZARD_PHONE_CHECKS)
    }

    @Test
    fun `Xiaomi gets the security switch and autostart, other phones do not`() {
        val xiaomi = PhoneCheckState.checksFor(isXiaomi = true, usesEmbeddedAdb = true)
        val other = PhoneCheckState.checksFor(isXiaomi = false, usesEmbeddedAdb = true)
        assertTrue(PhoneCheck.XIAOMI_USB_SECURITY in xiaomi)
        assertTrue(PhoneCheck.XIAOMI_AUTOSTART in xiaomi)
        assertFalse(PhoneCheck.XIAOMI_USB_SECURITY in other)
        assertFalse(PhoneCheck.XIAOMI_AUTOSTART in other)
        assertEquals(PhoneCheck.entries.toList(), xiaomi)
    }

    @Test
    fun `every phone gets USB debugging, battery and system updates`() {
        for (isXiaomi in listOf(false, true)) for (adb in listOf(false, true)) {
            val checks = PhoneCheckState.checksFor(isXiaomi, adb)
            assertTrue(PhoneCheck.USB_DEBUGGING in checks)
            assertTrue(PhoneCheck.BATTERY_UNRESTRICTED in checks)
            assertTrue(PhoneCheck.SYSTEM_AUTO_UPDATE_OFF in checks)
            assertEquals("no item twice", checks.size, checks.toSet().size)
        }
    }

    @Test
    fun `keep Wireless debugging on is asked only where ABP Call owns the ADB connection`() {
        assertTrue(PhoneCheck.KEEP_WIRELESS_DEBUGGING_ON in PhoneCheckState.checksFor(false, usesEmbeddedAdb = true))
        assertFalse(PhoneCheck.KEEP_WIRELESS_DEBUGGING_ON in PhoneCheckState.checksFor(false, usesEmbeddedAdb = false))
    }

    @Test
    fun `a reading wins over the person's mark, the mark answers only silence`() {
        assertEquals(PhoneCheckStatus.DONE, PhoneCheckState.status(read = true, marked = false))
        assertEquals(PhoneCheckStatus.NOT_DONE, PhoneCheckState.status(read = false, marked = true))
        assertEquals(PhoneCheckStatus.DONE, PhoneCheckState.status(read = null, marked = true))
        assertEquals(PhoneCheckStatus.NOT_DONE, PhoneCheckState.status(read = null, marked = false))
    }

    @Test
    fun `Xiaomi security switch is on when the property says 1 or the grant is held, else unknown`() {
        assertEquals(true, PhoneCheckState.xiaomiSecurityRead("1", holdsWriteSecureSettings = false))
        assertEquals(true, PhoneCheckState.xiaomiSecurityRead("", holdsWriteSecureSettings = true))
        assertNull(PhoneCheckState.xiaomiSecurityRead("0", holdsWriteSecureSettings = false))
        assertNull(PhoneCheckState.xiaomiSecurityRead(null, holdsWriteSecureSettings = false))
    }

    @Test
    fun `autostart and system updates are confirmed by the person`() {
        assertTrue(PhoneCheckState.isManualOnly(PhoneCheck.XIAOMI_AUTOSTART))
        assertTrue(PhoneCheckState.isManualOnly(PhoneCheck.SYSTEM_AUTO_UPDATE_OFF))
        assertFalse(PhoneCheckState.isManualOnly(PhoneCheck.BATTERY_UNRESTRICTED))
    }

    @Test
    fun `autostart opens MIUI security center first and falls back to app info and settings`() {
        val screens = PhoneCheckState.screensFor(PhoneCheck.XIAOMI_AUTOSTART, "kz.ploshadki.abpcall", isXiaomi = true)
        assertEquals("com.miui.securitycenter", screens.first().component?.packageName)
        assertEquals("com.miui.permcenter.autostart.AutoStartManagementActivity", screens.first().component?.className)
        assertTrue(screens.any { it.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS })
        assertEquals(Settings.ACTION_SETTINGS, screens.last().action)
    }

    @Test
    fun `every button has a screen every Android has at the end`() {
        for (check in PhoneCheck.entries - PhoneCheck.KEEP_WIRELESS_DEBUGGING_ON) for (isXiaomi in listOf(false, true)) {
            val screens = PhoneCheckState.screensFor(check, "p", isXiaomi)
            assertEquals("$check xiaomi=$isXiaomi", Settings.ACTION_SETTINGS, screens.last().action)
        }
        assertTrue(PhoneCheckState.screensFor(PhoneCheck.KEEP_WIRELESS_DEBUGGING_ON, "p", true).isEmpty())
    }

    @Test
    fun `battery asks Android for the exemption, on Xiaomi after the No restrictions page`() {
        val other = PhoneCheckState.screensFor(PhoneCheck.BATTERY_UNRESTRICTED, "p", isXiaomi = false)
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, other.first().action)
        assertEquals("package:p", other.first().dataString)
        val xiaomi = PhoneCheckState.screensFor(PhoneCheck.BATTERY_UNRESTRICTED, "p", isXiaomi = true)
        assertEquals("com.miui.powerkeeper", xiaomi.first().component?.packageName)
        assertTrue(xiaomi.any { it.action == Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS })
    }

    @Test
    fun `system updates open the Xiaomi updater, else Developer options`() {
        val xiaomi = PhoneCheckState.screensFor(PhoneCheck.SYSTEM_AUTO_UPDATE_OFF, "p", isXiaomi = true)
        assertEquals("com.android.updater", xiaomi.first().component?.packageName)
        val other = PhoneCheckState.screensFor(PhoneCheck.SYSTEM_AUTO_UPDATE_OFF, "p", isXiaomi = false)
        assertEquals(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, other.first().action)
    }

    @Test
    fun `marks are kept and can be taken back`() {
        val marks = PhoneCheckMarks(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        assertFalse(marks.isMarked(PhoneCheck.XIAOMI_AUTOSTART))
        marks.setMarked(PhoneCheck.XIAOMI_AUTOSTART, true)
        assertTrue(marks.isMarked(PhoneCheck.XIAOMI_AUTOSTART))
        marks.setMarked(PhoneCheck.XIAOMI_AUTOSTART, false)
        assertFalse(marks.isMarked(PhoneCheck.XIAOMI_AUTOSTART))
    }
}
