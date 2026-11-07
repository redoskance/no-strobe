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
     */
    fun pick(
        ranges: List<FpsRange>,
        sizesFor: (FpsRange) -> List<Size>,
        hz: Double,
        maxHigh: Int = 1080,
        budgetUsIso: Double = 0.0,
        maxIso: Double = 3200.0,
        minConfidence: Double = 0.75,
        confidence: Double = 1.0
    ): Triple<Int, Int, Int>? {
        if (confidence < minConfidence) return null
        val fixed = ranges.filter { it.lower == it.upper && it.upper >= 100 && it.upper <= 0.9 * hz }
            .sortedByDescending { it.upper }
        for (r in fixed) {
            if (budgetUsIso > 0.0 && maxIso > 0.0) {
                val (_, expUs) = exposureFor(1e6 / r.upper, hz)
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
     * The longest exposure that is a whole number of flicker periods and fits the frame:
     * `k = floor(0.9 x frame time x f)`, `e = k / f` microseconds.
     */
    fun exposureFor(frameUs: Double, hz: Double): Pair<Double, Double> {
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
