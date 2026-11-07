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
}
