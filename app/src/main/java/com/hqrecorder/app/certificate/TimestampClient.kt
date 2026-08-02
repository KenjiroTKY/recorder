package com.hqrecorder.app.certificate

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.tsp.TSPAlgorithms
import org.bouncycastle.tsp.TimeStampRequestGenerator
import org.bouncycastle.tsp.TimeStampResponse
import java.math.BigInteger
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class TimestampException(message: String) : Exception(message)

/**
 * RFC3161準拠のタイムスタンプ局(TSA)へファイルのSHA-256ハッシュを送信し、
 * TimeStampToken(TSR)を取得するクライアント。音声ファイル本体は送信しない。
 */
class TimestampClient(
    private val tsaUrl: String,
    private val authHeader: String? = null
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun requestTimestamp(fileHashSha256: ByteArray): ByteArray {
        val reqGenerator = TimeStampRequestGenerator().apply { setCertReq(true) }
        val nonce = BigInteger(64, SecureRandom())
        val tsRequest = reqGenerator.generate(TSPAlgorithms.SHA256, fileHashSha256, nonce)
        val requestBytes = tsRequest.encoded

        val requestBuilder = Request.Builder()
            .url(tsaUrl)
            .post(requestBytes.toRequestBody(MEDIA_TYPE_TS_QUERY))
            .header("Content-Type", "application/timestamp-query")
        if (!authHeader.isNullOrBlank()) {
            requestBuilder.header("Authorization", authHeader)
        }

        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw TimestampException("TSA HTTPエラー: ${response.code}")
            }
            val responseBytes = response.body?.bytes()
                ?: throw TimestampException("TSAレスポンスが空です")

            val tsResponse = TimeStampResponse(responseBytes)
            tsResponse.validate(tsRequest)
            val token = tsResponse.timeStampToken
                ?: throw TimestampException("タイムスタンプトークンを取得できませんでした: ${tsResponse.statusString}")
            return token.encoded
        }
    }

    companion object {
        private val MEDIA_TYPE_TS_QUERY = "application/timestamp-query".toMediaType()
    }
}
