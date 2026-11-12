/*
 * ABP Call — Ploshadki.kz fork of CallVault (https://github.com/madkongo/CallVault).
 * GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbpRecorderWatchTest {

    private val min = 60_000L

    @Test
    fun `owner request 12-11 - self-healing defaults`() {
        assertTrue("off-Wi-Fi loopback must be on by default", PloshadkiFork.OFFLINE_RECORDING_BY_DEFAULT)
        assertTrue(PloshadkiFork.LIVENESS_ALARM_MINUTES in 1L..15L)
        assertTrue(PloshadkiFork.RECORDER_DOWN_ALERT_MINUTES in 5L..30L)
    }

    @Test
    fun `no alert while the recorder is up`() {
        assertFalse(AbpRecorderWatch.shouldAlert(null, 10_000 * min))
    }

    @Test
    fun `no alert for a short outage that recovery may still fix`() {
        assertFalse(AbpRecorderWatch.shouldAlert(0L, 9 * min))
    }

    @Test
    fun `alert after the configured outage`() {
        assertTrue(AbpRecorderWatch.shouldAlert(0L, PloshadkiFork.RECORDER_DOWN_ALERT_MINUTES * min))
        assertTrue(AbpRecorderWatch.shouldAlert(5 * min, 5 * min + 60 * min))
    }

    @Test
    fun `checks come sooner while the recorder is down`() {
        assertEquals(PloshadkiFork.LIVENESS_ALARM_MINUTES * min, AbpRecorderWatch.nextCheckDelayMs(recorderUp = true))
        assertEquals(min, AbpRecorderWatch.nextCheckDelayMs(recorderUp = false))
    }
}
