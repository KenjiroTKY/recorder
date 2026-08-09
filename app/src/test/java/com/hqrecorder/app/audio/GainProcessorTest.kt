package com.hqrecorder.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/** TEST_SPEC.md 2.8: 録音感度（ゲイン）調整のGainProcessorに対するユニットテスト。 */
class GainProcessorTest {

    @Test
    fun zeroDb_short_isIdentity() {
        val buffer = shortArrayOf(100, -200, 32767, -32768)
        val original = buffer.copyOf()
        val clipped = GainProcessor.applyGainShort(buffer, buffer.size, 0f)
        assertFalse(clipped)
        assertEquals(original.toList(), buffer.toList())
    }

    @Test
    fun zeroDb_float_isIdentity() {
        val buffer = floatArrayOf(0.1f, -0.2f, 1.0f, -1.0f)
        val original = buffer.copyOf()
        val clipped = GainProcessor.applyGainFloat(buffer, buffer.size, 0f)
        assertFalse(clipped)
        assertEquals(original.toList(), buffer.toList())
    }

    @Test
    fun linearFactor_matchesDbToLinearFormula() {
        // 10^(db/20): +6dB≒x2.0, -6dB≒x0.5
        assertEquals(1.995f, GainProcessor.linearFactor(6f), 0.01f)
        assertEquals(0.501f, GainProcessor.linearFactor(-6f), 0.01f)
        assertEquals(1f, GainProcessor.linearFactor(0f), 0.0001f)
    }

    @Test
    fun positiveGain_short_scalesWithoutClipping() {
        val buffer = shortArrayOf(1000, -1000)
        val clipped = GainProcessor.applyGainShort(buffer, buffer.size, 6f)
        assertFalse(clipped)
        val expectedFactor = GainProcessor.linearFactor(6f)
        assertEquals((1000 * expectedFactor).roundToInt(), buffer[0].toInt())
        assertEquals((-1000 * expectedFactor).roundToInt(), buffer[1].toInt())
    }

    @Test
    fun highGain_short_hardClipsWithoutWrapAround() {
        val buffer = shortArrayOf(32000, -32000)
        val clipped = GainProcessor.applyGainShort(buffer, buffer.size, 12f)
        assertTrue(clipped)
        assertEquals(Short.MAX_VALUE, buffer[0])
        assertEquals(Short.MIN_VALUE, buffer[1])
    }

    @Test
    fun highGain_float_hardClipsWithoutWrapAround() {
        val buffer = floatArrayOf(0.9f, -0.9f)
        val clipped = GainProcessor.applyGainFloat(buffer, buffer.size, 12f)
        assertTrue(clipped)
        assertEquals(1f, buffer[0], 0.0001f)
        assertEquals(-1f, buffer[1], 0.0001f)
    }

    @Test
    fun clampGainDb_restrictsToGivenMax() {
        assertEquals(GainProcessor.MAX_GAIN_DB, GainProcessor.clampGainDb(40f, GainProcessor.MAX_GAIN_DB), 0.0001f)
        assertEquals(GainProcessor.MIN_GAIN_DB, GainProcessor.clampGainDb(-60f, GainProcessor.MAX_GAIN_DB), 0.0001f)
        assertEquals(3f, GainProcessor.clampGainDb(3f, GainProcessor.MAX_GAIN_DB), 0.0001f)
    }

    @Test
    fun clampGainDb_defaultMax_isUnprocessedUpperBound() {
        assertEquals(GainProcessor.MAX_GAIN_DB_UNPROCESSED, GainProcessor.clampGainDb(60f), 0.0001f)
    }

    @Test
    fun maxGainDb_unprocessedModeAllowsWiderBoostThanStandard() {
        assertEquals(GainProcessor.MAX_GAIN_DB_UNPROCESSED, GainProcessor.maxGainDb(preferUnprocessed = true), 0.0001f)
        assertEquals(GainProcessor.MAX_GAIN_DB, GainProcessor.maxGainDb(preferUnprocessed = false), 0.0001f)
        assertTrue(GainProcessor.MAX_GAIN_DB_UNPROCESSED > GainProcessor.MAX_GAIN_DB)
    }

    @Test
    fun autoReduceGainDb_notClipped_returnsCurrentValueUnchanged() {
        assertEquals(10f, GainProcessor.autoReduceGainDb(10f, clipped = false), 0.0001f)
    }

    @Test
    fun autoReduceGainDb_clipped_stepsDownByDefaultStep() {
        val reduced = GainProcessor.autoReduceGainDb(10f, clipped = true)
        assertEquals(10f - GainProcessor.AUTO_REDUCTION_STEP_DB, reduced, 0.0001f)
    }

    @Test
    fun autoReduceGainDb_clipped_usesCustomStep() {
        val reduced = GainProcessor.autoReduceGainDb(10f, clipped = true, stepDb = 1f)
        assertEquals(9f, reduced, 0.0001f)
    }

    @Test
    fun autoReduceGainDb_clipped_clampsAtMinGainDb() {
        val reduced = GainProcessor.autoReduceGainDb(GainProcessor.MIN_GAIN_DB + 1f, clipped = true)
        assertEquals(GainProcessor.MIN_GAIN_DB, reduced, 0.0001f)
    }
}
