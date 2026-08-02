package com.hqrecorder.app.certificate

import android.content.Context
import android.net.Uri
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.tsp.TimeStampToken
import java.security.MessageDigest
import java.util.Date

sealed class VerificationResult {
    data class Valid(val timestamp: Date, val tsaName: String?) : VerificationResult()
    data class Invalid(val reason: String) : VerificationResult()
}

/**
 * 録音ファイルと.tsr(TimeStampToken)を突き合わせ、改ざんの有無を検証する。
 * 現状はメッセージダイジェストの一致確認のみで、TSA署名者証明書チェーンの検証は将来課題。
 */
class CertificateVerifier(private val context: Context) {

    fun verify(fileUri: Uri, tsrUri: Uri): VerificationResult {
        return try {
            val fileHash = hash(fileUri)
            val tokenBytes = context.contentResolver.openInputStream(tsrUri)?.use { it.readBytes() }
                ?: return VerificationResult.Invalid("証明書ファイルを読み込めません")

            val token = TimeStampToken(CMSSignedData(tokenBytes))
            val tstInfo = token.timeStampInfo
            val expectedDigest = tstInfo.messageImprintDigest

            if (!expectedDigest.contentEquals(fileHash)) {
                return VerificationResult.Invalid("ファイルのハッシュ値が証明書と一致しません（改ざんの可能性があります）")
            }

            VerificationResult.Valid(
                timestamp = tstInfo.genTime,
                tsaName = tstInfo.tsa?.toString()
            )
        } catch (e: Exception) {
            VerificationResult.Invalid("検証エラー: ${e.message}")
        }
    }

    private fun hash(uri: Uri): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }
}
