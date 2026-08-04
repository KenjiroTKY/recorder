package com.hqrecorder.app.certificate.signing

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * DeviceKeyManagerで生成した端末鍵による署名を検証する純粋ロジック。
 * 標準JCA APIのみに依存するため、Android Keystoreにアクセスできない環境（第三者の検証端末等）でも
 * エクスポートされた公開鍵(.pub)とファイルハッシュ・署名(.sig)だけで検証できる。
 */
object DeviceSignatureVerifier {
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

    fun verify(publicKey: PublicKey, data: ByteArray, signatureBytes: ByteArray): Boolean {
        return try {
            Signature.getInstance(SIGNATURE_ALGORITHM).apply {
                initVerify(publicKey)
                update(data)
            }.verify(signatureBytes)
        } catch (e: Exception) {
            false
        }
    }

    fun verifyEncoded(publicKeyBytes: ByteArray, data: ByteArray, signatureBytes: ByteArray): Boolean {
        return try {
            val publicKey = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(publicKeyBytes))
            verify(publicKey, data, signatureBytes)
        } catch (e: Exception) {
            false
        }
    }
}
