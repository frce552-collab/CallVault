/*
 * ABP Call — Ploshadki.kz fork of CallVault. GPL-3.0-or-later with the Section 7 terms in LICENSE.
 * Upstream tests rewritten for the fork's fail-closed allow-list (only WhatsApp Business).
 */
package com.baba.callvault.data.voip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoipAppPolicyTest {

    private val w4b = "com.whatsapp.w4b"

    @Test
    fun `records WhatsApp Business when it is not excluded`() {
        assertTrue(VoipAppPolicy.shouldRecord(w4b, emptySet()))
        assertTrue(VoipAppPolicy.shouldRecord(w4b, setOf("org.telegram.messenger")))
    }

    @Test
    fun `never records personal WhatsApp`() {
        assertFalse(VoipAppPolicy.shouldRecord("com.whatsapp", emptySet()))
    }

    @Test
    fun `never records any other app`() {
        assertFalse(VoipAppPolicy.shouldRecord("org.telegram.messenger", emptySet()))
        assertFalse(VoipAppPolicy.shouldRecord("org.thoughtcrime.securesms", emptySet()))
        assertFalse(VoipAppPolicy.shouldRecord("com.whatsapp.w4b.fake", emptySet()))
    }

    @Test
    fun `does not record a call whose app could not be identified`() {
        assertFalse(VoipAppPolicy.shouldRecord(null, emptySet()))
        assertFalse(VoipAppPolicy.shouldRecord("", emptySet()))
        assertFalse(VoipAppPolicy.shouldRecord("   ", emptySet()))
    }

    @Test
    fun `WhatsApp Business switched off by the user is not recorded`() {
        assertFalse(VoipAppPolicy.shouldRecord(w4b, setOf(w4b)))
    }

    @Test
    fun `turning an app off adds it to the exclusions`() {
        assertEquals(setOf(w4b), VoipAppPolicy.withRecording(emptySet(), w4b, record = false))
    }

    @Test
    fun `turning an app back on removes it`() {
        assertEquals(emptySet<String>(), VoipAppPolicy.withRecording(setOf(w4b), w4b, record = true))
    }

    @Test
    fun `flipping one app leaves the others alone`() {
        assertEquals(setOf("a", "c"), VoipAppPolicy.withRecording(setOf("a", "b", "c"), "b", record = true))
    }
}
