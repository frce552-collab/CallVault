/*
 * ABP Call — Ploshadki.kz fork of CallVault. GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/**
 * The phone's own P-256 key in Android Keystore (never leaves the device) and RFC 9449 DPoP proofs
 * signed with it — the same protocol the server already uses for company Android devices (MOB-ADR-003).
 */
object DpopKey {
    private const val ALIAS = "abp_call_dpop_v1"

    @Synchronized
    fun keyPair(): KeyPair {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry)?.let { return KeyPair(it.certificate.publicKey, it.privateKey) }
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        return generator.generateKeyPair()
    }

    fun reset() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
    }

    /** DPoP proof for one request. [accessToken] set → `ath` claim (resource requests); null → enrollment/refresh. */
    fun proof(method: String, url: String, accessToken: String?): String {
        val kp = keyPair()
        val pub = kp.public as ECPublicKey
        val jwk = mapOf(
            "kty" to "EC",
            "crv" to "P-256",
            "x" to AbpCrypto.b64url(AbpCrypto.fixedLength(pub.w.affineX, 32)),
            "y" to AbpCrypto.b64url(AbpCrypto.fixedLength(pub.w.affineY, 32)),
        )
        val header = mapOf("typ" to "dpop+jwt", "alg" to "ES256", "jwk" to jwk)
        val payload = linkedMapOf<String, Any?>(
            "htm" to method.uppercase(),
            "htu" to url.substringBefore('?'),
            "iat" to System.currentTimeMillis() / 1000L,
            "jti" to UUID.randomUUID().toString(),
        )
        if (accessToken != null) payload["ath"] = AbpCrypto.b64url(AbpCrypto.sha256(accessToken.toByteArray(Charsets.UTF_8)))
        val signingInput = AbpCrypto.b64url(AbpCrypto.canonicalJson(header).toByteArray(Charsets.UTF_8)) + "." +
            AbpCrypto.b64url(AbpCrypto.canonicalJson(payload).toByteArray(Charsets.UTF_8))
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(kp.private)
            update(signingInput.toByteArray(Charsets.US_ASCII))
            sign()
        }
        return signingInput + "." + AbpCrypto.b64url(AbpCrypto.derToJose(der))
    }
}
