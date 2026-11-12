/*
 * ABP Call — Ploshadki.kz fork of CallVault (https://github.com/madkongo/CallVault).
 * GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import com.baba.callvault.integrations.adb.UsbDebuggingState
import com.baba.callvault.integrations.adb.WifiState
import com.baba.callvault.services.recording.ReadinessNotice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AbpRecoveryPolicyTest {

    @After
    fun tearDown() = AbpWdDropGuard.reset()

    @Test
    fun `owner request 12-11 - reboot recovery switches are on`() {
        assertTrue(PloshadkiFork.REBOOT_FORGETS_WD_OFF_BY_USER)
        assertTrue(PloshadkiFork.KEEP_WD_ON_IF_DROP_KILLED_RECORDER)
        assertTrue(PloshadkiFork.RETRY_RELAUNCH_REFUSED_WHILE_BUSY)
        assertTrue(PloshadkiFork.WD_DROP_DEATH_WINDOW_MS in 1_000L..30_000L)
    }

    // ---- B: a reboot forgets the previous boot's "switched off by the user"

    @Test
    fun `boot forgets a stale WD-off-by-user mark`() {
        assertTrue(AbpBootRecovery.shouldForgetWdOffByUser(markSet = true))
        assertFalse(AbpBootRecovery.shouldForgetWdOffByUser(markSet = false))
        assertFalse(AbpBootRecovery.shouldForgetWdOffByUser(markSet = true, enabled = false))
    }

    /** The deadlock this fixes: with the mark, USB debugging off, the keep-alive stands down on WD_OFF_BY_USER. */
    @Test
    fun `with the mark forgotten the keep-alive no longer stands down after a reboot`() {
        fun notice(offByUser: Boolean) = ReadinessNotice.of(
            ready = false, recoveryStuck = false, usbDebugging = UsbDebuggingState.OFF,
            wirelessDebuggingOn = false, wifi = WifiState.CONNECTED, loopbackArmed = false, hasGrant = true,
            wirelessDebuggingOffByUser = offByUser, enforced = false, offlineRecordingOn = true,
        )
        assertEquals(ReadinessNotice.WD_OFF_BY_USER, notice(offByUser = true))
        val afterBoot = notice(offByUser = !AbpBootRecovery.shouldForgetWdOffByUser(markSet = true))
        assertNotEquals(ReadinessNotice.WD_OFF_BY_USER, afterBoot)
        assertEquals(ReadinessNotice.STARTING, afterBoot)
    }

    // ---- A: a switch-off that killed the recorder is not repeated

    @Test
    fun `switching WD off stays allowed until it kills the recorder`() {
        assertTrue(AbpWdDropGuard.mayDropWirelessDebugging())
        assertFalse("a death with no switch-off of ours latches nothing", AbpWdDropGuard.onRecorderDied(weJustTurnedWdOff = false))
        assertTrue(AbpWdDropGuard.mayDropWirelessDebugging())
    }

    @Test
    fun `a death right after our switch-off keeps WD on for the boot, journalled once`() {
        assertTrue(AbpWdDropGuard.onRecorderDied(weJustTurnedWdOff = true))
        assertFalse(AbpWdDropGuard.mayDropWirelessDebugging())
        assertFalse("written to the journal only once", AbpWdDropGuard.onRecorderDied(weJustTurnedWdOff = true))
        assertFalse(AbpWdDropGuard.mayDropWirelessDebugging())
    }

    @Test
    fun `guard switched off by the fork flag latches nothing`() {
        assertFalse(AbpWdDropGuard.onRecorderDied(weJustTurnedWdOff = true, enabled = false))
        assertTrue(AbpWdDropGuard.mayDropWirelessDebugging())
    }

    // ---- B: a Wi-Fi join during a running relaunch is not lost

    @Test
    fun `forced relaunch refused while busy runs once after a failed attempt`() {
        val r = AbpRewarmRetry(enabled = true)
        r.onRefusedBusy(force = true)
        assertTrue(r.takeAfterAttempt(succeeded = false))
        assertFalse("consumed: no endless loop", r.takeAfterAttempt(succeeded = false))
    }

    @Test
    fun `no retry when the running attempt succeeded`() {
        val r = AbpRewarmRetry(enabled = true)
        r.onRefusedBusy(force = true)
        assertFalse(r.takeAfterAttempt(succeeded = true))
        assertFalse(r.takeAfterAttempt(succeeded = false))
    }

    @Test
    fun `unforced refusals (throttle) and disabled flag queue nothing`() {
        val r = AbpRewarmRetry(enabled = true)
        r.onRefusedBusy(force = false)
        assertFalse(r.takeAfterAttempt(succeeded = false))
        val off = AbpRewarmRetry(enabled = false)
        off.onRefusedBusy(force = true)
        assertFalse(off.takeAfterAttempt(succeeded = false))
    }
}

/** The policies above only help if the services call them; checked on the sources (no Android runtime here). */
class AbpRecoveryWiringTest {

    private fun src(rel: String): String {
        val f = listOf("src/main/java/com/baba/callvault/$rel", "app/src/main/java/com/baba/callvault/$rel")
            .map { java.io.File(it) }.first { it.exists() }
        return f.readText()
    }

    @Test
    fun `boot forgets the mark and journals it`() {
        val s = src("services/boot/BootReceiver.kt")
        assertTrue(s.contains("AbpBootRecovery.shouldForgetWdOffByUser("))
        assertTrue(s.contains("setWirelessDebuggingTurnedOffByUser(false)"))
        assertTrue(s.contains("TransportTimeline.event("))
        // Before the services start, so their first look already sees the cleared mark.
        assertTrue(s.indexOf("shouldForgetWdOffByUser") < s.indexOf("AdbConnectionService.start(context)"))
    }

    @Test
    fun `release asks the drop guard before switching WD off`() {
        val s = src("integrations/adb/AdbShell.kt")
        val release = s.substring(s.indexOf("fun releaseWirelessDebugging("))
        assertTrue(release.indexOf("AbpWdDropGuard.mayDropWirelessDebugging()") in 0 until release.indexOf("disableWirelessDebugging(context)"))
    }

    @Test
    fun `keep-alive notes death causes and retries a relaunch refused while busy`() {
        val s = src("services/recording/DaemonKeepAliveService.kt")
        assertTrue(s.contains("AbpWdDropGuard.onRecorderDied("))
        assertTrue(s.contains("noteDeathCause()"))
        assertTrue(s.contains("rewarmRetry.onRefusedBusy("))
        assertTrue(s.contains("rewarmRetry.takeAfterAttempt(ok)"))
    }
}
