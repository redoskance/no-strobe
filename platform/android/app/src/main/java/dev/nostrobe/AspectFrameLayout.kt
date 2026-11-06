package dev.nostrobe

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * A FrameLayout that keeps the viewfinder's aspect ratio inside the space it is given: 16:9 when
 * the screen is rotated into landscape (rotation 90/270), 9:16 when upright (rotation 0/180).
 * The camera frame is only scaled into the box, so a box with the frame's own
 * ratio shows it undistorted.
 */
class AspectFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private var aspectWidth = 16
    private var aspectHeight = 9

    /**
     * Sets the camera stream or video aspect ratio (e.g. 1920x1080 or 16:9).
     */
    fun setAspectRatio(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val w = maxOf(width, height)
        val h = minOf(width, height)
        if (aspectWidth != w || aspectHeight != h) {
            aspectWidth = w
            aspectHeight = h
            requestLayout()
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val maxW = MeasureSpec.getSize(widthSpec)
        val maxH = MeasureSpec.getSize(heightSpec)
        if (maxW <= 0 || maxH <= 0) {
            super.onMeasure(widthSpec, heightSpec)
            return
        }
        @Suppress("DEPRECATION")
        val displayRotation = (context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager)
            ?.defaultDisplay?.rotation ?: android.view.Surface.ROTATION_0
        val isLandscape = displayRotation == android.view.Surface.ROTATION_90 ||
                          displayRotation == android.view.Surface.ROTATION_270
        val targetRatio = aspectWidth.toFloat() / aspectHeight.toFloat()
        // 16:9 when rotated sideways, 9:16 when held upright (the camera sensor is mounted at 90°).
        val ratio = if (isLandscape) targetRatio else (1f / targetRatio)  // width / height
        var w = maxW
        var h = (maxW / ratio).toInt()
        if (h > maxH) {
            h = maxH
            w = (maxH * ratio).toInt()
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
        )
    }
}
