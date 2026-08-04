package com.hqrecorder.app.certificate.signing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * TEST_SPEC.md 2.6.2: 端末鍵署名が公開鍵で検証できること／改ざん時に検知できることの検証。
 * Android Keystoreへは依存せず、標準JCAの鍵ペアで検証ロジック単体をテストする。
 */
class DeviceSignatureVerifierTest {

    private val keyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun signWithTestKey(data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").apply {
            initSign(keyPair.private)
            update(data)
        }.sign()

    @Test
    fun verify_validSignature_returnsTrue() {
        val data = "recording-hash".toByteArray()
        val signature = signWithTestKey(data)

        assertTrue(DeviceSignatureVerifier.verify(keyPair.public, data, signature))
    }

    @Test
    fun verify_tamperedData_returnsFalse() {
        val data = "recording-hash".toByteArray()
        val signature = signWithTestKey(data)

        assertFalse(DeviceSignatureVerifier.verify(keyPair.public, "tampered-hash".toByteArray(), signature))
    }

    @Test
    fun verifyEncoded_withExportedPublicKeyBytes_returnsTrue() {
        val data = "recording-hash".toByteArray()
        val signature = signWithTestKey(data)

        assertTrue(DeviceSignatureVerifier.verifyEncoded(keyPair.public.encoded, data, signature))
    }

    @Test
    fun verifyEncoded_invalidSignatureBytes_returnsFalse() {
        val data = "recording-hash".toByteArray()

        assertFalse(DeviceSignatureVerifier.verifyEncoded(keyPair.public.encoded, data, ByteArray(64)))
    }
}
