/*
 * ABP Call — Ploshadki.kz fork of CallVault. GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureScreensTest {

    @Test
    fun `recorder screens are covered, the host's own screens are not`() {
        assertTrue(SecureScreens.isRecorderActivity("com.baba.callvault.MainActivity"))
        assertTrue(SecureScreens.isRecorderActivity("com.baba.callvault.ploshadki.AbpConnectActivity"))
        assertTrue(SecureScreens.isRecorderActivity("com.baba.callvault.ShareImportActivity"))
        assertFalse(SecureScreens.isRecorderActivity("kz.ploshadki.crm.MainActivity"))
        assertFalse(SecureScreens.isRecorderActivity("com.baba.callvaultx.Evil"))
    }
}
