/*
 * ABP Call — Ploshadki.kz fork of CallVault (https://github.com/madkongo/CallVault).
 * GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import com.baba.callvault.ploshadki.AutoSummaryPlan.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSummaryPlanTest {

    private val gb8 = 8_000_000_000L
    private val gb4 = 4_000_000_000L

    private fun decide(
        enabled: Boolean = true,
        summaryExists: Boolean = false,
        modelInstalled: Boolean = true,
        ram: Long = gb8,
    ) = AutoSummaryPlan.decide(enabled, summaryExists, modelInstalled, ram)

    @Test
    fun `owner decision 11-11 - automatic summary is on`() {
        assertTrue(PloshadkiFork.AUTO_SUMMARY_ENABLED)
        assertTrue(PloshadkiFork.AUTO_SUMMARY_WAIT_MINUTES in 10L..360L)
    }

    @Test
    fun `installed model on a big phone - summarise, then send`() {
        assertEquals(Step.SUMMARISE_THEN_SEND, decide())
    }

    @Test
    fun `switched off - send now, nothing else`() {
        assertEquals(Step.SEND_NOW, decide(enabled = false))
        assertEquals(Step.SEND_NOW, decide(enabled = false, modelInstalled = false))
    }

    @Test
    fun `a summary already exists - send now, never summarise twice`() {
        assertEquals(Step.SEND_NOW, decide(summaryExists = true))
    }

    @Test
    fun `small phone - send now, never download a model it cannot run`() {
        assertEquals(Step.SEND_NOW, decide(ram = gb4))
        assertEquals(Step.SEND_NOW, decide(ram = gb4, modelInstalled = false))
    }

    @Test
    fun `model missing - download it for later and send this call now`() {
        assertEquals(Step.DOWNLOAD_MODEL_AND_SEND_NOW, decide(modelInstalled = false))
    }

    @Test
    fun `unknown memory (0) does not block`() {
        assertEquals(Step.SUMMARISE_THEN_SEND, decide(ram = 0L))
    }
}
