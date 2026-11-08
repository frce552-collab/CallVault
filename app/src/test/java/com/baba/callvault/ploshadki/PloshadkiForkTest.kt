/*
 * ABP Call — Ploshadki.kz fork of CallVault. GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import com.baba.callvault.system.updates.GitHubReleases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `release lookup never reaches GitHub`() {
        // Returns before any network call: an unreachable override URL would otherwise throw/timeout.
        assertNull(GitHubReleases.fetchLatestRelease("http://127.0.0.1:9/never"))
    }
}
