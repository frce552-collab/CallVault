/*
 * ABP Call — Ploshadki.kz fork of CallVault (https://github.com/madkongo/CallVault).
 * GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import com.baba.callvault.ploshadki.AbpUpdateKey.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class AbpUpdateKeyTest {
    private val expires = LocalDate.of(2027, 5, 13)

    @Test
    fun `blank or bad date is unknown, never expired`() {
        assertNull(AbpUpdateKey.expiry(""))
        assertNull(AbpUpdateKey.expiry("  "))
        assertNull(AbpUpdateKey.expiry("13.05.2027"))
        assertEquals(expires, AbpUpdateKey.expiry("2027-05-13"))
        assertEquals(State.UNKNOWN, AbpUpdateKey.state(null, expires.plusYears(5)))
    }

    @Test
    fun `remind 14 days before, works through the expiry day, expired the day after`() {
        assertEquals(14L, PloshadkiFork.UPDATE_KEY_REMIND_DAYS)
        assertEquals(State.OK, AbpUpdateKey.state(expires, expires.minusDays(15)))
        assertEquals(State.REMIND, AbpUpdateKey.state(expires, expires.minusDays(14)))
        assertEquals(State.REMIND, AbpUpdateKey.state(expires, expires))
        assertEquals(State.EXPIRED, AbpUpdateKey.state(expires, expires.plusDays(1)))
        assertEquals(-1L, AbpUpdateKey.daysLeft(expires, expires.plusDays(1)))
    }

    @Test
    fun `GitHub 401 means expired whatever the date`() {
        assertEquals(State.EXPIRED, AbpUpdateKey.state(null, expires, authFailed = true))
        assertEquals(State.EXPIRED, AbpUpdateKey.state(expires, expires.minusDays(100), authFailed = true))
    }

    @Test
    fun `phone speaks only when expired, at most once a week`() {
        val today = expires.plusDays(3)
        assertFalse(AbpUpdateKey.shouldNotifyPhone(State.REMIND, null, today))
        assertFalse(AbpUpdateKey.shouldNotifyPhone(State.OK, null, today))
        assertFalse(AbpUpdateKey.shouldNotifyPhone(State.UNKNOWN, null, today))
        assertTrue(AbpUpdateKey.shouldNotifyPhone(State.EXPIRED, null, today))
        assertFalse(AbpUpdateKey.shouldNotifyPhone(State.EXPIRED, today.minusDays(6), today))
        assertTrue(AbpUpdateKey.shouldNotifyPhone(State.EXPIRED, today.minusDays(7), today))
    }

    @Test
    fun `401 flag follows the last query`() {
        AbpUpdateKey.onReleaseQuery(401)
        assertTrue(AbpUpdateKey.lastAuthFailed)
        AbpUpdateKey.onReleaseQuery(500) // network trouble does not clear it
        assertTrue(AbpUpdateKey.lastAuthFailed)
        AbpUpdateKey.onReleaseQuery(200)
        assertFalse(AbpUpdateKey.lastAuthFailed)
    }

    @Test
    fun `workflow can read the expiry line`() {
        // ploshadki-key-reminder.yml greps exactly this shape: const val UPDATE_KEY_EXPIRES = "..."
        val src = sequenceOf("src/main/java", "app/src/main/java")
            .map { File(it, "com/baba/callvault/ploshadki/PloshadkiFork.kt") }.first { it.exists() }.readText()
        assertTrue(Regex("""const val UPDATE_KEY_EXPIRES = "(\d{4}-\d{2}-\d{2})?"""").containsMatchIn(src))
    }
}
