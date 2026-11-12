/*
 * ABP Call — Ploshadki.kz fork of CallVault (https://github.com/madkongo/CallVault).
 * GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminPinTest {

    private class MemStore : AdminPin.Store {
        val m = HashMap<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
    }

    @Test
    fun `owner decision 12-11 - updates need the administrator PIN`() {
        assertTrue(PloshadkiFork.ADMIN_PIN_FOR_UPDATES)
        assertFalse("upstream CallVault releases must never be used", PloshadkiFork.UPSTREAM_UPDATES_ENABLED)
        assertEquals("frce552-collab/abp-call-releases", PloshadkiFork.UPDATE_REPO)
    }

    @Test
    fun `format - 4 to 8 digits only`() {
        assertFalse(AdminPin.isValidFormat("123"))
        assertTrue(AdminPin.isValidFormat("1234"))
        assertTrue(AdminPin.isValidFormat("12345678"))
        assertFalse(AdminPin.isValidFormat("123456789"))
        assertFalse(AdminPin.isValidFormat("12a4"))
    }

    @Test
    fun `the PIN itself is never stored`() {
        val s = MemStore()
        assertFalse(AdminPin.isSet(s))
        assertTrue(AdminPin.set(s, "4821"))
        assertTrue(AdminPin.isSet(s))
        assertFalse(s.m.values.any { it.contains("4821") })
        val first = s.m["hash"]
        AdminPin.set(s, "4821")
        assertNotEquals("a new salt each time", first, s.m["hash"])
    }

    @Test
    fun `right PIN passes, wrong PIN does not`() {
        val s = MemStore(); AdminPin.set(s, "4821")
        assertEquals(AdminPin.Check.Ok, AdminPin.verify(s, "4821", 0L))
        assertEquals(AdminPin.Check.Wrong, AdminPin.verify(s, "4822", 0L))
        assertEquals(AdminPin.Check.Wrong, AdminPin.verify(MemStore(), "4821", 0L))
    }

    @Test
    fun `five wrong PINs lock for five minutes, even the right one`() {
        val s = MemStore(); AdminPin.set(s, "4821")
        repeat(4) { assertEquals(AdminPin.Check.Wrong, AdminPin.verify(s, "0000", 1_000L)) }
        assertTrue(AdminPin.verify(s, "0000", 1_000L) is AdminPin.Check.Locked)
        assertTrue(AdminPin.verify(s, "4821", 2_000L) is AdminPin.Check.Locked)
        assertEquals(AdminPin.Check.Ok, AdminPin.verify(s, "4821", 1_000L + 5 * 60_000L))
    }
}
