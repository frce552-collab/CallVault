/*
 * ABP Call — Ploshadki.kz fork of CallVault. GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import com.baba.callvault.BuildConfig
import com.baba.callvault.server.RecorderBinderProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class HostPackageTest {

    @Test
    fun `in ABP Call the live authority equals the manifest default`() {
        // Nothing changes for the standalone app: same package, same provider authority as before.
        assertEquals(BuildConfig.APPLICATION_ID, HostPackage.name)
        assertEquals(RecorderBinderProvider.AUTHORITY, HostPackage.recorderAuthority())
    }

    @Test
    fun `an embedding host gets its own authority`() {
        assertEquals("kz.ploshadki.crm.recorder", HostPackage.recorderAuthority("kz.ploshadki.crm"))
    }
}
