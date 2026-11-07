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

    @Test
    fun highSpeedStrobeDecimationDropsAllFlashFrames() {
        // 240 fps recording of 10 Hz strobe (period = 24 frames): 240 frames total (1 sec).
        // In each 24-frame period: frames 0 and 1 are white flashes (0.90), frames 2..23 are dark gap (0.25).
        val n = 240
        val means = DoubleArray(n) { i ->
            if (i % 24 in 0..1) 0.90 else 0.25
        }
        val timestampsUs = LongArray(n) { i -> (i * 1_000_000L / 240) }

        val plan = OnDevicePost.planDarkGapDecimation(means, timestampsUs, srcFps = 240)

        assertTrue("Strobe should be detected and decimated", plan.isStrobeDecimated)
        assertEquals("Target framerate should be 60 fps", 60, plan.outputFps)
        assertEquals("20 flash frames should be detected (2 per period x 10 periods)", 20, plan.flashCount)
        assertEquals("220 dark frames should be detected", 220, plan.darkCount)

        // Verify that NO selected frame is a flash frame
        for (srcIdx in plan.selectedSourceIndices) {
            assertTrue("Selected frame $srcIdx must NOT be a flash frame!", srcIdx % 24 !in 0..1)
            assertEquals("Selected frame $srcIdx luma should be dark baseline", 0.25, means[srcIdx], 1e-6)
        }
    }

    @Test
    fun standardRateStrobeSubstitutesFlashFramesWithDarkFrames() {
        // 30 fps recording of 10 Hz strobe: 60 frames (2 sec).
        // Every 3 frames: frame 0 is flash (0.85), frames 1 and 2 are dark (0.25).
        val n = 60
        val means = DoubleArray(n) { i ->
            if (i % 3 == 0) 0.85 else 0.25
        }
        val timestampsUs = LongArray(n) { i -> (i * 1_000_000L / 30) }

        val plan = OnDevicePost.planDarkGapDecimation(means, timestampsUs, srcFps = 30)

        assertTrue("Strobe should be detected in standard rate capture", plan.isStrobeDecimated)
        assertEquals("Output fps matches src fps (30)", 30, plan.outputFps)
        assertEquals("Output frame count matches src count", 60, plan.selectedSourceIndices.size)

        // Verify that all output slots point to clean dark frames (no flash frame encoded)
        for ((outSlot, srcIdx) in plan.selectedSourceIndices.withIndex()) {
            assertTrue("Output slot $outSlot must reference a dark frame, got frame $srcIdx", srcIdx % 3 != 0)
            assertEquals(0.25, means[srcIdx], 1e-6)
        }
    }

    @Test
    fun normalVideoWithoutStrobePreservedUntouched() {
        // Normal 30 fps video with small ambient luma variations (no strobe)
        val n = 60
        val means = DoubleArray(n) { 0.45 + (it % 3) * 0.01 }
        val timestampsUs = LongArray(n) { i -> (i * 1_000_000L / 30) }

        val plan = OnDevicePost.planDarkGapDecimation(means, timestampsUs, srcFps = 30)

        assertTrue("Non-strobe clip should NOT trigger strobe decimation", !plan.isStrobeDecimated)
        assertEquals(0, plan.flashCount)
        assertEquals(60, plan.selectedSourceIndices.size)
        for (i in 0 until n) {
            assertEquals("Index $i should map 1-to-1 in normal video", i, plan.selectedSourceIndices[i])
        }
    }
}
