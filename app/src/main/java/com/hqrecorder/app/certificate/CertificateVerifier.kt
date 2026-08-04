package com.hqrecorder.app.certificate

import android.content.Context
import android.net.Uri
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.tsp.TimeStampToken
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
            val token = readToken(tsrUri)
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

    /**
     * 開始時刻証明(9.1)と終了時刻証明のTSA発行時刻を突き合わせ、開始<終了の順序整合性を検証する。
     * 録音全体が「開始時刻証明〜終了時刻証明の間に生成された」ことを示す補助証跡。
     */
    fun verifyStartEndOrder(startTsrUri: Uri, endTsrUri: Uri): VerificationResult {
        return try {
            val startTime = readToken(startTsrUri).timeStampInfo.genTime
            val endToken = readToken(endTsrUri)
            val endTime = endToken.timeStampInfo.genTime

            if (!StartEndTimeValidator.isValidOrder(startTime, endTime)) {
                return VerificationResult.Invalid("開始時刻証明が終了時刻証明以降になっています（改ざんの可能性があります）")
            }

            VerificationResult.Valid(
                timestamp = endTime,
                tsaName = endToken.timeStampInfo.tsa?.toString()
            )
        } catch (e: Exception) {
            VerificationResult.Invalid("開始/終了時刻証明の検証エラー: ${e.message}")
        }
    }

    private fun readToken(tsrUri: Uri): TimeStampToken {
        val tokenBytes = context.contentResolver.openInputStream(tsrUri)?.use { it.readBytes() }
            ?: throw IllegalStateException("証明書ファイルを読み込めません: $tsrUri")
        return TimeStampToken(CMSSignedData(tokenBytes))
    }

    private fun hash(uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("録音ファイルを読み込めません: $uri")
        return Sha256.hash(input)
    }
}
