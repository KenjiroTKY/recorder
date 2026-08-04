package com.hqrecorder.app.certificate

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.Date

class TrustedCaStoreTest {

    @Test
    fun `parsePem returns null for invalid input`() {
        assertNull(TrustedCaStore.parsePem("not a certificate"))
    }

    @Test
    fun `parsePem parses a valid self-signed certificate`() {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val keyPair = kpg.generateKeyPair()
        val now = Date()
        val builder = JcaX509v3CertificateBuilder(
            X500Name("CN=Test Root CA"),
            BigInteger.valueOf(1),
            now,
            Date(now.time + 24 * 60 * 60 * 1000),
            X500Name("CN=Test Root CA"),
            keyPair.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        val pem = "-----BEGIN CERTIFICATE-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(cert.encoded) +
            "\n-----END CERTIFICATE-----\n"

        val parsed = TrustedCaStore.parsePem(pem)

        assertEquals(cert.subjectX500Principal, parsed?.subjectX500Principal)
    }
}
