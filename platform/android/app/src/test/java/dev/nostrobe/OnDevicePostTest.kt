package dev.nostrobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDevicePostTest {

    @Test
    fun constantLumaGivesUnityGain() {
        val means = DoubleArray(30) { 100.0 }
        val gains = OnDevicePost.temporalGain(means, window = 15, maxGain = 4.0)
        assertEquals(30, gains.size)
        for (g in gains) {
            assertEquals(1.0, g, 1e-6)
        }
    }

    @Test
    fun alternatingPumpingIsCorrectedTowardsMean() {
        // Alternating frames (120, 80, 120, 80...), mean is 100.
        val means = DoubleArray(20) { i -> if (i % 2 == 0) 120.0 else 80.0 }
        val gains = OnDevicePost.temporalGain(means, window = 15, maxGain = 4.0)

        // Middle frames have a symmetrical window with mean ~100.0
        val mid = 10
        val correctedBright = means[mid] * gains[mid]
        val correctedDim = means[mid + 1] * gains[mid + 1]

        // Both corrected frames should be close to 100
        assertEquals(100.0, correctedBright, 5.0)
        assertEquals(100.0, correctedDim, 5.0)
        assertTrue("Bright frame should be scaled down", gains[mid] < 1.0)
        assertTrue("Dim frame should be scaled up", gains[mid + 1] > 1.0)
    }

    @Test
    fun maxGainCapsExtremeDeviations() {
        val means = DoubleArray(15) { 100.0 }
        means[7] = 5.0 // Very dim drop
        val gains = OnDevicePost.temporalGain(means, window = 15, maxGain = 4.0)
        assertTrue("Gain must not exceed maxGain (4.0)", gains[7] <= 4.0 + 1e-6)
    }

    @Test
    fun zeroOrNegativeLumaHandledGracefully() {
        val means = doubleArrayOf(0.0, 100.0, 0.0)
        val gains = OnDevicePost.temporalGain(means, window = 3, maxGain = 4.0)
        assertEquals(3, gains.size)
        assertEquals(1.0, gains[0], 1e-6)
    }
}
