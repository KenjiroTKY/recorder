package com.hqrecorder.app.certificate

import okhttp3.OkHttpClient
import okhttp3.Request
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.x509.CRLDistPoint
import org.bouncycastle.asn1.x509.DistributionPointName
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.tsp.TimeStampToken
import org.bouncycastle.util.Selector
import java.security.cert.CertPathBuilder
import java.security.cert.CertStore
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateFactory
import java.security.cert.CertificateNotYetValidException
import java.security.cert.CollectionCertStoreParameters
import java.security.cert.PKIXBuilderParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509CertSelector
import java.security.cert.X509Certificate

/**
 * TSA署名者証明書の署名検証・有効期限・(ユーザー登録済みなら)信頼ルートCAへの
 * チェーン構築・CRLによる失効確認を行う(9.5)。Androidに非依存の純粋ロジックとして
 * CertificateVerifierから切り出し、ユニットテスト対象とする。
 * OCSPによる失効確認は実装コストが高いため将来課題とする。
 */
object TsaChainVerifier {

    sealed class Result {
        data class Ok(val chainVerified: Boolean, val warning: String?) : Result()
        data class Fatal(val reason: String) : Result()
    }

    /**
     * 署名不一致・有効期限外・失効確認済みは致命的エラー(Fatal)、
     * 信頼ルートCA未設定によるチェーン検証省略やCRL取得不能はwarningとして非致命的に扱う(Ok)。
     */
    fun verify(token: TimeStampToken, trustedRootCaPems: List<String>): Result {
        val signedData = token.toCMSSignedData()
        val signerInfo = signedData.signerInfos.signers.firstOrNull()
            ?: return Result.Fatal("TSA署名者情報が見つかりません")
        @Suppress("UNCHECKED_CAST")
        val certHolder = signedData.certificates.getMatches(signerInfo.sid as Selector<X509CertificateHolder>).firstOrNull()
            ?: return Result.Fatal("TSA署名者証明書が見つかりません")

        try {
            token.validate(JcaSimpleSignerInfoVerifierBuilder().build(certHolder))
        } catch (e: Exception) {
            return Result.Fatal("TSA署名者証明書の署名検証に失敗しました: ${e.message}")
        }

        val cert = JcaX509CertificateConverter().getCertificate(certHolder)
        try {
            cert.checkValidity(token.timeStampInfo.genTime)
        } catch (e: CertificateExpiredException) {
            return Result.Fatal("TSA証明書はタイムスタンプ発行時点で有効期限が切れています")
        } catch (e: CertificateNotYetValidException) {
            return Result.Fatal("TSA証明書はタイムスタンプ発行時点でまだ有効ではありません")
        }

        val warnings = mutableListOf<String>()

        checkRevocation(cert)?.let { revocation ->
            if (revocation.revoked) {
                return Result.Fatal(revocation.message)
            }
            warnings.add(revocation.message)
        }

        var chainVerified = false
        val trustAnchors = TrustedCaStore.parseAll(trustedRootCaPems)
        if (trustAnchors.isEmpty()) {
            warnings.add("信頼ルートCA未設定のためチェーン検証は省略されました")
        } else {
            val converter = JcaX509CertificateConverter()
            val otherCerts = signedData.certificates.getMatches(null)
                .mapNotNull { runCatching { converter.getCertificate(it) }.getOrNull() }
            chainVerified = try {
                buildAndValidateChain(cert, otherCerts, trustAnchors)
                true
            } catch (e: Exception) {
                warnings.add("信頼ルートCAへのチェーン検証に失敗しました: ${e.message}")
                false
            }
        }

        return Result.Ok(chainVerified, warnings.takeIf { it.isNotEmpty() }?.joinToString(" / "))
    }

    private fun buildAndValidateChain(cert: X509Certificate, otherCerts: List<X509Certificate>, trustAnchors: List<X509Certificate>) {
        val anchors = trustAnchors.map { TrustAnchor(it, null) }.toSet()
        val certStore = CertStore.getInstance(
            "Collection",
            CollectionCertStoreParameters(otherCerts + cert)
        )
        val selector = X509CertSelector().apply { certificate = cert }
        val params = PKIXBuilderParameters(anchors, selector).apply {
            addCertStore(certStore)
            isRevocationEnabled = false
        }
        CertPathBuilder.getInstance("PKIX").build(params)
    }

    private data class RevocationInfo(val revoked: Boolean, val message: String)

    /** CRLによるベストエフォートの失効確認。CRLDPが無い/取得不能な場合は非致命的なwarningとして扱う(null=失効なし・警告なし)。 */
    private fun checkRevocation(cert: X509Certificate): RevocationInfo? {
        val crlUrl = extractCrlUrl(cert) ?: return RevocationInfo(false, "CRL配布点情報がないため失効確認は省略されました")
        return try {
            val request = Request.Builder().url(crlUrl).build()
            OkHttpClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return RevocationInfo(false, "CRLの取得に失敗しました(HTTP ${response.code})")
                }
                val bytes = response.body?.bytes() ?: return RevocationInfo(false, "CRLレスポンスが空です")
                val crl = CertificateFactory.getInstance("X.509").generateCRL(bytes.inputStream())
                if (crl.isRevoked(cert)) {
                    RevocationInfo(true, "TSA証明書はCRLにより失効が確認されました")
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            RevocationInfo(false, "CRLによる失効確認中にエラーが発生しました: ${e.message}")
        }
    }

    private fun extractCrlUrl(cert: X509Certificate): String? = runCatching {
        val extBytes = cert.getExtensionValue(Extension.cRLDistributionPoints.id) ?: return null
        val octets = ASN1OctetString.getInstance(extBytes).octets
        val crlDistPoint = CRLDistPoint.getInstance(ASN1Primitive.fromByteArray(octets))
        for (dp in crlDistPoint.distributionPoints) {
            val dpn = dp.distributionPoint ?: continue
            if (dpn.type == DistributionPointName.FULL_NAME) {
                val names = GeneralNames.getInstance(dpn.name).names
                for (name in names) {
                    if (name.tagNo == GeneralName.uniformResourceIdentifier) {
                        return name.name.toString()
                    }
                }
            }
        }
        null
    }.getOrNull()
}
