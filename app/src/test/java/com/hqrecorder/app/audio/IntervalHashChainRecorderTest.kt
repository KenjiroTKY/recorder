package com.hqrecorder.app.audio

import com.hqrecorder.app.certificate.chain.ChainVerificationResult
import com.hqrecorder.app.certificate.chain.IntervalHashChainBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile
import java.security.MessageDigest

/** TEST_SPEC.md 2.6.3: 区間ハッシュチェーンが実ファイルの書き込みに追従し、改ざんを検知できることの検証。 */
class IntervalHashChainRecorderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun checkpointIfDue_skipsWhenIntervalNotElapsed() {
        val file = tempFolder.newFile("part1.wav")
        appendBytes(file, ByteArray(10))
        val recorder = IntervalHashChainRecorder(intervalMs = 30_000L)

        val first = recorder.checkpointIfDue(file.absolutePath, file.length(), nowMs = 0L)
        val second = recorder.checkpointIfDue(file.absolutePath, file.length(), nowMs = 10_000L)

        assertNotNull(first)
        assertNull(second)
    }

    @Test
    fun checkpointIfDue_afterIntervalElapsed_appendsNewChunk() {
        val file = tempFolder.newFile("part1.wav")
        appendBytes(file, ByteArray(10))
        val recorder = IntervalHashChainRecorder(intervalMs = 30_000L)
        recorder.checkpointIfDue(file.absolutePath, file.length(), nowMs = 0L)

        appendBytes(file, ByteArray(20))
        val entry = recorder.checkpointIfDue(file.absolutePath, file.length(), nowMs = 30_000L)

        assertNotNull(entry)
        assertEquals(2, entry!!.index)
        assertEquals(30L, entry.offsetBytes)
    }

    @Test
    fun finalizeChain_recomputedContentHashes_verifyAsValid() {
        val file = tempFolder.newFile("part1.wav")
        val chunk1 = "0123456789".toByteArray()
        val chunk2 = "abcdefghij".toByteArray()
        appendBytes(file, chunk1)

        val recorder = IntervalHashChainRecorder(intervalMs = 30_000L)
        recorder.checkpointIfDue(file.absolutePath, file.length(), nowMs = 0L)
        appendBytes(file, chunk2)
        val entries = recorder.finalizeChain(file.absolutePath, file.length())

        val result = IntervalHashChainBuilder.verify(entries, listOf(sha256(chunk1), sha256(chunk2)))

        assertTrue(result is ChainVerificationResult.Valid)
    }

    @Test
    fun finalizeChain_afterFileTampering_detectsMismatch() {
        val file = tempFolder.newFile("part1.wav")
        val chunk1 = "0123456789".toByteArray()
        val chunk2 = "abcdefghij".toByteArray()
        appendBytes(file, chunk1)

        val recorder = IntervalHashChainRecorder(intervalMs = 30_000L)
        recorder.checkpointIfDue(file.absolutePath, file.length(), nowMs = 0L)
        appendBytes(file, chunk2)
        val entries = recorder.finalizeChain(file.absolutePath, file.length())

        // ファイルの2区間目を後から書き換えたと仮定して、実ファイルから区間ハッシュを再計算する
        overwriteRange(file, offset = 10, replacement = "TAMPERED!!".toByteArray())
        val recomputedChunk2 = hashRange(file, start = 10, end = 20)

        val result = IntervalHashChainBuilder.verify(entries, listOf(sha256(chunk1), recomputedChunk2))

        assertTrue(result is ChainVerificationResult.Tampered)
        assertEquals(2, (result as ChainVerificationResult.Tampered).firstDivergentIndex)
    }

    @Test
    fun initialOffsetBytes_excludesHeaderRegionFromChain() {
        // WAVヘッダのようにstop()時に書き戻される先頭領域を、意図的にプレースホルダのまま書いておく
        val file = tempFolder.newFile("part1.wav")
        appendBytes(file, ByteArray(44)) // ヘッダ領域(プレースホルダ、全ゼロ)
        val headerOffsetAtStart = file.length()
        val dataChunk = "0123456789".toByteArray()
        appendBytes(file, dataChunk)

        val recorder = IntervalHashChainRecorder(intervalMs = 30_000L, initialOffsetBytes = headerOffsetAtStart)
        val entries = recorder.finalizeChain(file.absolutePath, file.length())

        // ヘッダが後から実際の値へ書き戻された(=先頭44バイトが変化した)状態を再現する
        overwriteRange(file, offset = 0, replacement = "RIFF".toByteArray() + ByteArray(40) { 1 })
        val recomputedDataHash = hashRange(file, start = headerOffsetAtStart, end = file.length())

        val result = IntervalHashChainBuilder.verify(entries, listOf(recomputedDataHash))

        assertTrue(result is ChainVerificationResult.Valid)
    }

    private fun appendBytes(file: java.io.File, bytes: ByteArray) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(raf.length())
            raf.write(bytes)
        }
    }

    private fun overwriteRange(file: java.io.File, offset: Long, replacement: ByteArray) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(offset)
            raf.write(replacement)
        }
    }

    private fun hashRange(file: java.io.File, start: Long, end: Long): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buffer = ByteArray((end - start).toInt())
            raf.readFully(buffer)
            digest.update(buffer)
        }
        return digest.digest()
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
