package com.hqrecorder.app.audio

import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TEST_SPEC.md 2.3.3 / 2.4.3: ファイル命名規則とパート分割ファイル名の検証。 */
class RecordingFileNamingTest {

    @Test
    fun baseName_matchesNamingConventionAndEmbedsQualityTag() {
        val name = RecordingFileNaming.baseName(Date(), AudioQuality.HIGH_WAV)
        assertTrue(
            "expected yyyyMMdd_HHmmss_<qualityTag> but was $name",
            Regex("""^\d{8}_\d{6}_WAV48-24$""").matches(name)
        )
    }

    @Test
    fun partFileName_firstPartHasNoSuffix() {
        val name = RecordingFileNaming.partFileName("20260802_120000_WAV44-16", "wav", partIndex = 1)
        assertEquals("20260802_120000_WAV44-16.wav", name)
    }

    @Test
    fun partFileName_subsequentPartsAreNumbered() {
        val part2 = RecordingFileNaming.partFileName("20260802_120000_WAV44-16", "wav", partIndex = 2)
        val part3 = RecordingFileNaming.partFileName("20260802_120000_WAV44-16", "wav", partIndex = 3)
        assertEquals("20260802_120000_WAV44-16_part2.wav", part2)
        assertEquals("20260802_120000_WAV44-16_part3.wav", part3)
    }
}
