package com.hqrecorder.app.time

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockReliabilityCheckerTest {

    @Test
    fun `null offset is unknown`() {
        assertEquals(ClockReliability.UNKNOWN, ClockReliabilityChecker.classify(null))
    }

    @Test
    fun `offset within threshold is reliable`() {
        assertEquals(ClockReliability.RELIABLE, ClockReliabilityChecker.classify(500, thresholdMs = 2000))
        assertEquals(ClockReliability.RELIABLE, ClockReliabilityChecker.classify(-2000, thresholdMs = 2000))
        assertEquals(ClockReliability.RELIABLE, ClockReliabilityChecker.classify(0, thresholdMs = 2000))
    }

    @Test
    fun `offset beyond threshold is unverified`() {
        assertEquals(ClockReliability.UNVERIFIED, ClockReliabilityChecker.classify(2001, thresholdMs = 2000))
        assertEquals(ClockReliability.UNVERIFIED, ClockReliabilityChecker.classify(-5000, thresholdMs = 2000))
    }
}
