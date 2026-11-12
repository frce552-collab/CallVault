/*
 * ABP Call — Ploshadki.kz fork of CallVault (https://github.com/madkongo/CallVault).
 * GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

/**
 * Pure decisions for the recorder's reboot / Wi-Fi recovery (owner request 12.11.2026). Kept free of Android so
 * every branch is unit-tested; the services only read the switches and act. See [PloshadkiFork].
 */
object AbpBootRecovery {
    /** On boot: drop a "Wireless debugging switched off by the user" mark left from the previous boot. */
    fun shouldForgetWdOffByUser(markSet: Boolean, enabled: Boolean = PloshadkiFork.REBOOT_FORGETS_WD_OFF_BY_USER): Boolean =
        enabled && markSet
}

/**
 * Remembers, for this boot (this process), that switching Wireless debugging off killed the recorder — and then
 * forbids switching it off again. See [PloshadkiFork.KEEP_WD_ON_IF_DROP_KILLED_RECORDER].
 */
object AbpWdDropGuard {
    @Volatile
    private var latched = false

    /**
     * Called on every recorder death. [weJustTurnedWdOff]: CallVault wrote Wireless debugging off within
     * [PloshadkiFork.WD_DROP_DEATH_WINDOW_MS]. Returns true when this death latches the guard (first time only),
     * so the caller can write it to the journal once.
     */
    @Synchronized
    fun onRecorderDied(weJustTurnedWdOff: Boolean, enabled: Boolean = PloshadkiFork.KEEP_WD_ON_IF_DROP_KILLED_RECORDER): Boolean {
        if (!enabled || !weJustTurnedWdOff || latched) return false
        latched = true
        return true
    }

    /** False once a switch-off has killed the recorder in this boot. */
    fun mayDropWirelessDebugging(): Boolean = !latched

    /** Tests only. */
    internal fun reset() {
        latched = false
    }
}

/**
 * A forced relaunch (Wi-Fi joined, Wireless debugging switched on, liveness alarm) that arrived while another
 * relaunch was still running. Instead of dropping it, one more relaunch is made as soon as the running one fails.
 */
class AbpRewarmRetry(private val enabled: Boolean = PloshadkiFork.RETRY_RELAUNCH_REFUSED_WHILE_BUSY) {
    private var pending = false

    /** The gate refused a relaunch because one is in flight. Only forced ones are worth keeping. */
    @Synchronized
    fun onRefusedBusy(force: Boolean) {
        if (enabled && force) pending = true
    }

    /** The in-flight relaunch ended. True: make one more now. Consumes the pending retry either way. */
    @Synchronized
    fun takeAfterAttempt(succeeded: Boolean): Boolean {
        val retry = pending && !succeeded
        pending = false
        return retry
    }
}
