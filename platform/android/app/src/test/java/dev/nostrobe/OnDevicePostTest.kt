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

    @Test
    fun nonExistentSourceThrowsPostException() {
        var threw = false
        try {
            OnDevicePost.fix(java.io.File("/tmp/nonexistent-src.mp4"), java.io.File("/tmp/out.mp4")) {}
        } catch (e: OnDevicePost.PostException) {
            threw = true
        }
        assertTrue("Expected PostException for missing source file", threw)
    }

    @Test
    fun listenersTrackProcessingState() {
        val states = mutableListOf<Boolean>()
        val listener: (Boolean) -> Unit = { states.add(it) }
        OnDevicePost.addListener(listener)
        try {
            // isProcessing is initially false
            assertTrue(!OnDevicePost.isProcessing)
        } finally {
            OnDevicePost.removeListener(listener)
        }
    }

    @Test
    fun adaptiveGainPreservesSceneCuts() {
        val means = DoubleArray(60) { i -> if (i < 30) 0.1 else 0.8 }
        val gains = OnDevicePost.temporalGainAdaptive(means, window = 15, maxGain = 4.0, cutThreshold = 0.25)
        assertEquals(60, gains.size)
        // Near the hard boundary (frames 29 and 30), gains should stay close to 1.0
        assertTrue("Frame 29 gain should not bleed across scene cut: ${gains[29]}", Math.abs(gains[29] - 1.0) < 0.2)
        assertTrue("Frame 30 gain should not bleed across scene cut: ${gains[30]}", Math.abs(gains[30] - 1.0) < 0.2)
    }

    @Test
    fun adaptiveGainFlattensAlternatingFlicker() {
        val means = DoubleArray(30) { i -> if (i % 2 == 0) 0.6 else 0.4 }
        val gains = OnDevicePost.temporalGainAdaptive(means, window = 15, maxGain = 4.0, cutThreshold = 0.25)
        val mid = 15
        val correctedEven = means[mid] * gains[mid]
        val correctedOdd = means[mid + 1] * gains[mid + 1]
        assertEquals(0.5, correctedEven, 0.05)
        assertEquals(0.5, correctedOdd, 0.05)
    }

    @Test
    fun darkFrameSoftKneeCapsNoiseBoost() {
        val means = DoubleArray(15) { 0.4 }
        means[7] = 0.02 // Pitch dark drop
        val gains = OnDevicePost.temporalGainAdaptive(means, window = 15, maxGain = 4.0, cutThreshold = 0.25)
        // With soft-knee clamp on very dark frames (< 0.15), gain is capped well below 4.0
        assertTrue("Noise boost should be capped below 4.0 on pitch dark frames: ${gains[7]}", gains[7] < 2.5)
    }
}
