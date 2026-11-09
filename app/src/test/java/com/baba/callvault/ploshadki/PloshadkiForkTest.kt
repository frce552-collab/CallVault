/*
 * ABP Call — Ploshadki.kz fork of CallVault. GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import com.baba.callvault.system.updates.GitHubReleases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PloshadkiForkTest {

    @Test
    fun `upstream self-update and donation links are off`() {
        assertFalse(PloshadkiFork.UPSTREAM_UPDATES_ENABLED)
        assertFalse(PloshadkiFork.UPSTREAM_SUPPORT_LINKS_ENABLED)
    }

    @Test
    fun `only WhatsApp Business is recordable`() {
        assertEquals(setOf("com.whatsapp.w4b"), PloshadkiFork.RECORDABLE_VOIP_PACKAGES)
    }

    @Test
    fun `self-update comes from our fork, never from madkongo`() {
        assertTrue(PloshadkiFork.UPDATES_ENABLED)
        assertEquals("frce552-collab/CallVault", PloshadkiFork.UPDATE_REPO)
        assertEquals("ABP-Call.apk", PloshadkiFork.UPDATE_APK_ASSET)
        assertEquals("https://github.com/frce552-collab/CallVault/releases/latest", GitHubReleases.RELEASES_PAGE_URL)
    }
}
