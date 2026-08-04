package com.hqrecorder.app.certificate

import android.content.Context
import android.net.Uri
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.tsp.TimeStampToken
import java.util.Date

sealed class VerificationResult {
    data class Valid(
        val timestamp: Date,
        val tsaName: String?,
        val chainVerified: Boolean = false,
        val chainWarning: String? = null
    ) : VerificationResult()
    data class Invalid(val reason: String) : VerificationResult()
}

/**
 * 録音ファイルと.tsr(TimeStampToken)を突き合わせ、改ざんの有無を検証する。
 * メッセージダイジェストの一致確認に加え、TsaChainVerifierでTSA署名者証明書の署名・有効期限・
 * (ユーザーが信頼ルートCAを登録済みの場合の)証明書チェーン・失効情報(CRL)を確認する(9.5)。
 */
class CertificateVerifier(private val context: Context) {

    fun verify(fileUri: Uri, tsrUri: Uri, trustedRootCaPems: List<String> = emptyList()): VerificationResult {
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

            when (val chainCheck = TsaChainVerifier.verify(token, trustedRootCaPems)) {
                is TsaChainVerifier.Result.Fatal -> VerificationResult.Invalid(chainCheck.reason)
                is TsaChainVerifier.Result.Ok -> VerificationResult.Valid(
                    timestamp = tstInfo.genTime,
                    tsaName = tstInfo.tsa?.toString(),
                    chainVerified = chainCheck.chainVerified,
                    chainWarning = chainCheck.warning
                )
            }
        } catch (e: Exception) {
            VerificationResult.Invalid("検証エラー: ${e.message}")
        }
    }

    private fun hash(uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("録音ファイルを読み込めません: $uri")
        return Sha256.hash(input)
    }
}
