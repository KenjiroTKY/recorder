package com.hqrecorder.app.certificate.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** TEST_SPEC.md 2.6.3: 区間ハッシュチェーンの改ざん検知の検証。 */
class IntervalHashChainTest {

    private fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())

    @Test
    fun append_chainsPreviousHashWithChunkHash() {
        val builder = IntervalHashChainBuilder()
        val chunk1 = sha256("chunk1")
        val chunk2 = sha256("chunk2")

        val entry1 = builder.append(offsetBytes = 100L, chunkContentHash = chunk1)
        val entry2 = builder.append(offsetBytes = 200L, chunkContentHash = chunk2)

        val expectedHash1 = IntervalHashChainBuilder.chainHash(ByteArray(0), chunk1)
        val expectedHash2 = IntervalHashChainBuilder.chainHash(expectedHash1, chunk2)

        assertEquals(1, entry1.index)
        assertEquals(100L, entry1.offsetBytes)
        assertEquals(expectedHash1.toHexForTest(), entry1.hash)

        assertEquals(2, entry2.index)
        assertEquals(expectedHash2.toHexForTest(), entry2.hash)
    }

    @Test
    fun verify_ofUntamperedChunks_returnsValid() {
        val builder = IntervalHashChainBuilder()
        val chunks = listOf(sha256("a"), sha256("b"), sha256("c"))
        chunks.forEachIndexed { i, hash -> builder.append((i + 1) * 1000L, hash) }

        val result = IntervalHashChainBuilder.verify(builder.entries(), chunks)

        assertTrue(result is ChainVerificationResult.Valid)
    }

    @Test
    fun verify_ofEmptyEntries_returnsEmpty() {
        val result = IntervalHashChainBuilder.verify(emptyList(), emptyList())
        assertTrue(result is ChainVerificationResult.Empty)
    }

    @Test
    fun verify_withMismatchedChunkCount_returnsEntryCountMismatch() {
        val builder = IntervalHashChainBuilder()
        builder.append(1000L, sha256("a"))
        builder.append(2000L, sha256("b"))

        val result = IntervalHashChainBuilder.verify(builder.entries(), listOf(sha256("a")))

        assertTrue(result is ChainVerificationResult.EntryCountMismatch)
    }

    @Test
    fun verify_withTamperedMiddleChunk_detectsFirstDivergentIndex() {
        val builder = IntervalHashChainBuilder()
        val originalChunks = listOf(sha256("chunk1"), sha256("chunk2"), sha256("chunk3"))
        originalChunks.forEachIndexed { i, hash -> builder.append((i + 1) * 1000L, hash) }

        // 2番目の区間のみ内容が差し替えられたと仮定して再ハッシュ化した状態を再現する
        val recomputedChunks = listOf(sha256("chunk1"), sha256("tampered-chunk2"), sha256("chunk3"))

        val result = IntervalHashChainBuilder.verify(builder.entries(), recomputedChunks)

        assertTrue(result is ChainVerificationResult.Tampered)
        assertEquals(2, (result as ChainVerificationResult.Tampered).firstDivergentIndex)
    }

    private fun ByteArray.toHexForTest(): String = joinToString("") { "%02x".format(it) }
}
