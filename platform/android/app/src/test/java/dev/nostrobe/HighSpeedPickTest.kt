package dev.nostrobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The frame rate the flicker allows, and the exposure that fits it. No camera needed. */
class HighSpeedPickTest {
    private val sizes = mapOf(
        HighSpeedPick.FpsRange(240, 240) to listOf(
            HighSpeedPick.Size(3840, 2160), HighSpeedPick.Size(1920, 1080),
            HighSpeedPick.Size(1280, 720)),
        HighSpeedPick.FpsRange(120, 120) to listOf(
            HighSpeedPick.Size(1920, 1080), HighSpeedPick.Size(1280, 720)),
        HighSpeedPick.FpsRange(100, 100) to listOf(HighSpeedPick.Size(640, 480)),
        HighSpeedPick.FpsRange(60, 60) to listOf(HighSpeedPick.Size(1920, 1080))
    )
    private val ranges = sizes.keys.toList() + listOf(HighSpeedPick.FpsRange(30, 60))

    private fun pick(hz: Double) =
        HighSpeedPick.pick(ranges, { r -> sizes[r] ?: emptyList() }, hz)

    @Test fun fastestRateThatFitsTheFlicker() {
        // 500 Hz: 0.9 x 500 = 450, so 240 fps qualifies and is the fastest.
        assertEquals(Triple(240, 1920, 1080), pick(500.0))
    }

    @Test fun rateFollowsTheFlicker() {
        assertEquals(Triple(120, 1920, 1080), pick(200.0))  // 180 max, 240 is over
        assertEquals(Triple(100, 640, 480), pick(120.0))    // 108 max
    }

    @Test fun slowFlickerHasNoHighSpeedRate() {
        // 100 Hz: 0.9 x 100 = 90, under the 100 fps floor.
        assertNull(pick(100.0))
        assertNull(pick(0.0))
    }

    @Test fun variableRangesAreNotFixedRates() {
        // 30-60 is a range, not a fixed rate: never picked.
        assertEquals(Triple(100, 640, 480), HighSpeedPick.pick(
            listOf(HighSpeedPick.FpsRange(30, 60), HighSpeedPick.FpsRange(100, 100)),
            { r -> sizes[r] ?: emptyList() }, 120.0))
    }

    @Test fun sizeUnderTheHeightCap() {
        // 2160 rows is over the cap, so 1080p wins for 240 fps.
        assertEquals(Triple(240, 1920, 1080), pick(500.0))
    }

    @Test fun rateWithoutAQualifyingSizeFallsToTheNext() {
        val noBig = mapOf(
            HighSpeedPick.FpsRange(240, 240) to listOf(HighSpeedPick.Size(3840, 2160)),
            HighSpeedPick.FpsRange(120, 120) to listOf(HighSpeedPick.Size(1920, 1080)))
        assertEquals(Triple(120, 1920, 1080), HighSpeedPick.pick(
            noBig.keys.toList(), { r -> noBig[r] ?: emptyList() }, 500.0))
    }

    @Test fun exposureIsWholePeriodsFittingTheFrame() {
        // 240 fps frame (4166.7 us) at 500 Hz: k = floor(0.9 x 4166.7 x 500 / 1e6) = 1, e = 2000 us.
        val (k1, e1) = HighSpeedPick.exposureFor(1e6 / 240.0, 500.0)
        assertEquals(1.0, k1, 1e-9)
        assertEquals(2000.0, e1, 1e-9)
        // 30 fps frame (33333.3 us) at 100 Hz: k = floor(0.9 x 33333.3 x 100 / 1e6) = 3, e = 30000 us.
        val (k2, e2) = HighSpeedPick.exposureFor(1e6 / 30.0, 100.0)
        assertEquals(3.0, k2, 1e-9)
        assertEquals(30000.0, e2, 1e-9)
    }

    @Test fun lowLightSafeguardAvoidsNoisyRates() {
        // At 500 Hz:
        // 240 fps exposure is 2000 us (k=1).
        // 120 fps exposure: frameUs = 8333.3 us, k = floor(0.9 * 8333.3 * 500 / 1e6) = 3 -> e = 6000 us.
        // If budget = 12_000_000:
        // For 240 fps: reqIso = 12_000_000 / 2000 = 6000 > 3200 -> rejected!
        // For 120 fps: reqIso = 12_000_000 / 6000 = 2000 <= 3200 -> accepted!
        val picked = HighSpeedPick.pick(
            ranges, { r -> sizes[r] ?: emptyList() }, 500.0,
            budgetUsIso = 12_000_000.0, maxIso = 3200.0
        )
        assertEquals(Triple(120, 1920, 1080), picked)
    }

    @Test fun pitchDarkRejectsAllHighSpeedRates() {
        // In very dim lighting, even 100 fps would exceed maxIso.
        // High-speed mode should not engage, preserving image quality by falling back to 30/60 fps with large k.
        val picked = HighSpeedPick.pick(
            ranges, { r -> sizes[r] ?: emptyList() }, 500.0,
            budgetUsIso = 50_000_000.0, maxIso = 3200.0
        )
        assertNull(picked)
    }

    @Test fun weakConfidenceRejectsHighSpeed() {
        // High-speed runs open-loop without analysis stream, requiring confidence >= 0.75.
        val picked = HighSpeedPick.pick(
            ranges, { r -> sizes[r] ?: emptyList() }, 500.0,
            confidence = 0.50
        )
        assertNull(picked)

        val strong = HighSpeedPick.pick(
            ranges, { r -> sizes[r] ?: emptyList() }, 500.0,
            confidence = 0.85
        )
        assertEquals(Triple(240, 1920, 1080), strong)
    }

    @Test fun playbackFpsRealTimeByDefault() {
        // Real-time by default: playback framerate matches capture rate (1.0x speed).
        assertEquals(240, HighSpeedPick.playbackFps(240, slowMo = false))
        assertEquals(120, HighSpeedPick.playbackFps(120, slowMo = false))
        assertEquals(60, HighSpeedPick.playbackFps(60, slowMo = false))
    }

    @Test fun playbackFpsSlowMotionWhenToggled() {
        // Slow-motion toggle: playback container framerate set to 30 fps (e.g. 8x or 4x slow-mo).
        assertEquals(30, HighSpeedPick.playbackFps(240, slowMo = true))
        assertEquals(30, HighSpeedPick.playbackFps(120, slowMo = true))
        assertEquals(30, HighSpeedPick.playbackFps(60, slowMo = true))
    }

    @Test fun strobeAllowsHighSpeedAtLowFrequency() {
        // In strobe mode (isStrobe = true or hz in 1..60), high-speed rates are allowed to isolate dark gap.
        val strobePicked = HighSpeedPick.pick(
            ranges, { r -> sizes[r] ?: emptyList() }, 10.0,
            isStrobe = true
        )
        assertEquals(Triple(240, 1920, 1080), strobePicked)
    }

    @Test fun strobeExposureFitsInsideFrameTime() {
        // At 240 fps (frameUs = 4166.7 us), 10 Hz strobe:
        // Strobe exposure is 0.9 * frameUs = 3750 us, leaving a 10% safety margin per frame
        val (k, expUs) = HighSpeedPick.exposureFor(1e6 / 240.0, 10.0, isStrobe = true)
        assertEquals(1.0, k, 1e-6)
        assertEquals(3750.0, expUs, 1.0)
    }
}
