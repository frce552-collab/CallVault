/*
 * ABP Call — Ploshadki.kz fork of CallVault (https://github.com/madkongo/CallVault).
 * GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import android.app.AlarmManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.PrivilegedMode
import com.baba.callvault.data.RecordingState
import com.baba.callvault.services.recording.DaemonKeepAliveService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * The Doze liveness alarm (owner request 12.11): one inexact allow-while-idle alarm, never more; and when the
 * keep-alive is not running, it brings it back only if the user still wants recording in a mode that keeps it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AbpLivenessAlarmTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = AppPreferences(context)
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))
    private val keepAlive = ComponentName(context, DaemonKeepAliveService::class.java)

    @Before
    fun setUp() {
        ShadowAlarmManager.reset()
        prefs.setRecordingState(RecordingState.ACTIVE)
        prefs.setPrivilegedMode(PrivilegedMode.STANDALONE)
        prefs.setVoipRecordingEnabled(false)
    }

    @After
    fun tearDown() {
        AbpLivenessAlarm.cancel(context)
    }

    private fun startedKeepAlive(): Intent? {
        val app = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
        var i = app.nextStartedService
        while (i != null) {
            if (i.component == keepAlive) return i
            i = app.nextStartedService
        }
        return null
    }

    @Test
    fun `schedule sets one inexact allow-while-idle wakeup alarm`() {
        val before = SystemClock.elapsedRealtime()
        AbpLivenessAlarm.schedule(context, 5 * 60_000L)

        val all = alarms.scheduledAlarms
        assertEquals(1, all.size)
        val a = all.single()
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, a.getType())
        assertTrue(a.isAllowWhileIdle())
        assertTrue(a.getTriggerAtMs() >= before + 5 * 60_000L)
        assertTrue(a.getTriggerAtMs() <= SystemClock.elapsedRealtime() + 5 * 60_000L)
        @Suppress("DEPRECATION")
        val sent = shadowOf(a.operation).savedIntent
        assertEquals(AbpLivenessAlarm.ACTION, sent.action)
        assertEquals(ComponentName(context, AbpLivenessAlarm::class.java), sent.component)
    }

    @Test
    fun `rescheduling replaces the pending alarm instead of adding one`() {
        AbpLivenessAlarm.schedule(context, 60_000L)
        AbpLivenessAlarm.schedule(context, 5 * 60_000L)
        AbpLivenessAlarm.schedule(context, 60_000L)
        assertEquals(1, alarms.scheduledAlarms.size)
    }

    @Test
    fun `cancel removes the alarm and is safe to repeat`() {
        AbpLivenessAlarm.schedule(context, 60_000L)
        AbpLivenessAlarm.cancel(context)
        AbpLivenessAlarm.cancel(context)
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test
    fun `a broadcast with another action does nothing`() {
        AbpLivenessAlarm().onReceive(context, Intent("something.else"))
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertNull(startedKeepAlive())
    }

    @Test
    fun `no running keep-alive is reported as not handled`() {
        assertFalse(DaemonKeepAliveService.livenessCheckFromAlarm(context))
    }

    @Test
    fun `recording turned off by the user - the alarm does not start the keep-alive and ends its chain`() {
        prefs.setRecordingState(RecordingState.STOPPED)
        AbpLivenessAlarm.schedule(context, 60_000L)
        var relaunching: Boolean? = null

        AbpLivenessAlarm.handle(context) { relaunching = it }

        assertNull(startedKeepAlive())
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertEquals(false, relaunching)
    }

    @Test
    fun `Shizuku mode without app calls - the alarm does not start the keep-alive`() {
        prefs.setPrivilegedMode(PrivilegedMode.SHIZUKU)
        prefs.setVoipRecordingEnabled(false)
        AbpLivenessAlarm.schedule(context, 60_000L)

        AbpLivenessAlarm.handle(context) {}

        assertNull(startedKeepAlive())
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test
    fun `Shizuku mode with app calls keeps the keep-alive, as KeepAlivePolicy says`() {
        prefs.setPrivilegedMode(PrivilegedMode.SHIZUKU)
        prefs.setVoipRecordingEnabled(true)

        AbpLivenessAlarm.handle(context) {}

        assertNotNull(startedKeepAlive())
        assertEquals(1, alarms.scheduledAlarms.size)
    }

    @Test
    fun `killed keep-alive with recording on is started again and the next check is scheduled`() {
        var relaunching: Boolean? = null

        AbpLivenessAlarm.handle(context) { relaunching = it }

        assertNotNull(startedKeepAlive())
        assertEquals(1, alarms.scheduledAlarms.size)
        // Down: the retry comes at the short interval.
        assertTrue(alarms.scheduledAlarms.single().getTriggerAtMs() <= SystemClock.elapsedRealtime() + 60_000L)
        assertEquals(true, relaunching)
    }

    @Test
    fun `a refused background foreground-service start does not crash and keeps the chain`() {
        val refusing = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun startForegroundService(service: Intent?): ComponentName? =
                throw ForegroundServiceStartNotAllowedException("background start not allowed")
        }

        AbpLivenessAlarm.handle(refusing) {}

        assertEquals("the next check must still be scheduled", 1, alarms.scheduledAlarms.size)
    }

    @Test
    fun `the down alert is loud, ongoing, and cancelled`() {
        val nm = context.getSystemService(NotificationManager::class.java)
        AbpRecorderDownAlert.show(context)

        val shadow = shadowOf(nm)
        val n = shadow.allNotifications.single()
        assertTrue(n.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        assertNotNull(n.contentIntent)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, nm.getNotificationChannel(n.channelId).importance)

        AbpRecorderDownAlert.show(context)
        assertEquals("showing twice is one notification", 1, shadow.allNotifications.size)

        AbpRecorderDownAlert.cancel(context)
        assertTrue(shadow.allNotifications.isEmpty())
    }
}
