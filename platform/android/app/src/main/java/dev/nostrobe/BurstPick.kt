package dev.nostrobe

import android.graphics.BitmapFactory
import kotlin.math.sqrt

/**
 * Picks the best still of a burst taken under a flickering or strobing light.
 *
 * A frame hit by a strobe pulse is brighter than its neighbours at the same exposure and ISO. The burst
 * therefore runs at a FIXED exposure (the auto-exposure is locked), and the light of a frame is its raw
 * mean luma: a JPEG's luma is already normalised by the auto-exposure, so dividing by exposure x ISO
 * (the first version did) turns an exposure change into a light change and keeps the longest, blurriest
 * frame. The frames within [LIGHT_BAND] of the darkest one are the frames the pulses missed; among them
 * the one with the least row banding wins (a flicker the exposure did not cancel shows as bands).
 * When the capture results show that the exposure was NOT constant across the burst, the light levels
 * cannot be compared and the least banded frame is kept.
 */
object BurstPick {
    /** Frames at most this much brighter than the darkest one count as not flashed. */
    const val LIGHT_BAND = 1.15

    class Frame(
        /** Mean luma of the decoded frame. */
        val light: Double,
        /** Row-to-row luma variation relative to the mean (0 = none). */
        val banding: Double,
        /** Exposure (ms) x ISO / 100 the sensor reported for the frame; 0 when unknown. */
        val gain: Double = 1.0,
    )

    /** The exposure x ISO of the burst stayed within this ratio: its light levels can be compared. */
    const val GAIN_TOLERANCE = 1.15

    /** Index of the frame to keep (0 when the list is empty or has one frame). */
    fun pick(frames: List<Frame>): Int {
        if (frames.size <= 1) return 0
        if (!comparable(frames)) return frames.indices.minByOrNull { frames[it].banding } ?: 0
        val darkest = frames.minOf { it.light }
        val candidates = frames.indices.filter { frames[it].light <= darkest * LIGHT_BAND }
        return candidates.minByOrNull { frames[it].banding } ?: 0
    }

    /** True when every frame has a known gain and the gains agree within [GAIN_TOLERANCE]. */
    fun comparable(frames: List<Frame>): Boolean {
        if (frames.any { it.gain <= 0.0 }) return false
        return frames.maxOf { it.gain } / frames.minOf { it.gain } <= GAIN_TOLERANCE
    }

    /**
     * Light level and banding of one JPEG, from a 1/8 decode. The decoded rows are the sensor rows
     * (the EXIF rotation is not applied), which is the direction a rolling shutter bands along.
     */
    fun analyse(jpeg: ByteArray, exposureNs: Long, iso: Int): Frame {
        val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return Frame(0.0, 0.0)
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        val rows = DoubleArray(h)
        for (y in 0 until h) {
            var s = 0.0
            for (x in 0 until w) {
                val p = px[y * w + x]
                s += 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
            }
            rows[y] = s / w
        }
        val mean = rows.average()
        val gain = (exposureNs / 1e6) * (iso / 100.0)
        return Frame(mean, banding(rows, mean), gain)
    }

    /** Standard deviation of the row means around their moving average, relative to the mean. */
    fun banding(rows: DoubleArray, mean: Double): Double {
        if (rows.size < 8 || mean <= 1e-9) return 0.0
        val win = (rows.size / 6).coerceAtLeast(3)
        var acc = 0.0
        var n = 0
        for (y in rows.indices) {
            val lo = (y - win / 2).coerceAtLeast(0)
            val hi = (y + win / 2).coerceAtMost(rows.size - 1)
            var s = 0.0
            for (i in lo..hi) s += rows[i]
            val d = rows[y] - s / (hi - lo + 1)
            acc += d * d
            n++
        }
        return sqrt(acc / n) / mean
    }
}
