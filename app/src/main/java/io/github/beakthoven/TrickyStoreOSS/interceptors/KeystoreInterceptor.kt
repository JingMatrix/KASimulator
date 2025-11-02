/*
 * Copyright 2025 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.beakthoven.TrickyStoreOSS.interceptors

import android.annotation.SuppressLint
import android.os.IBinder
import android.os.Parcel
import android.security.Credentials
import android.security.KeyStore
import android.security.keymaster.ExportResult
import android.security.keymaster.KeyCharacteristics
import android.security.keymaster.KeymasterArguments
import android.security.keymaster.KeymasterCertificateChain
import android.security.keymaster.KeymasterDefs
import android.security.keystore.IKeystoreCertificateChainCallback
import android.security.keystore.IKeystoreExportKeyCallback
import android.security.keystore.IKeystoreKeyCharacteristicsCallback
import android.security.keystore.IKeystoreService
import io.github.beakthoven.TrickyStoreOSS.CertificateGen
import io.github.beakthoven.TrickyStoreOSS.CertificateHack
import io.github.beakthoven.TrickyStoreOSS.KeyBoxUtils
import io.github.beakthoven.TrickyStoreOSS.config.PkgConfig
import io.github.beakthoven.TrickyStoreOSS.interceptors.InterceptorUtils.createByteArrayReply
import io.github.beakthoven.TrickyStoreOSS.interceptors.InterceptorUtils.createSuccessKeystoreResponse
import io.github.beakthoven.TrickyStoreOSS.interceptors.InterceptorUtils.createSuccessReply
import io.github.beakthoven.TrickyStoreOSS.interceptors.InterceptorUtils.extractAlias
import io.github.beakthoven.TrickyStoreOSS.interceptors.InterceptorUtils.getTransactCode
import io.github.beakthoven.TrickyStoreOSS.interceptors.InterceptorUtils.hasException
import io.github.beakthoven.TrickyStoreOSS.logging.Logger
import java.math.BigInteger
import java.security.KeyPair
import java.util.Date

@SuppressLint("BlockedPrivateApi")
object KeystoreInterceptor : BaseKeystoreInterceptor() {
    private val getTransaction = getTransactCode(IKeystoreService.Stub::class.java, "get")
    private val generateKeyTransaction =
        getTransactCode(IKeystoreService.Stub::class.java, "generateKey")
    private val getKeyCharacteristicsTransaction =
        getTransactCode(IKeystoreService.Stub::class.java, "getKeyCharacteristics")
    private val exportKeyTransaction =
        getTransactCode(IKeystoreService.Stub::class.java, "exportKey")
    private val attestKeyTransaction =
        getTransactCode(IKeystoreService.Stub::class.java, "attestKey")

    override val serviceName = "android.security.keystore"
    override val processName = "keystore"
    override val injectionCommand = "exec ./inject `pidof keystore` libTEESimulator.so entry"

    private const val DESCRIPTOR = "android.security.keystore.IKeystoreService"

    private val keyArguments = HashMap<Key, CertificateGen.KeyGenParameters>()
    private val keyPairs = HashMap<Key, KeyPair>()

    data class Key(val uid: Int, val alias: String)

    override fun onPreTransact(
        target: IBinder,
        code: Int,
        flags: Int,
        callingUid: Int,
        callingPid: Int,
        data: Parcel,
    ): Result {
        if (KeyBoxUtils.hasKeyboxes()) {
            if (code == getTransaction) {
                if (PkgConfig.needHack(callingUid)) {
                    return Continue
                } else if (PkgConfig.needGenerate(callingUid)) {
                    return Skip
                }
            } else if (PkgConfig.needGenerate(callingUid)) {
                when (code) {
                    generateKeyTransaction -> {
                        kotlin
                            .runCatching {
                                data.enforceInterface(DESCRIPTOR)
                                val callback =
                                    IKeystoreKeyCharacteristicsCallback.Stub.asInterface(
                                        data.readStrongBinder()
                                    )
                                val alias = data.readString()!!.extractAlias()
                                Logger.i("generateKeyTransaction uid $callingUid alias $alias")
                                val check = data.readInt()
                                val kma = KeymasterArguments()
                                val kgp = CertificateGen.KeyGenParameters()
                                if (check == 1) {
                                    kma.readFromParcel(data)
                                    kgp.algorithm = kma.getEnum(KeymasterDefs.KM_TAG_ALGORITHM, 0)
                                    kgp.keySize =
                                        kma.getUnsignedInt(KeymasterDefs.KM_TAG_KEY_SIZE, 0).toInt()
                                    // kgp.setEcCurveName(kgp.keySize)
                                    kgp.purpose = kma.getEnums(KeymasterDefs.KM_TAG_PURPOSE)
                                    kgp.digest = kma.getEnums(KeymasterDefs.KM_TAG_DIGEST)
                                    kgp.certificateNotBefore =
                                        kma.getDate(KeymasterDefs.KM_TAG_ACTIVE_DATETIME, Date())
                                    if (kgp.algorithm == KeymasterDefs.KM_ALGORITHM_RSA) {
                                        try {
                                            val getArgumentByTag =
                                                KeymasterArguments::class
                                                    .java
                                                    .getDeclaredMethods()
                                                    .first { it.name == "getArgumentByTag" }
                                            getArgumentByTag.isAccessible = true
                                            val rsaArgument =
                                                getArgumentByTag.invoke(
                                                    kma,
                                                    KeymasterDefs.KM_TAG_RSA_PUBLIC_EXPONENT,
                                                )

                                            val getLongTagValue =
                                                KeymasterArguments::class
                                                    .java
                                                    .getDeclaredMethods()
                                                    .first { it.name == "getLongTagValue" }
                                            getLongTagValue.isAccessible = true
                                            kgp.rsaPublicExponent =
                                                getLongTagValue.invoke(kma, rsaArgument)
                                                    as BigInteger
                                        } catch (ex: Exception) {
                                            Logger.e("Read rsaPublicExponent error", ex)
                                        }
                                    }
                                    keyArguments[Key(callingUid, alias)] = kgp
                                }

                                val kc = KeyCharacteristics()
                                kc.swEnforced = KeymasterArguments()
                                kc.hwEnforced = kma

                                val ksr = createSuccessKeystoreResponse()
                                callback.onFinished(ksr, kc)

                                return createSuccessReply()
                            }
                            .onFailure { Logger.e("generateKeyTransaction error", it) }
                    }

                    getKeyCharacteristicsTransaction -> {
                        kotlin
                            .runCatching {
                                data.enforceInterface(DESCRIPTOR)
                                val callback =
                                    IKeystoreKeyCharacteristicsCallback.Stub.asInterface(
                                        data.readStrongBinder()
                                    )
                                val alias = data.readString()!!.extractAlias()
                                Logger.i(
                                    "getKeyCharacteristicsTransaction uid $callingUid alias $alias"
                                )
                                val kc = KeyCharacteristics()
                                val kma = KeymasterArguments()
                                kma.addEnum(
                                    KeymasterDefs.KM_TAG_ALGORITHM,
                                    keyArguments[Key(callingUid, alias)]!!.algorithm,
                                )
                                kc.swEnforced = KeymasterArguments()
                                kc.hwEnforced = kma

                                val ksr = createSuccessKeystoreResponse()
                                callback.onFinished(ksr, kc)

                                return createSuccessReply()
                            }
                            .onFailure { Logger.e("getKeyCharacteristicsTransaction error", it) }
                    }

                    exportKeyTransaction -> {
                        kotlin
                            .runCatching {
                                data.enforceInterface(DESCRIPTOR)
                                val callback =
                                    IKeystoreExportKeyCallback.Stub.asInterface(
                                        data.readStrongBinder()
                                    )
                                val alias = data.readString()!!.extractAlias()
                                Logger.i("exportKeyTransaction uid $callingUid alias $alias")
                                val kp =
                                    CertificateGen.generateKeyPair(
                                        keyArguments[Key(callingUid, alias)]!!
                                    )
                                keyPairs[Key(callingUid, alias)] = kp!!

                                val erP = Parcel.obtain()
                                erP.writeInt(KeyStore.NO_ERROR)
                                erP.writeByteArray(kp.public.encoded)
                                erP.setDataPosition(0)
                                val er = ExportResult.CREATOR.createFromParcel(erP)
                                erP.recycle()

                                callback.onFinished(er)

                                return createSuccessReply()
                            }
                            .onFailure { Logger.e("exportKeyTransaction error", it) }
                    }

                    attestKeyTransaction -> {
                        kotlin
                            .runCatching {
                                data.enforceInterface(DESCRIPTOR)
                                val callback =
                                    IKeystoreCertificateChainCallback.Stub.asInterface(
                                        data.readStrongBinder()
                                    )
                                val alias = data.readString()!!.extractAlias()
                                Logger.i("attestKeyTransaction uid $callingUid alias $alias")
                                val check = data.readInt()
                                val kma = KeymasterArguments()
                                if (check == 1) {
                                    kma.readFromParcel(data)
                                    val attestationChallenge =
                                        kma.getBytes(
                                            KeymasterDefs.KM_TAG_ATTESTATION_CHALLENGE,
                                            ByteArray(0),
                                        )

                                    val ksr = createSuccessKeystoreResponse()

                                    val key = Key(callingUid, alias)
                                    val ka = keyArguments[key]!!
                                    ka.attestationChallenge = attestationChallenge
                                    val chain =
                                        CertificateGen.generateChain(
                                            callingUid,
                                            ka,
                                            keyPairs[key]!!,
                                        )

                                    val kcc = KeymasterCertificateChain(chain)
                                    callback.onFinished(ksr, kcc)
                                }

                                return createSuccessReply()
                            }
                            .onFailure { Logger.e("attestKeyTransaction error", it) }
                    }
                }
            }
        }
        return Skip
    }

    override fun onPostTransact(
        target: IBinder,
        code: Int,
        flags: Int,
        callingUid: Int,
        callingPid: Int,
        data: Parcel,
        reply: Parcel?,
        resultCode: Int,
    ): Result {
        if (target != keystore || code != getTransaction || reply == null) return Skip
        if (reply.hasException()) return Skip
        val p = Parcel.obtain()
        Logger.d(
            "intercept post $target uid=$callingUid pid=$callingPid dataSz=${data.dataSize()} replySz=${reply.dataSize()}"
        )
        try {
            data.enforceInterface(DESCRIPTOR)
            val alias = data.readString() ?: ""
            var response = reply.createByteArray()
            when {
                alias.startsWith(Credentials.USER_CERTIFICATE) -> {
                    response =
                        CertificateHack.hackUserCertificate(
                            response!!,
                            alias.extractAlias(),
                            callingUid,
                        )
                    Logger.i("Hacked leaf certificate for uid=$callingUid")
                    return createByteArrayReply(response)
                }
                alias.startsWith(Credentials.CA_CERTIFICATE) -> {
                    response =
                        CertificateHack.hackCACertificateChain(
                            response!!,
                            alias.extractAlias(),
                            callingUid,
                        )
                    Logger.i("Hacked CA certificate chain for uid=$callingUid")
                    return createByteArrayReply(response)
                }
                else -> p.recycle()
            }
        } catch (t: Throwable) {
            Logger.e("failed to hack certificate chain of uid=$callingUid pid=$callingPid!", t)
            p.recycle()
        }
        return Skip
    }
}
