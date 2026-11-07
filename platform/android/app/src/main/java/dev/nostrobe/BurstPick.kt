package dev.nostrobe

import android.graphics.BitmapFactory
import kotlin.math.sqrt

/**
 * Picks the best still of a burst taken under a flickering or strobing light.
 *
 * A frame hit by a strobe pulse is brighter than its neighbours at the same exposure and ISO, so
 * the light level of every frame is its mean luma divided by what the sensor was asked to collect
 * (exposure x ISO, from the capture result). The frames within [LIGHT_BAND] of the darkest one are
 * the frames the pulses missed; among them the one with the least row banding wins (a flicker that
 * the exposure did not cancel shows as horizontal bands).
 */
object BurstPick {
    /** Frames at most this much brighter than the darkest one count as not flashed. */
    const val LIGHT_BAND = 1.15

    class Frame(
        /** Mean luma / (exposure x ISO): comparable between frames taken with different settings. */
        val light: Double,
        /** Row-to-row luma variation relative to the mean (0 = none). */
        val banding: Double,
    )

    /** Index of the frame to keep (0 when the list is empty or has one frame). */
    fun pick(frames: List<Frame>): Int {
        if (frames.size <= 1) return 0
        val darkest = frames.minOf { it.light }
        val candidates = frames.indices.filter { frames[it].light <= darkest * LIGHT_BAND }
        return candidates.minByOrNull { frames[it].banding } ?: 0
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
        val light = if (gain > 1e-9) mean / gain else mean
        return Frame(light, banding(rows, mean))
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
