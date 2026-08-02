package com.hqrecorder.app.audio

import java.io.RandomAccessFile

/**
 * PCM WAVをRandomAccessFileへ書き込む。ヘッダのサイズフィールドは
 * stop()時に確定値へ書き戻すため、出力先はシーク可能なローカルファイルである必要がある。
 */
class WavFileWriter(private val bitDepth: Int) : AudioFileWriter {

    private lateinit var raf: RandomAccessFile
    private var filePath: String = ""
    private var sampleRateHz = 44_100
    private var channelCount = 2
    private var dataBytesWritten = 0L

    override fun start(filePath: String, sampleRateHz: Int, channelCount: Int) {
        this.filePath = filePath
        this.sampleRateHz = sampleRateHz
        this.channelCount = channelCount
        this.dataBytesWritten = 0L
        raf = RandomAccessFile(filePath, "rw")
        raf.setLength(0)
        raf.write(ByteArray(44))
    }

    override fun writeShortFrame(pcm: ShortArray, frameCount: Int) {
        val sampleCount = frameCount * channelCount
        val bytes = ByteArray(sampleCount * 2)
        var bi = 0
        for (i in 0 until sampleCount) {
            val v = pcm[i].toInt()
            bytes[bi++] = (v and 0xFF).toByte()
            bytes[bi++] = ((v shr 8) and 0xFF).toByte()
        }
        raf.write(bytes)
        dataBytesWritten += bytes.size
    }

    override fun writeFloatFrame(pcm: FloatArray, frameCount: Int) {
        val sampleCount = frameCount * channelCount
        val bytesPerSample = if (bitDepth == 24) 3 else 2
        val bytes = ByteArray(sampleCount * bytesPerSample)
        var bi = 0
        for (i in 0 until sampleCount) {
            val clamped = pcm[i].coerceIn(-1f, 1f)
            if (bitDepth == 24) {
                val v = (clamped * 8_388_607f).toInt()
                bytes[bi++] = (v and 0xFF).toByte()
                bytes[bi++] = ((v shr 8) and 0xFF).toByte()
                bytes[bi++] = ((v shr 16) and 0xFF).toByte()
            } else {
                val v = (clamped * 32_767f).toInt()
                bytes[bi++] = (v and 0xFF).toByte()
                bytes[bi++] = ((v shr 8) and 0xFF).toByte()
            }
        }
        raf.write(bytes)
        dataBytesWritten += bytes.size
    }

    override fun bytesWritten(): Long = dataBytesWritten + 44

    override fun stop(): AudioFileWriterResult {
        val bytesPerSample = if (bitDepth == 24) 3 else 2
        finalizeHeader(bytesPerSample)
        val durationMs = if (sampleRateHz > 0 && channelCount > 0) {
            (dataBytesWritten * 1000L) / (sampleRateHz.toLong() * channelCount * bytesPerSample)
        } else {
            0L
        }
        val size = raf.length()
        raf.close()
        return AudioFileWriterResult(filePath, durationMs, size)
    }

    private fun finalizeHeader(bytesPerSample: Int) {
        val byteRate = sampleRateHz * channelCount * bytesPerSample
        val blockAlign = channelCount * bytesPerSample
        val dataSize = dataBytesWritten
        val riffSize = 36 + dataSize

        raf.seek(0)
        raf.writeAscii("RIFF")
        raf.writeIntLE(riffSize.toInt())
        raf.writeAscii("WAVE")
        raf.writeAscii("fmt ")
        raf.writeIntLE(16)
        raf.writeShortLE(1) // PCM format
        raf.writeShortLE(channelCount)
        raf.writeIntLE(sampleRateHz)
        raf.writeIntLE(byteRate)
        raf.writeShortLE(blockAlign)
        raf.writeShortLE(bitDepth)
        raf.writeAscii("data")
        raf.writeIntLE(dataSize.toInt())
    }
}

private fun RandomAccessFile.writeAscii(s: String) {
    write(s.toByteArray(Charsets.US_ASCII))
}

private fun RandomAccessFile.writeIntLE(v: Int) {
    write(v and 0xFF)
    write((v shr 8) and 0xFF)
    write((v shr 16) and 0xFF)
    write((v shr 24) and 0xFF)
}

private fun RandomAccessFile.writeShortLE(v: Int) {
    write(v and 0xFF)
    write((v shr 8) and 0xFF)
}
