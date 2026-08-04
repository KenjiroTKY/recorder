package com.hqrecorder.app.certificate

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import org.bouncycastle.tsp.TSPAlgorithms
import org.bouncycastle.tsp.TimeStampRequestGenerator
import org.bouncycastle.tsp.TimeStampResponseGenerator
import org.bouncycastle.tsp.TimeStampToken
import org.bouncycastle.tsp.TimeStampTokenGenerator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date

class TsaChainVerifierTest {

    private fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        return kpg.generateKeyPair()
    }

    private fun generateCert(
        subjectDn: String,
        issuerDn: String,
        subjectKey: PublicKey,
        issuerKey: PrivateKey,
        notBefore: Date,
        notAfter: Date,
        isCa: Boolean
    ): X509Certificate {
        val serial = BigInteger.valueOf(System.nanoTime())
        val builder = JcaX509v3CertificateBuilder(
            X500Name(issuerDn), serial, notBefore, notAfter, X500Name(subjectDn), subjectKey
        )
        if (isCa) {
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        } else {
            builder.addExtension(Extension.extendedKeyUsage, true, ExtendedKeyUsage(KeyPurposeId.id_kp_timeStamping))
        }
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(issuerKey)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private fun toPem(cert: X509Certificate): String {
        val base64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(cert.encoded)
        return "-----BEGIN CERTIFICATE-----\n$base64\n-----END CERTIFICATE-----\n"
    }

    private fun buildToken(tsaCert: X509Certificate, tsaKey: PrivateKey, genTime: Date): TimeStampToken {
        val digestCalcProvider = JcaDigestCalculatorProviderBuilder().build()
        val contentSigner = JcaContentSignerBuilder("SHA256withRSA").build(tsaKey)
        val signerInfoGenerator = JcaSignerInfoGeneratorBuilder(digestCalcProvider).build(contentSigner, tsaCert)
        val tokenGenerator = TimeStampTokenGenerator(
            signerInfoGenerator,
            digestCalcProvider.get(org.bouncycastle.asn1.x509.AlgorithmIdentifier(org.bouncycastle.asn1.nist.NISTObjectIdentifiers.id_sha256)),
            ASN1ObjectIdentifier("1.2.3.4.5")
        )
        tokenGenerator.addCertificates(JcaCertStore(listOf(tsaCert)))

        val requestGenerator = TimeStampRequestGenerator().apply { setCertReq(true) }
        val messageHash = MessageDigest.getInstance("SHA-256").digest("dummy content".toByteArray())
        val request = requestGenerator.generate(TSPAlgorithms.SHA256, messageHash, BigInteger.ONE)

        val responseGenerator = TimeStampResponseGenerator(tokenGenerator, TSPAlgorithms.ALLOWED)
        val response = responseGenerator.generate(request, BigInteger.TEN, genTime)
        return response.timeStampToken
    }

    private fun buildRootAndTsaCert(genTime: Date): Triple<X509Certificate, X509Certificate, PrivateKey> {
        val rootKeyPair = generateKeyPair()
        val farPast = Date(genTime.time - 365L * 24 * 60 * 60 * 1000)
        val farFuture = Date(genTime.time + 365L * 24 * 60 * 60 * 1000)
        val rootCert = generateCert(
            "CN=Test Root CA", "CN=Test Root CA",
            rootKeyPair.public, rootKeyPair.private,
            farPast, farFuture, isCa = true
        )
        val tsaKeyPair = generateKeyPair()
        val tsaCert = generateCert(
            "CN=Test TSA", "CN=Test Root CA",
            tsaKeyPair.public, rootKeyPair.private,
            farPast, farFuture, isCa = false
        )
        return Triple(rootCert, tsaCert, tsaKeyPair.private)
    }

    @Test
    fun `chain verified when trusted root registered`() {
        val genTime = Date()
        val (rootCert, tsaCert, tsaKey) = buildRootAndTsaCert(genTime)
        val token = buildToken(tsaCert, tsaKey, genTime)

        val result = TsaChainVerifier.verify(token, listOf(toPem(rootCert)))

        assertTrue(result is TsaChainVerifier.Result.Ok)
        val ok = result as TsaChainVerifier.Result.Ok
        assertTrue(ok.chainVerified)
    }

    @Test
    fun `chain skipped when no trusted root registered`() {
        val genTime = Date()
        val (_, tsaCert, tsaKey) = buildRootAndTsaCert(genTime)
        val token = buildToken(tsaCert, tsaKey, genTime)

        val result = TsaChainVerifier.verify(token, emptyList())

        assertTrue(result is TsaChainVerifier.Result.Ok)
        val ok = result as TsaChainVerifier.Result.Ok
        assertFalse(ok.chainVerified)
        assertTrue(ok.warning?.contains("信頼ルートCA未設定") == true)
    }

    @Test
    fun `fatal when tsa certificate expired before timestamp genTime`() {
        val genTime = Date()
        val rootKeyPair = generateKeyPair()
        val farPast = Date(genTime.time - 365L * 24 * 60 * 60 * 1000)
        val farFuture = Date(genTime.time + 365L * 24 * 60 * 60 * 1000)
        val rootCert = generateCert(
            "CN=Test Root CA", "CN=Test Root CA",
            rootKeyPair.public, rootKeyPair.private,
            farPast, farFuture, isCa = true
        )
        val tsaKeyPair = generateKeyPair()
        // TSA証明書の有効期限がgenTimeより前(既に失効)
        val expiredNotAfter = Date(genTime.time - 24 * 60 * 60 * 1000)
        val tsaCert = generateCert(
            "CN=Test TSA", "CN=Test Root CA",
            tsaKeyPair.public, rootKeyPair.private,
            farPast, expiredNotAfter, isCa = false
        )
        val token = buildToken(tsaCert, tsaKeyPair.private, genTime)

        val result = TsaChainVerifier.verify(token, listOf(toPem(rootCert)))

        // BC自体のTimeStampToken.validate()がgenTime時点の証明書有効性を検証するため、
        // このケースは(明示的なcheckValidity()判定より前段の)署名検証で既に失敗として検出される。
        // いずれの経路であってもFatalとして拒否されることが重要。
        assertTrue(result is TsaChainVerifier.Result.Fatal)
    }
}
