package dev.nostrobe

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * A FrameLayout that keeps the viewfinder's aspect ratio inside the space it is given. Whether the box
 * is 16:9 or 9:16 depends on how the camera frame is turned to reach the screen: the orientation of
 * the sensor relative to the display (see [setRelativeOrientation]); 90 and 270 turn a landscape frame
 * into a portrait one. Until that is known the sensor is assumed mounted at 90 degrees, as on a phone.
 * The camera frame is only scaled into the box, so a box with the frame's own
 * ratio shows it undistorted.
 */
class AspectFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private var aspectWidth = 16
    private var aspectHeight = 9
    private var relativeOrientation = -1

    /** The share of the height the box may take: below 1 when the controls are stacked under it. */
    var maxHeightShare = 1f
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    /**
     * The camera image's orientation relative to the screen, in degrees (sensor orientation minus the
     * display rotation for a back camera, plus it for a front one). A tablet whose sensor is mounted
     * at 0 shows a landscape frame on its landscape screen; the phone assumption said portrait.
     */
    fun setRelativeOrientation(degrees: Int) {
        if (degrees != relativeOrientation) {
            relativeOrientation = degrees
            requestLayout()
        }
    }

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
        val maxH = (MeasureSpec.getSize(heightSpec) * maxHeightShare).toInt()
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
        // The frame is shown turned by a quarter when the sensor is mounted at 90 or 270 relative to the screen
        // (a phone held upright: 9:16), straight otherwise (16:9).
        val turned = if (relativeOrientation >= 0) relativeOrientation % 180 == 90 else !isLandscape
        val ratio = if (turned) (1f / targetRatio) else targetRatio  // width / height
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
