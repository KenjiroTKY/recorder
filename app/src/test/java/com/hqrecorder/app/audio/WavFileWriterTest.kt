package com.hqrecorder.app.audio

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Test

/** TEST_SPEC.md 2.2.3 / 2.4.3: WAVヘッダ生成、24bit変換時のクリッピング、再生時間計算の検証。 */
class WavFileWriterTest {

    @Test
    fun bytesWritten_startsAtHeaderSizeOnly() {
        val file = File.createTempFile("wav_test", ".wav")
        file.deleteOnExit()
        val writer = WavFileWriter(bitDepth = 16)
        writer.start(file.absolutePath, sampleRateHz = 44_100, channelCount = 2)

        assertEquals(44L, writer.bytesWritten())

        writer.stop()
    }

    @Test
    fun stop_writesValidStereo16BitHeader() {
        val file = File.createTempFile("wav_test", ".wav")
        file.deleteOnExit()
        val writer = WavFileWriter(bitDepth = 16)
        writer.start(file.absolutePath, sampleRateHz = 44_100, channelCount = 2)

        // 2 stereo frames = 4 samples
        writer.writeShortFrame(shortArrayOf(100, -100, 200, -200), frameCount = 2)
        writer.stop()

        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(44)
            raf.readFully(header)

            assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
            assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
            assertEquals("fmt ", String(header, 12, 4, Charsets.US_ASCII))
            assertEquals(16, readIntLE(header, 16)) // fmt chunk size
            assertEquals(1, readShortLE(header, 20)) // PCM
            assertEquals(2, readShortLE(header, 22)) // channels
            assertEquals(44_100, readIntLE(header, 24)) // sample rate
            assertEquals(44_100 * 2 * 2, readIntLE(header, 28)) // byte rate
            assertEquals(2 * 2, readShortLE(header, 32)) // block align
            assertEquals(16, readShortLE(header, 34)) // bits per sample
            assertEquals("data", String(header, 36, 4, Charsets.US_ASCII))
            assertEquals(4 * 2, readIntLE(header, 40)) // data size = 4 samples * 2 bytes
        }
    }

    @Test
    fun stop_computesDurationFromWrittenBytes() {
        val file = File.createTempFile("wav_test", ".wav")
        file.deleteOnExit()
        val writer = WavFileWriter(bitDepth = 16)
        // mono, 1000Hz, 16bit => 2 bytes/sample/frame; 500 frames = 500ms
        writer.start(file.absolutePath, sampleRateHz = 1_000, channelCount = 1)
        writer.writeShortFrame(ShortArray(500), frameCount = 500)

        val result = writer.stop()

        assertEquals(500L, result.durationMs)
        assertEquals(44L + 500 * 2, result.fileSizeBytes)
    }

    @Test
    fun writeFloatFrame_24bit_clampsOutOfRangeSamples() {
        val file = File.createTempFile("wav_test", ".wav")
        file.deleteOnExit()
        val writer = WavFileWriter(bitDepth = 24)
        writer.start(file.absolutePath, sampleRateHz = 48_000, channelCount = 1)

        // 2.0f/-2.0f exceed [-1,1] and must clamp to full scale before quantization
        writer.writeFloatFrame(floatArrayOf(2.0f, -2.0f, 0.5f, -0.5f), frameCount = 4)
        writer.stop()

        RandomAccessFile(file, "r").use { raf ->
            raf.seek(44)
            val samples = IntArray(4) { read24LE(raf) }
            assertEquals(8_388_607, samples[0])
            assertEquals(-8_388_607, samples[1])
            assertEquals(4_194_303, samples[2])
            assertEquals(-4_194_303, samples[3])
        }
    }

    private fun readIntLE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readShortLE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun read24LE(raf: RandomAccessFile): Int {
        val b0 = raf.readUnsignedByte()
        val b1 = raf.readUnsignedByte()
        val b2 = raf.readUnsignedByte()
        val raw = b0 or (b1 shl 8) or (b2 shl 16)
        return if (raw and 0x800000 != 0) raw - 0x1000000 else raw
    }
}
