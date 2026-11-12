/*
 * ABP Call — Ploshadki.kz fork of CallVault (https://github.com/madkongo/CallVault).
 * GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The administrator PIN that unlocks installing an update ([PloshadkiFork.ADMIN_PIN_FOR_UPDATES]).
 *
 * Never stored as such: only a random salt and PBKDF2-HMAC-SHA256 of the PIN (120 000 rounds) are kept, in the app's
 * private preferences, which are excluded from backups. Five wrong PINs lock the check for five minutes.
 */
object AdminPin {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 8
    private const val MAX_FAILS = 5
    private const val LOCK_MS = 5 * 60_000L
    private const val ITERATIONS = 120_000

    /** Where the hash lives; an interface so the logic is unit-tested without Android. */
    interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String?)
    }

    private class Prefs(context: Context) : Store {
        private val sp = context.applicationContext.getSharedPreferences("abp_admin_pin", Context.MODE_PRIVATE)
        override fun get(key: String) = sp.getString(key, null)
        override fun put(key: String, value: String?) { sp.edit().apply { if (value == null) remove(key) else putString(key, value) }.commit() }
    }

    fun store(context: Context): Store = Prefs(context)

    sealed interface Check {
        data object Ok : Check
        data object Wrong : Check
        data class Locked(val secondsLeft: Long) : Check
    }

    fun isValidFormat(pin: String): Boolean = pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }

    fun isSet(store: Store): Boolean = store.get("hash") != null && store.get("salt") != null

    /** Sets (or replaces) the PIN. Refuses a malformed one. */
    fun set(store: Store, pin: String, random: SecureRandom = SecureRandom()): Boolean {
        if (!isValidFormat(pin)) return false
        val salt = ByteArray(16).also { random.nextBytes(it) }
        store.put("salt", b64(salt))
        store.put("hash", b64(derive(pin, salt)))
        store.put("fails", "0")
        store.put("lockUntil", null)
        return true
    }

    fun verify(store: Store, pin: String, nowMs: Long = System.currentTimeMillis()): Check {
        val lockUntil = store.get("lockUntil")?.toLongOrNull() ?: 0L
        if (nowMs < lockUntil) return Check.Locked((lockUntil - nowMs + 999) / 1000)
        val salt = store.get("salt")?.let(::unb64) ?: return Check.Wrong
        val hash = store.get("hash")?.let(::unb64) ?: return Check.Wrong
        if (isValidFormat(pin) && MessageDigest.isEqual(derive(pin, salt), hash)) {
            store.put("fails", "0"); store.put("lockUntil", null)
            return Check.Ok
        }
        val fails = (store.get("fails")?.toIntOrNull() ?: 0) + 1
        if (fails >= MAX_FAILS) {
            store.put("fails", "0"); store.put("lockUntil", (nowMs + LOCK_MS).toString())
            return Check.Locked(LOCK_MS / 1000)
        }
        store.put("fails", fails.toString())
        return Check.Wrong
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)).encoded

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String) = runCatching { Base64.getDecoder().decode(s) }.getOrNull()
}
