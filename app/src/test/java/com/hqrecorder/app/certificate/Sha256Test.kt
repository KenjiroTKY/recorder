package com.hqrecorder.app.certificate

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** TEST_SPEC.md 2.5.1 / 2.5.4: SHA-256算出（既知ベクタ比較）と改ざん時の不一致検出の検証。 */
class Sha256Test {

    @Test
    fun hash_ofEmptyInput_matchesKnownVector() {
        val digest = Sha256.hash(ByteArrayInputStream(ByteArray(0)))
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            digest.toHex()
        )
    }

    @Test
    fun hash_ofKnownString_matchesKnownVector() {
        val digest = Sha256.hash(ByteArrayInputStream("abc".toByteArray(Charsets.US_ASCII)))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            digest.toHex()
        )
    }

    @Test
    fun hash_ofTamperedContent_differsFromOriginal() {
        val original = Sha256.hash(ByteArrayInputStream("original content".toByteArray()))
        val tampered = Sha256.hash(ByteArrayInputStream("tampered content".toByteArray()))

        assertNotEquals(original.toHex(), tampered.toHex())
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
