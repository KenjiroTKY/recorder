package com.hqrecorder.app.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/** TEST_SPEC.md 2.4.4: 長時間録音でファイル分割された各パートの合算ロジックの検証。 */
class AudioFileWriterAggregationTest {

    @Test
    fun totalDurationAndSize_sumAcrossParts() {
        val parts = listOf(
            AudioFileWriterResult(filePath = "part1", durationMs = 1_000, fileSizeBytes = 500),
            AudioFileWriterResult(filePath = "part2", durationMs = 2_000, fileSizeBytes = 700),
            AudioFileWriterResult(filePath = "part3", durationMs = 3_500, fileSizeBytes = 300)
        )

        assertEquals(6_500L, parts.totalDurationMs())
        assertEquals(1_500L, parts.totalSizeBytes())
    }

    @Test
    fun totalDurationAndSize_emptyListIsZero() {
        val parts = emptyList<AudioFileWriterResult>()

        assertEquals(0L, parts.totalDurationMs())
        assertEquals(0L, parts.totalSizeBytes())
    }
}
