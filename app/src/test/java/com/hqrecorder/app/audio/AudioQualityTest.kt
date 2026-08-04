package com.hqrecorder.app.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/** TEST_SPEC.md 2.2.1: プリセットごとのフォーマット・サンプルレート・bit数/ビットレートの検証。 */
class AudioQualityTest {

    @Test
    fun highWav_hasExpectedParameters() {
        val q = AudioQuality.HIGH_WAV
        assertEquals(AudioFormatType.WAV, q.formatType)
        assertEquals(48_000, q.sampleRateHz)
        assertEquals(24, q.bitDepth)
        assertEquals("wav", q.fileExtension)
        assertEquals("WAV48-24", q.qualityTag)
    }

    @Test
    fun standardWav_hasExpectedParameters() {
        val q = AudioQuality.STANDARD_WAV
        assertEquals(AudioFormatType.WAV, q.formatType)
        assertEquals(44_100, q.sampleRateHz)
        assertEquals(16, q.bitDepth)
        assertEquals("wav", q.fileExtension)
        assertEquals("WAV44-16", q.qualityTag)
    }

    @Test
    fun longAac_hasExpectedParameters() {
        val q = AudioQuality.LONG_AAC
        assertEquals(AudioFormatType.AAC, q.formatType)
        assertEquals(128_000, q.aacBitrateBps)
        assertEquals("m4a", q.fileExtension)
        assertEquals("AAC128", q.qualityTag)
    }

    @Test
    fun voiceAac_hasExpectedParameters() {
        val q = AudioQuality.VOICE_AAC
        assertEquals(AudioFormatType.AAC, q.formatType)
        assertEquals(64_000, q.aacBitrateBps)
        assertEquals("m4a", q.fileExtension)
        assertEquals("AAC64", q.qualityTag)
    }

    @Test
    fun presets_containsAllFourInOrder() {
        assertEquals(
            listOf(
                AudioQuality.HIGH_WAV,
                AudioQuality.STANDARD_WAV,
                AudioQuality.LONG_AAC,
                AudioQuality.VOICE_AAC
            ),
            AudioQuality.presets
        )
    }
}
