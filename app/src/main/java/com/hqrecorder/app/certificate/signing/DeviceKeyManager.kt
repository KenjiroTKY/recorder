package com.hqrecorder.app.certificate.signing

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Android KeystoreにECDSA鍵ペアを生成・保持し、録音ファイルハッシュへの署名を行う。
 * StrongBox対応端末ではハードウェアセキュリティモジュールに鍵を格納し、非対応時はTEEへフォールバックする。
 * 秘密鍵はKeystore外へエクスポートされない。
 */
class DeviceKeyManager {

    fun publicKey(): PublicKey = privateKeyEntry().certificate.publicKey

    fun sign(data: ByteArray): ByteArray {
        val privateKey = privateKeyEntry().privateKey
        return Signature.getInstance(SIGNATURE_ALGORITHM).apply {
            initSign(privateKey)
            update(data)
        }.sign()
    }

    private fun privateKeyEntry(): KeyStore.PrivateKeyEntry {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry)?.let { return it }

        generateKeyPair()
        return keyStore.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
    }

    private fun generateKeyPair() {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        try {
            generator.initialize(buildSpec(strongBoxBacked = true))
            generator.generateKeyPair()
        } catch (e: StrongBoxUnavailableException) {
            generator.initialize(buildSpec(strongBoxBacked = false))
            generator.generateKeyPair()
        }
    }

    private fun buildSpec(strongBoxBacked: Boolean): KeyGenParameterSpec =
        KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setIsStrongBoxBacked(strongBoxBacked)
            .build()

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "hq_recorder_device_signing_key"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }
}
