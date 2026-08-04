package com.hqrecorder.app.audio

import com.hqrecorder.app.certificate.chain.ChainEntry
import com.hqrecorder.app.certificate.chain.IntervalHashChainBuilder
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * 録音中に書き込み中のファイルから一定間隔(デフォルト30秒)ごとに新規区間を読み出しハッシュ化、
 * IntervalHashChainBuilderへ連結する(SPEC.md 3.6 / DESIGN.md 9.3)。
 * ディスク読み取り・ハッシュ計算はCPU負荷があるため、呼び出し側で音声読み取りループとは
 * 別スレッドから呼び出し、録音スレッドをブロックしないこと。
 */
class IntervalHashChainRecorder(
    private val intervalMs: Long = 30_000L,
    initialOffsetBytes: Long = 0L
) {
    private val builder = IntervalHashChainBuilder()
    // WAVはヘッダのサイズフィールドをstop()時に書き戻すため、ヘッダ領域(録音開始直後のbytesWritten())は
    // チェーンの対象外とする。録音開始直後の値を初期オフセットとして受け取ることでフォーマット非依存に扱う。
    private var lastOffset = initialOffsetBytes
    private var lastCheckpointAtMs = -1L

    /** 前回チェックポイントからintervalMs以上経過していれば、新規区間をハッシュ化して連結する。 */
    fun checkpointIfDue(filePath: String, currentBytesWritten: Long, nowMs: Long): ChainEntry? {
        if (lastCheckpointAtMs >= 0 && nowMs - lastCheckpointAtMs < intervalMs) return null
        val entry = appendChunk(filePath, currentBytesWritten)
        lastCheckpointAtMs = nowMs
        return entry
    }

    /** パート確定時に、まだチェーンに含まれていない末尾区間を連結してチェーン全体を返す。 */
    fun finalizeChain(filePath: String, currentBytesWritten: Long): List<ChainEntry> {
        appendChunk(filePath, currentBytesWritten)
        return builder.entries()
    }

    private fun appendChunk(filePath: String, currentBytesWritten: Long): ChainEntry? {
        if (currentBytesWritten <= lastOffset) return null
        val chunkHash = hashRange(filePath, lastOffset, currentBytesWritten)
        val entry = builder.append(currentBytesWritten, chunkHash)
        lastOffset = currentBytesWritten
        return entry
    }

    private fun hashRange(filePath: String, start: Long, end: Long): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(filePath, "r").use { raf ->
            raf.seek(start)
            var remaining = end - start
            val buffer = ByteArray(64 * 1024)
            while (remaining > 0) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = raf.read(buffer, 0, toRead)
                if (read <= 0) break
                digest.update(buffer, 0, read)
                remaining -= read
            }
        }
        return digest.digest()
    }
}
