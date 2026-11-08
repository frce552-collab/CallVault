/*
 * ABP Call — Ploshadki.kz fork of CallVault. GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AbpHttpsOnlyTest {

    private fun link(server: String) = """{"v":1,"server":"$server","tenantId":"t","enrollmentId":"e","enrollmentCode":"c",""" +
        """"enrollmentRef":"r","employeeRef":"m","sourceAccountId":"s","secret":"${AbpCrypto.b64url(ByteArray(32) { 7 })}"}"""

    @Test
    fun `an https server is accepted`() {
        assertEquals("https://air.example.kz", AbpStore.parseLink(link("https://air.example.kz/")).server)
    }

    @Test
    fun `a plain http server is refused in every build`() {
        assertThrows(IllegalArgumentException::class.java) { AbpStore.parseLink(link("http://air.example.kz")) }
        assertThrows(IllegalArgumentException::class.java) { AbpStore.parseLink(link("http://10.0.2.2:3000")) }
    }

    @Test
    fun `only a real https address counts`() {
        assertTrue(AbpStore.isHttps("HTTPS://air.example.kz"))
        assertFalse(AbpStore.isHttps("https://"))
        assertFalse(AbpStore.isHttps("ftp://air.example.kz"))
    }
}
