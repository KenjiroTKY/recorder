package com.hqrecorder.app.certificate

import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * ユーザーが設定画面で登録した信頼ルートCA証明書(PEM文字列)を
 * X509Certificateへ変換する。Android非依存の純粋ロジックとしてユニットテスト対象。
 */
object TrustedCaStore {

    fun parsePem(pem: String): X509Certificate? = runCatching {
        val cf = CertificateFactory.getInstance("X.509")
        cf.generateCertificate(pem.trim().byteInputStream()) as X509Certificate
    }.getOrNull()

    fun parseAll(pemList: List<String>): List<X509Certificate> = pemList.mapNotNull { parsePem(it) }
}
