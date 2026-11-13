/*
 * ABP Call — Ploshadki.kz fork of CallVault (https://github.com/madkongo/CallVault).
 * GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import com.baba.callvault.ploshadki.AbpPostUpdateCheck.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbpPostUpdateCheckTest {

    @Test
    fun `owner request 13-11 - update button and post-update check are on`() {
        assertTrue(PloshadkiFork.UPDATE_NOW_BUTTON)
        assertTrue(PloshadkiFork.POST_UPDATE_CHECK)
        assertTrue(PloshadkiFork.POST_UPDATE_CHECK_MINUTES in 1L..10L)
        // The night install stays as it was.
        assertTrue(PloshadkiFork.AUTO_INSTALL_UPDATES)
        assertEquals(20, PloshadkiFork.AUTO_INSTALL_FROM_HOUR)
        assertEquals(8, PloshadkiFork.AUTO_INSTALL_UNTIL_HOUR)
        // Owner 12.11: no PIN — otherwise the notification button would be hidden.
        assertFalse(PloshadkiFork.ADMIN_PIN_FOR_UPDATES)
    }

    @Test
    fun `recorder up after the update - no alert`() {
        val v = AbpPostUpdateCheck.verdict(stoppedByUser = false, aliveFirst = true, aliveAfterRetry = null)
        assertEquals(Verdict.UP, v)
        assertFalse(AbpPostUpdateCheck.mustAlert(v))
    }

    @Test
    fun `recorder down but the retry brings it back - no alert`() {
        val v = AbpPostUpdateCheck.verdict(stoppedByUser = false, aliveFirst = false, aliveAfterRetry = true)
        assertEquals(Verdict.RECOVERED, v)
        assertFalse(AbpPostUpdateCheck.mustAlert(v))
    }

    @Test
    fun `recorder still down after the retry - loud alert`() {
        val v = AbpPostUpdateCheck.verdict(stoppedByUser = false, aliveFirst = false, aliveAfterRetry = false)
        assertEquals(Verdict.DOWN, v)
        assertTrue(AbpPostUpdateCheck.mustAlert(v))
    }

    @Test
    fun `no retry result counts as down`() {
        assertTrue(AbpPostUpdateCheck.mustAlert(AbpPostUpdateCheck.verdict(false, false, null)))
    }

    @Test
    fun `recording switched off by the user is not an outage`() {
        val v = AbpPostUpdateCheck.verdict(stoppedByUser = true, aliveFirst = false, aliveAfterRetry = null)
        assertEquals(Verdict.SKIPPED_BY_USER, v)
        assertFalse(AbpPostUpdateCheck.mustAlert(v))
    }
}
