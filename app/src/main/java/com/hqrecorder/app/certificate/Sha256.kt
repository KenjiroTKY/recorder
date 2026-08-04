package com.hqrecorder.app.certificate

import java.io.InputStream
import java.security.MessageDigest

/**
 * CertificateManager/CertificateVerifierで共通して使うSHA-256計算。
 * ファイル全体をメモリに載せずストリームで逐次ハッシュ化する。
 */
object Sha256 {
    fun hash(input: InputStream): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        input.use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }
}
