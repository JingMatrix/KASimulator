/*
 * Copyright 2025 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.beakthoven.TrickyStoreOSS

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.beakthoven.TrickyStoreOSS.logging.Logger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1TaggedObject
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.X509CertificateHolder

val ATTESTATION_OID = ASN1ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17")

object AttestUtils {
    data class AttestationData(
        val verifiedBootHash: ByteArray?,
        val attestVersion: Int?,
        val keymasterVersion: Int?,
        val osVersion: Int?,
    )

    val TEEStatus: Boolean by lazy { isTEEWorking() }
    val CachedAttestData: AttestationData? by lazy { getAttestData() }

    private val keygen_alias = "TEESimulator_attest"

    private fun isTEEWorking(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.app.ActivityThread.initializeMainlineModules()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.security.keystore2.AndroidKeyStoreProvider.install()
            } else {
                android.security.keystore.AndroidKeyStoreProvider.install()
            }

            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)

            val keyPairGenerator =
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")

            val challenge = ByteArray(16).apply { SecureRandom().nextBytes(this) }

            val parameterSpec =
                KeyGenParameterSpec.Builder(keygen_alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setAttestationChallenge(challenge)
                    .setIsStrongBoxBacked(false)
                    .build()

            keyPairGenerator.initialize(parameterSpec)
            keyPairGenerator.generateKeyPair()

            Logger.d("TEE check: successful")

            // keyStore.deleteEntry(keygen_alias)
            true
        } catch (e: Exception) {
            Logger.w("TEE check failure: ${e.message}")
            false
        }
    }

    private fun getAttestCert(): X509Certificate? {
        return if (TEEStatus) {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)

            val certChain = keyStore.getCertificateChain(keygen_alias)
            if (certChain == null || certChain.isEmpty()) {
                null
            } else {
                keyStore.deleteEntry(keygen_alias)
                certChain[0] as X509Certificate
            }
        } else {
            null
        }
    }

    private fun getAttestData(): AttestationData? {
        val leaf: X509Certificate = getAttestCert() ?: return null

        return try {
            val leafHolder = X509CertificateHolder(leaf.encoded)
            val ext: Extension =
                leafHolder.getExtension(ATTESTATION_OID)
                    ?: run {
                        Logger.i("No attestation extension found on certificate")
                        return null
                    }

            val keyDescriptionSeq = ASN1Sequence.getInstance(ext.extnValue.octets)
            val encodables = keyDescriptionSeq.toArray()

            val attestVersion = ASN1Integer.getInstance(encodables[0]).value.intValueExact()
            val keymasterVersion = ASN1Integer.getInstance(encodables[2]).value.intValueExact()
            var attestVerifiedBootHash: ByteArray? = null
            var attestOSVersion: Int? = null

            val teeEnforced = ASN1Sequence.getInstance(encodables[7])

            teeEnforced.forEach { element ->
                val tagged = element as ASN1TaggedObject
                when (tagged.tagNo) {
                    704 -> { // Parse Root of Trust
                        val rootOfTrustSeq =
                            ASN1Sequence.getInstance(tagged.baseObject.toASN1Primitive())
                        if (rootOfTrustSeq.size() >= 4) {
                            attestVerifiedBootHash =
                                ASN1OctetString.getInstance(rootOfTrustSeq.getObjectAt(3)).octets
                        }
                    }
                    705 -> { // Parse OS Version
                        attestOSVersion =
                            ASN1Integer.getInstance(tagged.baseObject.toASN1Primitive())
                                .value
                                .intValueExact()
                    }
                }
            }

            Logger.i("Extracted attestationVersion: $attestVersion")
            Logger.i("Extracted keymasterVersion: $keymasterVersion")
            Logger.i("Extracted verifiedBootHash: ${attestVerifiedBootHash?.toHex() ?: 0}")
            Logger.i("Extracted osVersion: $attestOSVersion")

            AttestationData(
                verifiedBootHash = attestVerifiedBootHash,
                attestVersion = attestVersion,
                keymasterVersion = keymasterVersion,
                osVersion = attestOSVersion,
            )
        } catch (e: Exception) {
            Logger.e("Failed to parse attestation data", e)
            null
        }
    }
}
