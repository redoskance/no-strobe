package dev.nostrobe

import org.junit.Assert.assertEquals
import org.junit.Test

class BurstPickTest {
    private fun f(light: Double, banding: Double) = BurstPick.Frame(light, banding)

    @Test
    fun skipsTheFramesAStrobePulseHit() {
        // Frames 1 and 3 are 2.4x brighter (a flash inside the exposure): never chosen,
        // even though frame 1 has the least banding of all.
        val frames = listOf(f(1.00, 0.050), f(2.50, 0.010), f(1.05, 0.030), f(2.40, 0.200))
        assertEquals(2, BurstPick.pick(frames))
    }

    @Test
    fun steadyLightPicksTheLeastBanded() {
        val frames = listOf(f(1.00, 0.040), f(1.02, 0.010), f(0.99, 0.030))
        assertEquals(1, BurstPick.pick(frames))
    }

    @Test
    fun darkestFrameCountsEvenIfItIsBanded() {
        // Only one frame is outside the flash: it is kept whatever its banding.
        val frames = listOf(f(3.0, 0.001), f(1.0, 0.300), f(3.1, 0.002))
        assertEquals(1, BurstPick.pick(frames))
    }

    @Test
    fun emptyAndSingleListsAreSafe() {
        assertEquals(0, BurstPick.pick(emptyList()))
        assertEquals(0, BurstPick.pick(listOf(f(1.0, 0.0))))
    }

    @Test
    fun bandingIsZeroForAFlatProfileAndGrowsWithBands() {
        val flat = DoubleArray(120) { 100.0 }
        val banded = DoubleArray(120) { if ((it / 6) % 2 == 0) 90.0 else 110.0 }
        assertEquals(0.0, BurstPick.banding(flat, 100.0), 1e-9)
        assert(BurstPick.banding(banded, 100.0) > 0.05)
    }

    @Test
    fun framesWithDifferentExposuresAreNotComparedOnLight() {
        // The exposure changed during the burst (gain 1.0 -> 4.2): a dark frame is the long one, not an
        // unflashed one. Only the banding decides.
        val frames = listOf(
            BurstPick.Frame(1.00, 0.050, gain = 1.0),
            BurstPick.Frame(0.15, 0.030, gain = 4.2),
            BurstPick.Frame(0.14, 0.020, gain = 4.2),
        )
        assertEquals(false, BurstPick.comparable(frames))
        assertEquals(2, BurstPick.pick(frames))
    }

    @Test
    fun undecodableFrameIsNeverPickedWhileAValidOneExists() {
        // An undecodable JPEG is 0 light and 0 banding: it would win on both. It must lose.
        val bad = BurstPick.Frame(0.0, 0.0, valid = false)
        assertEquals(2, BurstPick.pick(listOf(bad, f(1.0, 0.05), f(1.02, 0.01))))
        assertEquals(0, BurstPick.pick(listOf(f(1.0, 0.05), bad)))
        assertEquals(0, BurstPick.pick(listOf(bad, bad)))
    }

    @Test
    fun unknownGainIsNotComparable() {
        val frames = listOf(BurstPick.Frame(1.0, 0.05, gain = 0.0), BurstPick.Frame(2.5, 0.01, gain = 0.0))
        assertEquals(false, BurstPick.comparable(frames))
        assertEquals(1, BurstPick.pick(frames))
    }
}
