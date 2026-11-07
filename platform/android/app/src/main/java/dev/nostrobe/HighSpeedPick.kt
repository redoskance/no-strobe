package dev.nostrobe

/**
 * The frame rate a high-speed recording can use, from the camera's stream map. Paul's rule: the
 * frame must hold one flicker period (k = 1), so the rate is at most 0.9 x the flicker frequency.
 * Split out of `MainActivity.pickHighSpeed` so the choice can be tested without a camera.
 */
object HighSpeedPick {
    /** One entry of `SCALER_STREAM_CONFIGURATION_MAP.highSpeedVideoFpsRanges`. */
    data class FpsRange(val lower: Int, val upper: Int)
    /** One entry of `getHighSpeedVideoSizesFor`. */
    data class Size(val width: Int, val height: Int)

    /**
     * Fixed ranges only (`lower == upper`), at least 100 fps, at most 0.9 x `hz`, sorted by rate
     * descending. For the fastest qualifying rate, the largest size with `height <= maxHigh`; if
     * that rate has no such size, the next rate down. Returns (fps, width, height) or null.
     *
     * Low-light / noise safeguard: If `budgetUsIso > 0` and `maxIso > 0`, filters out candidate rates
     * whose required exposure would force sensor sensitivity above `maxIso` (e.g. 3200), preventing
     * severe noise degradation in dim scenes.
     *
     * Confidence check: High-speed recording runs open-loop (no analysis stream during recording),
     * so it requires high lock confidence (`confidence >= minConfidence`, default 0.75).
     *
     * Strobe mode (`isStrobe` or `hz < 60 Hz`): In slow strobe mode, higher frame rates (120/240 fps)
     * provide short exposure windows and dense sampling per cycle, isolating the dark interval
     * between flashes so that flashed frames can be cleanly dropped.
     */
    fun pick(
        ranges: List<FpsRange>,
        sizesFor: (FpsRange) -> List<Size>,
        hz: Double,
        maxHigh: Int = 1080,
        budgetUsIso: Double = 0.0,
        maxIso: Double = 3200.0,
        minConfidence: Double = 0.75,
        confidence: Double = 1.0,
        isStrobe: Boolean = false,
    ): Triple<Int, Int, Int>? {
        if (confidence < minConfidence) return null
        val strobeRegime = isStrobe || (hz in 1.0..60.0)
        val fixed = if (strobeRegime) {
            // For slow strobes: higher frame rates isolate the dark gap between flashes
            ranges.filter { it.lower == it.upper && it.upper >= 100 }
                .sortedByDescending { it.upper }
        } else {
            // In PWM mode: Paul's rule (frame duration >= 1 flicker cycle, so rate <= 0.9 * hz).
            ranges.filter { it.lower == it.upper && it.upper >= 100 && it.upper <= 0.9 * hz }
                .sortedByDescending { it.upper }
        }
        for (r in fixed) {
            if (budgetUsIso > 0.0 && maxIso > 0.0) {
                val (_, expUs) = exposureFor(1e6 / r.upper, hz, strobeRegime)
                if (expUs > 0.0) {
                    val reqIso = budgetUsIso / expUs
                    if (reqIso > maxIso) {
                        // Required ISO would cause unacceptable noise in this light; try slower rate
                        continue
                    }
                }
            }
            val size = sizesFor(r).filter { it.height <= maxHigh }
                .maxByOrNull { it.width.toLong() * it.height } ?: continue
            return Triple(r.upper, size.width, size.height)
        }
        return null
    }

    /**
     * For PWM (strobeRegime=false): The longest exposure that is a whole number of flicker periods and fits the frame:
     * `k = floor(0.9 x frame time x f)`, `e = k / f` microseconds.
     * For Strobe (strobeRegime=true): The exposure fits inside the frame time to keep the shutter short:
     * `e = 0.9 x frame time`.
     */
    fun exposureFor(frameUs: Double, hz: Double, isStrobe: Boolean = false): Pair<Double, Double> {
        if (isStrobe || (hz in 1.0..60.0)) {
            val e = (0.9 * frameUs).coerceAtLeast(100.0)
            return 1.0 to e
        }
        val k = kotlin.math.floor(0.9 * frameUs * hz / 1e6).coerceAtLeast(1.0)
        return k to k * 1e6 / hz
    }

    /**
     * Container playback framerate for a high-speed clip.
     * When `slowMo` is true: returns 30 fps (producing 4x slow-mo at 120 fps or 8x slow-mo at 240 fps).
     * When `slowMo` is false (default): returns `captureFps` (real-time 1.0x playback).
     */
    fun playbackFps(captureFps: Int, slowMo: Boolean): Int {
        return if (slowMo) 30 else captureFps
    }
}
