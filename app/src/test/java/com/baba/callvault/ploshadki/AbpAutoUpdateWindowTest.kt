/*
 * ABP Call — Ploshadki.kz fork of CallVault (https://github.com/madkongo/CallVault).
 * GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

class AbpAutoUpdateWindowTest {
    private val almaty = ZoneId.of("Asia/Almaty")
    private fun at(h: Int, m: Int = 0) = ZonedDateTime.of(2026, 11, 12, h, m, 0, 0, almaty)

    @Test
    fun `window is 20-00 to 08-00 Almaty`() {
        assertEquals(20, PloshadkiFork.AUTO_INSTALL_FROM_HOUR)
        assertEquals(8, PloshadkiFork.AUTO_INSTALL_UNTIL_HOUR)
        assertEquals("Asia/Almaty", PloshadkiFork.AUTO_INSTALL_ZONE)
        assertTrue(AbpAutoUpdateWindow.isOpen(at(20)))
        assertTrue(AbpAutoUpdateWindow.isOpen(at(23, 59)))
        assertTrue(AbpAutoUpdateWindow.isOpen(at(0)))
        assertTrue(AbpAutoUpdateWindow.isOpen(at(7, 59)))
        assertFalse(AbpAutoUpdateWindow.isOpen(at(8)))
        assertFalse(AbpAutoUpdateWindow.isOpen(at(13)))
        assertFalse(AbpAutoUpdateWindow.isOpen(at(19, 59)))
    }

    @Test
    fun `delay until the window opens`() {
        assertEquals(Duration.ZERO, AbpAutoUpdateWindow.delayUntilOpen(at(22)))
        assertEquals(Duration.ofHours(7), AbpAutoUpdateWindow.delayUntilOpen(at(13)))
        assertEquals(Duration.ofHours(12), AbpAutoUpdateWindow.delayUntilOpen(at(8)))
        assertEquals(Duration.ofMinutes(1), AbpAutoUpdateWindow.delayUntilOpen(at(19, 59)))
    }
}
