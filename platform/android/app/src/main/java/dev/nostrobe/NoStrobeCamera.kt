package dev.nostrobe

import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.graphics.Rect
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.media.MediaRecorder
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.io.Writer
import java.util.Locale
import android.util.Size
import android.view.Surface
import kotlin.math.abs

/** Why the camera failed (the onFailure callback of NoStrobeCamera). */
const val CAMERA_FAIL_OPEN = 1        // could not be opened (busy, disabled, unsupported)
const val CAMERA_FAIL_LOST = 2        // disconnected, or a device error while running
const val CAMERA_FAIL_NO_SESSION = 3  // the session could not be configured at all
const val CAMERA_FAIL_NO_RECORD = 4   // the session runs without the recorder surface

/**
 * Camera2 pipeline: preview + YUV analysis + recording, with the shutter driven by the
 * Rust controller.
 *
 * The repeating request is reissued only when the controller moves the shutter, so the
 * new exposure takes effect on the next frame without a request per frame.
 */
/** Debug: set by the activity from the `dump_exposures` extra before the camera is created. */
@Volatile var dumpExposuresOverride: DoubleArray? = null

class NoStrobeCamera(
    private val manager: CameraManager,
    private val cameraId: String,
    private val previewSurface: Surface,
    private val recorder: MediaRecorder?,
    private val pwmMode: Boolean,
    /** Pins one lens of a logical multi-camera; null leaves the lens choice to the phone. */
    private val physicalId: String? = null,
    private var zoomRatio: Float = 1f,
    /** Exposure compensation in EV (stops), from the exposure bar. */
    private var evBias: Float = 0f,
    /** Called once the capture session is configured and the repeating request runs. */
    private val onReady: () -> Unit = {},
    /** Debug: pretend the scene has a PWM light at this frequency (0 = real scene). */
    private val simFlickerHz: Double = 0.0,
    /** Debug: behave like a camera without manual exposure. */
    private val forceAutoOnly: Boolean = false,
    /** Debug: start the session ladder at this level (2 = no analysis stream). */
    private val startLevel: Int = 0,
    /** > 0: constrained high-speed recording at this rate, recorder surface only (no preview). */
    private val highSpeedFps: Int = 0,
    /** Debug: sweep fixed exposures and write the row profiles of each to profiles.csv (real data). */
    private val dumpProfiles: Boolean = false,
    private val outDir: java.io.File? = null,
    /** Frame rate the recorder was set to (the open-loop recording uses it). */
    private val recordFps: Int = 30,
    /** A lock found before this camera was reopened (to record): start on it, do not search. */
    private val seedExposureUs: Double = 0.0,
    private val seedHz: Double = 0.0,
    /** Recording without a lock: stay on auto-exposure, probing exposures would ruin the clip. */
    private val holdAutoNoSeed: Boolean = false,
    /** The camera failed or cannot record here: one of the CAMERA_FAIL_* codes. */
    private val onFailure: (Int) -> Unit = {},
    private val onStatus: (NsCommand) -> Unit,
) {
    @Volatile private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var strobe: SyncController? = null
    private var pwm: PwmSyncController? = null
    private var builder: android.hardware.camera2.CaptureRequest.Builder? = null
    private var last: NsCommand? = null
    private var sustainUs = 0.0
    private var lineRateHz = 0.0
    /** Row readout rate in rows/s, 0 when the device does not publish it. */
    val rowRateHz: Double get() = lineRateHz
    val currentCameraId: String get() = cameraId
    val currentPhysicalId: String? get() = activePhysicalId
    val currentSustainUs: Double get() = sustainUs
    val currentLineRateHz: Double get() = lineRateHz
    val isTimingIgnored: Boolean get() = timingIgnored
    val currentFlickerHz: Double get() = if (lockHz > 0.0) lockHz else (pwm?.frequencyHz() ?: 0.0)
    val processingTimeUs: Long get() = avgProcessUs
    private var avgProcessUs = 0L
    private var handler: Handler? = null
    private var analysisThread: HandlerThread? = null

    // The sensor pipeline is a few frames deep: after a new exposure is requested, the next
    // images still carry the old one. The controller measures residual banding per trial
    // exposure, so a frame is only fed to it if the exposure the sensor really used
    // (CaptureResult, matched by SENSOR_TIMESTAMP) is the one last requested.
    private var requestedExposureNs = 0L
    private var requestedFrameDurationNs = 0L
    private var latestResultExposureNs = 0L
    private val resultExposure = HashMap<Long, Long>()
    private var lastCaptureTs = 0L
    private var timingMismatches = 0
    private var timingIgnored = false
    private var staleFrames = 0
    private var lastAfState: Int? = null
    private var lastFocusDiopters = -1f
    @Volatile private var stopped = false
    @Volatile private var reportedLoss = false
    // The device cannot (or does not) do manual exposure: the camera stays on auto-exposure
    // and the app only records; the clip is fixed afterwards.
    private var autoOnly = false

    // No flicker found: do not leave the sensor on short exposures and a maxed ISO (a dark,
    // noisy clip). After PROBE_MS without a lock the camera goes back to auto-exposure for
    // REST_MS, then probes again (the lights may have changed).
    private var surfaceList: List<Surface> = emptyList()
    private var fallbackSurfaces: List<Surface> = emptyList()
    /** The lock in force right now (0 when searching), to carry over when the camera is reopened. */
    @Volatile private var lockExposureUs = 0.0
    @Volatile private var lockHz = 0.0
    @Volatile private var lockFps = 0.0
    @Volatile private var lockAtMs = 0L

    /** (exposure us, frequency Hz) of a lock seen in the last seconds, else (0, 0). A status of
     *  "search" is interleaved with "locked" during re-checks, so the instant is not enough. */
    fun carryLock(): Triple<Double, Double, Double> =
        if (lockHz > 0.0 && SystemClock.elapsedRealtime() - lockAtMs < LOCK_GRACE_MS) Triple(lockExposureUs, lockHz, lockFps)
        else Triple(0.0, 0.0, 0.0)
    // The lens actually pinned by the session that is running (null: the logical camera).
    private var activePhysicalId: String? = null
    private var analysisSurface: Surface? = null
    private var photoReader: ImageReader? = null
    private var onPictureCallback: ((ByteArray) -> Unit)? = null
    /** A still burst in progress: JPEGs and capture results are collected until the pick. */
    private class Burst(val expected: Int, val onPicture: (ByteArray) -> Unit) {
        val frames = ArrayList<Pair<Long, ByteArray>>()        // image timestamp -> JPEG
        val meta = HashMap<Long, Pair<Long, Int>>()            // sensor timestamp -> (exposure ns, ISO)
        var failed = 0
        var done = false
    }
    @Volatile private var burst: Burst? = null
    val isLocked: Boolean get() = last?.state == NsJni.STATE_LOCKED
    private var probeSinceMs = 0L
    private var restUntilMs = 0L

    // Per-frame log written while recording (one line per analysed frame, plus the frames
    // dropped as stale). Live correction is not possible with MediaRecorder, so the clip is
    // fixed afterwards (tools/postprocess.sh) and this log says how much of it needs fixing.
    @Volatile var meta: Writer? = null
    private val statsLock = Any()
    private var recFrames = 0
    private var recLocked = 0

    fun recordingStats(): Pair<Int, Int> = synchronized(statsLock) { recFrames to recLocked }

    fun closeMeta() = synchronized(statsLock) {
        try { meta?.flush(); meta?.close() } catch (_: java.io.IOException) {}
        meta = null
    }

    private fun recordFrame(ts: Long, expNs: Long, c: NsCommand?, luma: Double, stale: Boolean) {
        val w = meta ?: return
        synchronized(statsLock) {
            if (!stale) {
                recFrames += 1
                if (c?.state == NsJni.STATE_LOCKED) recLocked += 1
            }
            val hz = c?.flickerHz ?: 0.0
            try {
                w.write(String.format(Locale.US, "%d,%d,%d,%.4f,%d,%.1f,%d\n",
                    ts, expNs, c?.iso?.toInt() ?: 0, luma, c?.state ?: -1, hz, if (stale) 1 else 0))
            } catch (_: java.io.IOException) {}
            // Flush regularly: a debug log must survive the app being stopped.
            if (!stale && recFrames % 20 == 0) try { w.flush() } catch (_: java.io.IOException) {}
        }
    }

    // Brightness budget = exposure (us) * ISO, metered by the camera's own auto-exposure
    // before the shutter goes manual. A shorter exposure then gets a proportionally higher
    // ISO, so every trial of the closed loop sees the same image brightness (the residual
    // banding amplitude is only comparable between trials at equal brightness).
    private var budgetUsIso = 0.0
    private var latestAutoBudget = 0.0
    private var autoWait = 0
    /** The auto-exposure settle window is over: the gate must not re-arm, even with no budget. */
    private var autoSettled = false
    private var isoMin = 100.0
    private var isoMax = 6400.0
    private var recordingWarmupFrames = 0
    private var seedApplied = false
    private var readyCalled = false
    private var hsSession: CameraConstrainedHighSpeedCaptureSession? = null

    private fun triggerRecorderStart() {
        if (readyCalled || stopped) return
        readyCalled = true
        Log.i(TAG, "recorder start triggered after warmup (%d frames)".format(recordingWarmupFrames))
        onReady()
    }

    private val resultCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            // Where the lens really is (diopters; 0 = infinity): log changes, to check that a tap focuses.
            result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { d ->
                if (lastFocusDiopters < 0f || Math.abs(d - lastFocusDiopters) > 0.15f * maxOf(1f, lastFocusDiopters)) {
                    Log.i(TAG, "focus distance %.2f diopters (%s)".format(d, if (d > 0.01f) "%.2f m".format(1f / d) else "infinity"))
                    lastFocusDiopters = d
                }
            }
            val af = result.get(CaptureResult.CONTROL_AF_STATE)
            if (af != lastAfState) {
                lastAfState = af
                when (af) {
                    CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> Log.i(TAG, "focus: locked")
                    CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> Log.i(TAG, "focus: could not lock")
                    else -> Log.d(TAG, "af state $af")
                }
            }
            if (recorder != null && !readyCalled) {
                recordingWarmupFrames++
                if (seedExposureUs > 0.0 && seedHz > 0.0) {
                    val threshold = if (highSpeedFps > 0) 8 else 3
                    if (!seedApplied && recordingWarmupFrames >= threshold) {
                        seedApplied = true
                        val hs = hsSession
                        if (hs != null) {
                            applySeededHighSpeed(hs)
                        } else {
                            applySeededExposure()
                        }
                    }
                    val startThreshold = if (highSpeedFps > 0) 14 else 5
                    if (seedApplied && recordingWarmupFrames >= startThreshold) {
                        triggerRecorderStart()
                    }
                } else {
                    if (recordingWarmupFrames >= 3) {
                        triggerRecorderStart()
                    }
                }
            }
            val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            val e = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return

            // Standard Camera2 rolling shutter skew (API 21+): directly yields accurate line readout rate
            val skewNs = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW)
            if (skewNs != null && skewNs > 0L) {
                val skewUs = skewNs / 1000.0
                if (sustainUs <= 0.0 || abs(sustainUs - skewUs) > 100.0) {
                    sustainUs = skewUs
                    val h = reader?.height ?: 1080
                    val computedRate = LineProfileExtractor.lineRate(h, skewNs.toDouble())
                    if (computedRate > 0.0) {
                        lineRateHz = computedRate
                        pwm?.setLineRate(computedRate)
                        Log.i(TAG, "sensor rolling shutter skew: %.2f ms -> line rate %.0f Hz".format(
                            sustainUs / 1000.0, computedRate
                        ))
                    }
                }
            }

            // Track frame interval vs requested duration to detect if sensor ignores SENSOR_FRAME_DURATION
            if (lastCaptureTs > 0L && requestedFrameDurationNs > 0L) {
                val interval = ts - lastCaptureTs
                if (abs(interval - requestedFrameDurationNs) > 4_000_000L && interval < 500_000_000L) {
                    timingMismatches++
                    if (timingMismatches == 30 && !timingIgnored) {
                        timingIgnored = true
                        Log.w(TAG, "sensor ignores SENSOR_FRAME_DURATION: actual interval %.1f ms vs requested %.1f ms".format(
                            interval / 1e6, requestedFrameDurationNs / 1e6
                        ))
                    }
                } else {
                    timingMismatches = 0
                }
            }
            lastCaptureTs = ts

            if (requestedExposureNs == 0L) {
                val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                if (iso != null) {
                    latestAutoBudget = (e / 1000.0) * iso
                    if (ae == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                        budgetUsIso = latestAutoBudget
                    }
                }
            }
            synchronized(resultExposure) {
                latestResultExposureNs = e
                resultExposure[ts] = e
                if (resultExposure.size > 16) {
                    val oldest = resultExposure.keys.minOrNull()
                    if (oldest != null) resultExposure.remove(oldest)
                }
            }
        }
    }

    /** Exposure the sensor used for the frame with this timestamp, or the latest known. */
    private var matchHit = 0
    private var matchMiss = 0
    private var missRun = 0


    private fun exposureMatches(actualNs: Long, requestedNs: Long): Boolean {
        if (requestedNs <= 0L) return true
        // Sensors quantise exposure to a multiple of the row time: allow 3 % or 60 us.
        val tol = maxOf(requestedNs * 3 / 100, 60_000L)
        return abs(actualNs - requestedNs) <= tol
    }

    // The caller (MainActivity.startCamera) checks the CAMERA permission before it gets here.
    @android.annotation.SuppressLint("MissingPermission")
    fun start() {
        try {
            openPipeline()
        } catch (e: CameraAccessException) {
            // Busy, disabled or gone: nothing is left running, the caller says so.
            Log.w(TAG, "camera $cameraId not opened: ${e.message}")
            stop()
            onFailure(CAMERA_FAIL_OPEN)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "camera $cameraId not opened: ${e.message}")
            stop()
            onFailure(CAMERA_FAIL_OPEN)
        }
    }

    /** The camera was lost while running: reported once, not while stopping. */
    private fun reportLost() {
        if (stopped || reportedLoss) return
        reportedLoss = true
        onFailure(CAMERA_FAIL_LOST)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun openPipeline() {
        stopped = false
        reportedLoss = false
        Log.i(TAG, "controller: ${if (pwmMode) "pwm (100 Hz - 5 kHz, row profile)" else "strobe (slow flashes, frame luma)"}")
        // A 32-bit process on a 64-bit capable device (measured on a Xperia 1 III ROM): the
        // framework aborts the whole process inside ImageReader's Image.getPlanes(), which
        // cannot be caught. Those devices stay on auto-exposure and only record.
        val risky32 = !android.os.Process.is64Bit() && Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        if (risky32) Log.w(TAG, "32-bit process on a 64-bit device: no flicker analysis (framework abort in getPlanes)")
        val noProbe = holdAutoNoSeed && seedExposureUs <= 0.0
        autoOnly = forceAutoOnly || risky32 || noProbe ||
            (pwmMode && !Camera2Sync.supportsManualSensor(manager.getCameraCharacteristics(cameraId)))
        if (autoOnly) {
            if (noProbe) {
                Log.i(TAG, "recording without a flicker lock: auto-exposure, no probing")
                onStatus(NsCommand(0.0, 0.0, 0.0, NsJni.MODE_AUTO, NsJni.STATE_SEARCH, 0.0, 0.0, 0.0))
            } else {
                Log.w(TAG, "camera $cameraId has no MANUAL_SENSOR capability: flicker sync unavailable")
                onStatus(unsupportedStatus())
            }
        }
        // A pinned lens has its own sensor: exposure range, readout time and stream sizes.
        val chars = manager.getCameraCharacteristics(physicalId ?: cameraId)
        val limits = Camera2Sync.limitsFromCharacteristics(chars)
        sustainUs = limits.sustainUs
        // Android 16: the app fixes the exposure time and the camera's AE picks the ISO
        // (CONTROL_AE_PRIORITY_MODE). The keys are not in android.jar at compileSdk 34.
        val prio = if (Build.VERSION.SDK_INT >= 29) try {
            chars.get(CameraCharacteristics.Key("android.control.aeAvailablePriorityModes", IntArray::class.java))
        } catch (_: Throwable) { null } else null
        Log.i(TAG, "AE priority modes: ${prio?.joinToString(",") ?: "none"}")
        Log.i(TAG, "limits: exposure %.0f..%.0f us, sustain %.0f us, iso %.0f..%.0f, fps %s".format(
            limits.minExposureUs, limits.maxExposureUs, limits.sustainUs, limits.minIso, limits.maxIso,
            limits.supportedFps.joinToString(",")))
        isoMin = limits.minIso
        isoMax = limits.maxIso

        if (pwmMode) {
            // The controller is built on the first frame: the line rate needs the stream
            // height, which is only known once an image arrives.
            pwm = null
        } else {
            strobe = NsJni.create(limits)
        }

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        // A modest analysis size keeps the per-frame row profile cheap; the preview is a
        // separate surface and does not need the full resolution.
        val size = map?.getOutputSizes(ImageFormat.YUV_420_888)
            ?.filter { it.height <= 720 }
            ?.maxBy { it.width.toLong() * it.height }
            ?: Size(1280, 720)

        // The listener must not run on the main thread: the row profile and the JNI call
        // per frame freeze the UI, and the preview lags.
        val thread = HandlerThread("analysis").apply { start() }
        analysisThread = thread
        handler = Handler(thread.looper)
        // Ask for CPU-readable buffers: on some devices the default buffers of a 32-bit process
        // cannot be mapped and Image.getPlanes() aborts the process inside the framework.
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3,
                android.hardware.HardwareBuffer.USAGE_CPU_READ_OFTEN)
        } else {
            ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
        }
        r.setOnImageAvailableListener({ reader -> onFrame(reader) }, handler)
        reader = r
        analysisSurface = r.surface

        val normalSurfaces = if (recorder != null) {
            listOf(previewSurface, r.surface, recorder.surface)
        } else {
            val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)
            val targetRatio = 16f / 9f
            val jpegSize = jpegSizes?.filter { s ->
                val ratio = s.width.toFloat() / s.height.toFloat()
                kotlin.math.abs(ratio - targetRatio) < 0.05f
            }?.maxByOrNull { it.width.toLong() * it.height }
                ?: jpegSizes?.maxByOrNull { it.width.toLong() * it.height }
                ?: Size(1920, 1080)
            // One more than the burst: the reader must hold every frame until it is read.
            val pr = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, BURST_COUNT + 1)
            pr.setOnImageAvailableListener({ rd ->
                while (true) {
                    val img = try { rd.acquireNextImage() } catch (_: Exception) { null } ?: break
                    try {
                        val buf = img.planes[0].buffer
                        val bytes = ByteArray(buf.remaining())
                        buf.get(bytes)
                        onStillFrame(img.timestamp, bytes)
                    } finally {
                        img.close()
                    }
                }
            }, handler)
            photoReader = pr
            listOf(previewSurface, r.surface, pr.surface)
        }
        // High-speed: the session takes one or two surfaces and no analysis stream; we give it
        // the recorder only. The normal set is kept for the fallback when the device refuses.
        val hsSurfaces = recorder?.surface?.let { listOf(it) }
        val surfaces = if (highSpeedFps > 0 && hsSurfaces != null) hsSurfaces else normalSurfaces
        fallbackSurfaces = normalSurfaces
        surfaceList = surfaces

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                // The camera opens asynchronously: the activity may have been paused (screen
                // off) and the preview surface destroyed meanwhile.
                if (stopped || surfaces.any { !it.isValid }) {
                    d.close()
                    return
                }
                device = d
                // stop() may have run since the check above, and found no device to close.
                if (stopped) { d.close(); device = null; return }
                try {
                    createSession(d, surfaces, startLevel)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "session not created: ${e.message}")
                    d.close(); device = null
                } catch (e: CameraAccessException) {
                    Log.w(TAG, "session not created: ${e.message}")
                    d.close(); device = null
                    if (!stopped) onFailure(CAMERA_FAIL_OPEN)
                }
            }

            override fun onDisconnected(d: CameraDevice) {
                d.close()
                device = null
                reportLost()
            }

            override fun onError(d: CameraDevice, e: Int) {
                d.close()
                device = null
                Log.w(TAG, "camera $cameraId error $e")
                reportLost()
            }
        }, null)
    }

    /**
     * The OS image pipeline at its best: high-quality noise reduction, edge, hot-pixel,
     * tone-map and aberration modes, when the camera lists them. The clip is meant to be
     * post-processed, so the less the ISP smears it the better.
     */
    private fun applyQuality(b: CaptureRequest.Builder) {
        val chars = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull() ?: return
        fun hq(avail: CameraCharacteristics.Key<IntArray>, want: Int, key: CaptureRequest.Key<Int>) {
            if (chars.get(avail)?.contains(want) == true) b.set(key, want)
        }
        hq(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
            CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY, CaptureRequest.NOISE_REDUCTION_MODE)
        hq(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES,
            CameraMetadata.EDGE_MODE_HIGH_QUALITY, CaptureRequest.EDGE_MODE)
        hq(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES,
            CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY, CaptureRequest.HOT_PIXEL_MODE)
        hq(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES,
            CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE)
        hq(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES,
            CameraMetadata.TONEMAP_MODE_HIGH_QUALITY, CaptureRequest.TONEMAP_MODE)
    }

    private fun applyBias(b: CaptureRequest.Builder) {
        // Only used while the camera's auto-exposure runs (before probing, and in the rest
        // periods). In manual exposure the bias scales the ISO budget instead.
        val chars = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull() ?: return
        val step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: return
        val range = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: return
        if (step <= 0f) return
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, Math.round(evBias / step).coerceIn(range.lower, range.upper))
    }

    /** Exposure bar: takes effect on the next frame, without touching the flicker lock. */
    fun setExposureBias(ev: Float) {
        evBias = ev
        last = null  // the next analysed frame re-applies the ISO with the new bias
        val b = builder ?: return
        applyBias(b)
        try {
            session?.setRepeatingRequest(b.build(), resultCallback, handler)
        } catch (e: Exception) {
            Log.w(TAG, "exposure bias not applied: ${e.message}")
        }
    }

    private fun applyZoom(b: CaptureRequest.Builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
        }
    }

    /**
     * Tap to focus at a point of the preview (0..1 in the upright view). The point is turned
     * into sensor coordinates (rotation, then the zoom crop), autofocus is triggered once
     * and then held; continuous autofocus resumes after FOCUS_HOLD_MS.
     */
    fun focusAt(nx: Float, ny: Float) {
        val b = builder ?: return
        val sess = session ?: return
        val chars = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull() ?: return
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val front = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        val (rawSx, rawSy) = when (orientation) {
            90 -> ny to (1f - nx)
            270 -> (1f - ny) to nx
            180 -> (1f - nx) to (1f - ny)
            else -> nx to ny
        }
        val sx = if (front) 1f - rawSx else rawSx
        val sy = rawSy
        // What the user sees is the zoom crop, centred on the active array.
        val z = zoomRatio.coerceAtLeast(0.1f)
        val cw = active.width() / z
        val ch = active.height() / z
        val px = active.exactCenterX() - cw / 2f + sx * cw
        val py = active.exactCenterY() - ch / 2f + sy * ch
        val half = (minOf(active.width(), active.height()) / 10f).toInt()
        val rect = Rect(
            (px - half).toInt().coerceIn(active.left, active.right - 1),
            (py - half).toInt().coerceIn(active.top, active.bottom - 1),
            (px + half).toInt().coerceIn(active.left + 1, active.right),
            (py + half).toInt().coerceIn(active.top + 1, active.bottom),
        )
        val maxAf = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        if (maxAf > 0) {
            b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX - 1)))
        }
        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
        Log.i(TAG, "tap to focus at (%.2f, %.2f) -> sensor rect %s".format(nx, ny, rect))
        try {
            sess.capture(b.build(), focusCallback, handler)
            // The trigger is one-shot: the held request must not carry it again.
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            sess.setRepeatingRequest(b.build(), resultCallback, handler)
            handler?.removeCallbacks(resumeContinuousFocus)
            handler?.postDelayed(resumeContinuousFocus, FOCUS_HOLD_MS)
        } catch (e: Exception) {
            Log.w(TAG, "focus not applied: ${e.message}")
        }
    }

    private val resumeContinuousFocus = Runnable {
        val b = builder ?: return@Runnable
        val sess = session ?: return@Runnable
        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        b.set(CaptureRequest.CONTROL_AF_REGIONS, null)
        b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
        try {
            sess.capture(b.build(), null, handler)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            sess.setRepeatingRequest(b.build(), resultCallback, handler)
        } catch (_: Exception) {}
    }

    private val focusCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            when (result.get(CaptureResult.CONTROL_AF_STATE)) {
                CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> Log.i(TAG, "focus: locked")
                CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> Log.i(TAG, "focus: could not lock")
                else -> {}
            }
        }
    }

    /** Zoom without restarting the session: the repeating request is reissued. */
    fun setZoom(ratio: Float) {
        zoomRatio = ratio
        val b = builder ?: return
        applyZoom(b)
        try {
            session?.setRepeatingRequest(b.build(), resultCallback, handler)
        } catch (e: Exception) {
            Log.w(TAG, "zoom not applied: ${e.message}")
        }
    }

    /**
     * Take a still photo as a BURST and keep the best frame: under a strobe some frames are lit by
     * a pulse and some are not, and a single shot cannot choose. Each frame carries the
     * flicker-locked exposure when there is a lock. [onPicture] gets the chosen JPEG once.
     */
    fun takePicture(orientation: Int, onPicture: (ByteArray) -> Unit) {
        val current = burst
        if (current != null && !current.done) return  // the previous burst is still running
        if (!startBurst(orientation, BURST_COUNT, onPicture)) {
            // The device refused the burst: a single frame, as before, rather than no photo.
            Log.w(TAG, "burst refused: single capture")
            startBurst(orientation, 1, onPicture)
        }
    }

    private fun startBurst(orientation: Int, count: Int, onPicture: (ByteArray) -> Unit): Boolean {
        val d = device ?: return false
        val s = session ?: return false
        val ps = photoReader?.surface ?: return false
        val b = Burst(count, onPicture)
        burst = b
        try {
            val requests = (0 until count).map {
                val r = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                r.addTarget(ps)
                applyZoom(r)
                applyBias(r)
                applyQuality(r)
                r.set(CaptureRequest.JPEG_ORIENTATION, orientation)
                // If flicker/strobe is locked, enforce the exact locked exposure & ISO for the still capture
                if (last?.state == NsJni.STATE_LOCKED && requestedExposureNs > 0L) {
                    r.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    r.set(CaptureRequest.SENSOR_EXPOSURE_TIME, requestedExposureNs)
                    builder?.get(CaptureRequest.SENSOR_SENSITIVITY)?.let { iso ->
                        r.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    }
                } else {
                    // No flicker lock: the auto-exposure keeps converging between the frames of the burst
                    // (16 ms then 66 ms on a Pixel 10 Pro XL in a dim room), and the frames could not be
                    // compared. Lock it at its current state for the whole burst.
                    r.set(CaptureRequest.CONTROL_AE_LOCK, true)
                }
                r.build()
            }
            s.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult,
                ) {
                    val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                    val e = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
                    val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
                    synchronized(b) { b.meta[ts] = e to iso }
                    maybeFinishBurst(b)
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure,
                ) {
                    synchronized(b) { b.failed += 1 }
                    maybeFinishBurst(b)
                }
            }, handler)
            // Whatever has arrived is used if the pipeline stalls.
            handler?.postDelayed({ finishBurst(b) }, BURST_TIMEOUT_MS)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "takePicture burst failed: ${e.message}")
            b.done = true
            burst = null
            return false
        }
    }

    /** A JPEG of the burst arrived (camera handler thread). */
    private fun onStillFrame(timestamp: Long, bytes: ByteArray) {
        val b = burst ?: return
        synchronized(b) { if (!b.done) b.frames.add(timestamp to bytes) }
        maybeFinishBurst(b)
    }

    /** All the frames and results are in (a failed capture counts as answered). */
    private fun maybeFinishBurst(b: Burst) {
        val ready = synchronized(b) {
            !b.done && b.frames.size + b.failed >= b.expected && b.frames.all { b.meta.containsKey(it.first) }
        }
        if (ready) finishBurst(b)
    }

    /** Scores the frames off the camera thread and hands the best one over. */
    private fun finishBurst(b: Burst) {
        val frames = synchronized(b) {
            if (b.done) return
            b.done = true
            b.frames.toList()
        }
        if (burst === b) burst = null
        if (frames.isEmpty()) {
            Log.w(TAG, "burst: no frame arrived")
            return
        }
        Thread {
            try {
                val scored = frames.map { (ts, jpeg) ->
                    val (e, iso) = synchronized(b) { b.meta[ts] } ?: (0L to 0)
                    BurstPick.analyse(jpeg, e, iso)
                }
                val best = BurstPick.pick(scored)
                Log.i(TAG, "burst: ${frames.size} frames, kept #$best; light " +
                    scored.joinToString(" ") { "%.2f".format(it.light) } + "; banding " +
                    scored.joinToString(" ") { "%.3f".format(it.banding) } + "; exposure ms x ISO " +
                    frames.joinToString(" ") { (ts, _) ->
                        val (e, iso) = synchronized(b) { b.meta[ts] } ?: (0L to 0)
                        "%.1fx%d".format(e / 1e6, iso)
                    })
                b.onPicture(frames[best].second)
            } catch (e: Exception) {
                Log.w(TAG, "burst pick failed (${e.message}): keeping the first frame")
                b.onPicture(frames[0].second)
            }
        }.start()
    }

    /**
     * Opens the capture session, trying simpler stream combinations when the device refuses
     * one ("Unsupported set of inputs/outputs"; seen on a Sony Xperia 1 III with preview +
     * analysis + recorder on a pinned lens):
     *   0  the requested lens pinned, every stream
     *   1  the logical camera (the phone picks the lens), every stream
     *   2  no analysis stream: preview + recorder only, no flicker sync (post-processing)
     *   3  preview only: the clip cannot be recorded on this device
     */
    /**
     * Constrained high-speed recording (e.g. 120/240 frames per second), chosen by the app when
     * the locked flicker is fast enough (frame time >= the exposure k/f with k = 1). There is no
     * analysis stream and no preview in such a session: the lock found before is applied open
     * loop. If the device refuses it, fall back to the normal preview + recorder session.
     */
    private fun createHighSpeedSession(d: CameraDevice, surfaces: List<Surface>) {
        autoOnly = true
        val cb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (stopped) {
                    try { s.close() } catch (_: Exception) {}
                    return
                }
                session = s
                val hs = s as? CameraConstrainedHighSpeedCaptureSession
                if (hs == null) { createSession(d, fallbackSurfaces, 2); return }
                try {
                    val b = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    for (t in surfaces) b.addTarget(t)
                    b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(highSpeedFps, highSpeedFps))
                    builder = b
                    hsSession = hs
                    hs.setRepeatingBurst(hs.createHighSpeedRequestList(b.build()), resultCallback, handler)
                    Log.i(TAG, "high-speed session at $highSpeedFps fps")
                    handler?.postDelayed({ triggerRecorderStart() }, 1500L)
                } catch (e: Exception) {
                    Log.w(TAG, "high-speed start failed (${e.message}): normal session")
                    createSession(d, fallbackSurfaces, 2)
                }
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                Log.w(TAG, "high-speed session refused at $highSpeedFps fps: normal session")
                try { createSession(d, fallbackSurfaces, 2) } catch (e: Exception) {
                    Log.w(TAG, "fallback failed: ${e.message}")
                }
            }
        }
        try {
            d.createConstrainedHighSpeedCaptureSession(surfaces, cb, handler)
        } catch (e: Exception) {
            Log.w(TAG, "high-speed not available (${e.message}): normal session")
            createSession(d, fallbackSurfaces, 2)
        }
    }

    /** Exposure for the high-speed rate: the longest multiple of the period that fits the frame. */
    private fun applySeededHighSpeed(hs: CameraConstrainedHighSpeedCaptureSession) {
        val b = builder ?: return
        if (stopped || seedHz <= 0.0) return
        val (k, e) = HighSpeedPick.exposureFor(1e6 / highSpeedFps, seedHz)
        val budget = if (budgetUsIso > 0.0) budgetUsIso else latestAutoBudget
        val iso = if (budget > 0.0) (budget * Math.pow(2.0, evBias.toDouble()) / e).coerceIn(isoMin, isoMax) else isoMin
        val cmd = NsCommand(e, highSpeedFps.toDouble(), iso, NsJni.MODE_PWM, NsJni.STATE_LOCKED, 1e6 / seedHz, 0.8, 0.0, seedHz)
        Camera2Sync.apply(b, cmd)
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(highSpeedFps, highSpeedFps))
        requestedExposureNs = (e * 1000.0).toLong()
        try {
            hs.setRepeatingBurst(hs.createHighSpeedRequestList(b.build()), resultCallback, handler)
            Log.i(TAG, "high speed %d fps: %.0f us (k=%.0f of %.1f Hz) ISO %.0f".format(highSpeedFps, e, k, seedHz, iso))
            onStatus(cmd)
        } catch (ex: Exception) {
            Log.w(TAG, "high-speed exposure not applied: ${ex.message}")
        }
    }

    private fun createSession(d: CameraDevice, all: List<Surface>, level: Int = 0) {
        if (stopped) return
        if (highSpeedFps > 0 && all === surfaceList && recorder != null) {
            createHighSpeedSession(d, all)
            return
        }
        val pin = if (level == 0) physicalId else null
        activePhysicalId = pin
        val surfaces = when {
            level >= 3 -> listOf(previewSurface)
            level == 2 -> all.filter { it !== analysisSurface }
            else -> all
        }
        if (level >= 2) {
            autoOnly = true
            // Starting at level 2 on purpose (recording) is not a device limitation.
            if (startLevel < 2 || level > startLevel) onStatus(unsupportedStatus())
        }
        if (level == 1 && physicalId == null) {
            // Nothing was pinned at level 0, so level 1 would be the same request.
            createSession(d, all, 2); return
        }
        val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        // The JPEG reader belongs to the session but not to the repeating request: it was encoding a
        // full-resolution JPEG for every preview frame, and a burst would have collected them all.
        for (s in surfaces) if (s !== photoReader?.surface) b.addTarget(s)
        applyZoom(b)
        applyBias(b)
        applyQuality(b)
        builder = b
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (stopped) {
                    try { s.close() } catch (_: Exception) {}
                    return
                }
                session = s
                try {
                    s.setRepeatingRequest(b.build(), resultCallback, handler)
                } catch (e: Exception) {
                    Log.w(TAG, "session setRepeatingRequest failed: ${e.message}")
                    return
                }
                if (recorder == null) {
                    if (level < 3) onReady()
                } else if (level >= 3) {
                    // Preview only: the recorder surface is not in this session, nothing can be recorded.
                    Log.w(TAG, "preview-only session with a recorder: cannot record on this camera")
                    onStatus(unsupportedStatus())
                    onFailure(CAMERA_FAIL_NO_RECORD)
                } else {
                    handler?.postDelayed({ triggerRecorderStart() }, 1500L)
                }
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                Log.w(TAG, "session configuration failed at level $level (lens pin $pin): trying a simpler one")
                if (level < 3 && !stopped) {
                    try { createSession(d, all, level + 1) } catch (e: Exception) {
                        Log.w(TAG, "fallback session failed: ${e.message}")
                    }
                } else if (level >= 3 && !stopped) {
                    onStatus(unsupportedStatus())
                    onFailure(if (recorder != null) CAMERA_FAIL_NO_RECORD else CAMERA_FAIL_NO_SESSION)
                }
            }
        }
        val executor = java.util.concurrent.Executor { r -> (handler ?: Handler(android.os.Looper.getMainLooper())).post(r) }
        if (pin != null) {
            // Streams bound to one physical lens: the phone cannot switch lenses under us.
            val configs = surfaces.map { sf ->
                OutputConfiguration(sf).also { it.setPhysicalCameraId(pin) }
            }
            d.createCaptureSession(
                SessionConfiguration(SessionConfiguration.SESSION_REGULAR, configs, executor, callback)
            )
        } else {
            d.createCaptureSession(surfaces, callback, null)
        }
    }

    // Shortest exposure this sensor really delivers. The advertised range can be shorter
    // (and differs per lens): learned when the sensor keeps returning a longer exposure.
    private var minExposureOverrideUs = 0.0
    private var maxExposureOverrideUs = 0.0
    private var lastActualNs = 0L
    private var mismatchRun = 0

    private fun currentLimits(): NsJni.CameraLimits {
        val base = Camera2Sync.limitsFromCharacteristics(
            manager.getCameraCharacteristics(activePhysicalId ?: cameraId)
        )
        var limits = base
        if (minExposureOverrideUs > limits.minExposureUs) limits = limits.copy(minExposureUs = minExposureOverrideUs)
        if (maxExposureOverrideUs > 0.0 && maxExposureOverrideUs < limits.maxExposureUs) {
            limits = limits.copy(maxExposureUs = maxOf(maxExposureOverrideUs, limits.minExposureUs))
        }
        return limits
    }

    /**
     * Open loop: the camera has been on auto-exposure for a moment (the capture results gave
     * the brightness budget, exposure x ISO), now the locked exposure goes in and the ISO is
     * what keeps that brightness. No analysis stream is in the session, so nothing re-checks
     * the lock until the recording ends.
     */
    private fun applySeededExposure() {
        val b = builder ?: return
        val sess = session ?: return
        if (stopped) return
        val budget = if (budgetUsIso > 0.0) budgetUsIso else latestAutoBudget
        val iso = if (budget > 0.0) {
            (budget * Math.pow(2.0, evBias.toDouble()) / seedExposureUs).coerceIn(isoMin, isoMax)
        } else isoMin
        val cmd = NsCommand(seedExposureUs, recordFps.toDouble(), iso, NsJni.MODE_PWM, NsJni.STATE_LOCKED,
            1e6 / seedHz, 0.8, 0.0, seedHz)
        Camera2Sync.apply(b, cmd)
        applyZoom(b)
        applyQuality(b)
        requestedExposureNs = (seedExposureUs * 1000.0).toLong()
        try {
            sess.setRepeatingRequest(b.build(), resultCallback, handler)
            Log.i(TAG, "open loop: %.0f us at ISO %.0f (budget %.0f)".format(seedExposureUs, iso, budget))
            onStatus(cmd)
        } catch (e: Exception) {
            Log.w(TAG, "open-loop exposure not applied: ${e.message}")
        }
    }

    /** Brightness of a frame under a strobe of [hz] (10 % duty): ambient + the flash share caught. */
    private fun simStrobeLuma(tsNs: Long, expNs: Long, hz: Double): Double {
        val period = 1e9 / hz
        val pulse = 0.1 * period
        val start = tsNs.toDouble()
        val end = start + expNs
        var caught = 0.0
        var n = Math.floor(start / period) - 1
        while (n * period < end) {
            val a = maxOf(start, n * period)
            val b = minOf(end, n * period + pulse)
            if (b > a) caught += b - a
            n += 1.0
        }
        // Ambient grows with the exposure; the pulse adds its caught energy (full pulse = 0.8).
        val ambient = 0.1 * minOf(1.0, expNs / 33e6)
        return (ambient + 0.8 * (caught / pulse)).coerceIn(0.0, 1.0)
    }

    // --- debug: row profiles at fixed exposures, to study a real light offline ---------------
    // Debug: `--es dump_exposures 12000,12500,...` (microseconds) replaces the default grid, for a fine sweep.
    private val dumpExposuresUs = dumpExposuresOverride ?: doubleArrayOf(500.0, 1000.0, 2000.0, 4000.0, 6000.0, 8000.0, 10000.0,
        12000.0, 15000.0, 20000.0, 25000.0, 30000.0)
    private var dumpIndex = 0
    private var dumpFrames = 0
    private var dumpWriter: java.io.Writer? = null

    /** Returns true while the sweep owns the frame. */
    private fun handleDump(image: android.media.Image, exposureNs: Long): Boolean {
        if (dumpIndex >= dumpExposuresUs.size) return false
        val b = builder ?: return true
        val e = dumpExposuresUs[dumpIndex]
        if (requestedExposureNs != (e * 1000).toLong()) {
            val budget = if (budgetUsIso > 0.0) budgetUsIso else latestAutoBudget
            val iso = if (budget > 0.0) (budget / e).coerceIn(isoMin, isoMax) else isoMax
            Camera2Sync.apply(b, NsCommand(e, 30.0, iso, NsJni.MODE_PWM, NsJni.STATE_SEARCH, 0.0, 0.0, 0.0))
            requestedExposureNs = (e * 1000).toLong()
            dumpFrames = 0
            try { session?.setRepeatingRequest(b.build(), resultCallback, handler) } catch (_: Exception) {}
            Log.i(TAG, "dump: exposure %.0f us ISO %.0f".format(e, iso))
            return true
        }
        if (!exposureMatches(exposureNs, requestedExposureNs)) return true
        if (dumpWriter == null) {
            dumpWriter = java.io.FileWriter(java.io.File(outDir ?: return true, "profiles.csv"))
        }
        val prof = LineProfileExtractor.rowProfile(image, 1)
        val w = dumpWriter!!
        w.write("%.0f,%d".format(e, image.timestamp))
        for (v in prof) w.write(",%.5f".format(java.util.Locale.US, v))
        w.write("\n")
        // Flush every line: a 1080-row line is about 7.5 kB, so the FileWriter's buffer holds
        // barely one. Stopping the app (am force-stop) then loses everything not written to disk,
        // and profiles.csv reads as 0 bytes.
        w.flush()
        dumpFrames += 1
        if (dumpFrames >= 24) {
            dumpIndex += 1
            dumpFrames = 0
            if (dumpIndex >= dumpExposuresUs.size) {
                w.flush(); w.close(); dumpWriter = null
                Log.i(TAG, "dump: done")
            }
        }
        return true
    }

    private fun unsupportedStatus() = NsCommand(0.0, 0.0, 0.0, NsJni.MODE_UNSUPPORTED,
        NsJni.STATE_SEARCH, 0.0, 0.0, 0.0)

    private fun onFrame(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            return  // reader closed during shutdown
        } ?: return
        try {
            if (autoOnly) return  // nothing to analyse: no manual exposure on this camera
            if (SystemClock.elapsedRealtime() < restUntilMs) return  // auto-exposure rest
            // Let the camera's auto-exposure settle first so the brightness budget is known
            // (about one second at 60 fps; the analysis has room for that).
            if (!autoSettled && requestedExposureNs == 0L && budgetUsIso == 0.0) {
                autoWait += 1
                if (autoWait < 60) return
                budgetUsIso = latestAutoBudget  // AE never reported CONVERGED: use what we have
                autoSettled = true  // with a zero budget the old test never closed: the dump
                // (and the controller) were never reached, and the app analysed nothing.
            }
            // The controller is created once the brightness budget is known: the frame rate and
            // the shutter it aims for depend on it.
            if (pwmMode && pwm == null) {
                val baseLimits = currentLimits()
                // Dim scene: the exposure is capped by the frame time (a bit under one frame), so
                // at the highest frame rate the ISO would hit its maximum. Like the phone's own
                // auto-exposure, use a lower frame rate (not under MIN_DIM_FPS) until the ISO
                // stays under ISO_SOFT_MAX, or as low as allowed.
                val needUs = if (budgetUsIso > 0.0) budgetUsIso / ISO_SOFT_MAX else 0.0
                val fpsChoices = baseLimits.supportedFps.filter { it >= MIN_DIM_FPS }.distinct().sortedDescending()
                    .ifEmpty { listOf(baseLimits.supportedFps.maxOrNull() ?: 30.0) }
                val chosenFps = fpsChoices.firstOrNull { (1e6 / it) * 0.95 >= needUs } ?: fpsChoices.last()
                val limits = baseLimits.copy(supportedFps = doubleArrayOf(chosenFps))
                Log.i(TAG, "frame rate %.0f for a brightness budget of %.0f (need %.1f ms at ISO %.0f)"
                    .format(chosenFps, budgetUsIso, needUs / 1000.0, ISO_SOFT_MAX))
                lineRateHz = LineProfileExtractor.lineRate(image.height, sustainUs * 1000.0)
                pwm = NsJni.createPwm(limits, lineRateHz)
                val chars = manager.getCameraCharacteristics(activePhysicalId ?: cameraId)
                val pipelineDepth = (chars.get(CameraCharacteristics.REQUEST_PIPELINE_MAX_DEPTH)?.toInt() ?: 2).toDouble()
                pwm!!.setPipelineDepth(pipelineDepth)
                // Long exposures on a multiple of the period (180 degree shutter) keep the ISO low.
                // Longer still when the scene is dim enough that the ISO would pass ISO_SOFT_MAX
                // (up to nearly a full frame): more motion blur, much less noise.
                val frameUs = 1e6 / chosenFps
                pwm!!.setShutterFraction((needUs / frameUs).coerceIn(SHUTTER_FRACTION, 0.95))
                if (budgetUsIso > 0.0) {
                    // The search stays above the exposure where the ISO would pass ISO_SEARCH_MAX
                    // (a preview at ISO 17000 is pink noise), and below the one that overexposes.
                    pwm!!.setMinExposure(budgetUsIso * Math.pow(2.0, evBias.toDouble()) / ISO_SEARCH_MAX)
                    pwm!!.setMaxExposure(budgetUsIso * Math.pow(2.0, evBias.toDouble()) / isoMin)
                }
                if (seedExposureUs > 0.0 && seedHz > 0.0) {
                    Log.i(TAG, "starting locked: %.0f us at %.1f Hz".format(seedExposureUs, seedHz))
                    pwm!!.seed(seedExposureUs, seedHz)
                }
            }
            // The exposure THIS frame was taken with: its own capture result, matched by sensor
            // timestamp. On some devices the result arrives after the image (about 40 % of the
            // frames on a Xperia 1 III and a Pixel 11 Pro Fold): using the latest known value
            // instead made the closed loop chase a one-step-old exposure and drift. Such frames
            // are dropped; if no frame ever matches (timestamps of another clock), fall back.
            val frameExposureNs: Long = run {
                val exact = synchronized(resultExposure) { resultExposure[image.timestamp] }
                if (exact != null) {
                    matchHit += 1; missRun = 0; exact
                } else {
                    matchMiss += 1; missRun += 1
                    if (matchHit == 0 && missRun > 60) latestResultExposureNs else return
                }
            }
            if (dumpProfiles && handleDump(image, frameExposureNs)) return
            // Skip frames still carrying the previous exposure; they would pollute the trial.
            if (!exposureMatches(frameExposureNs, requestedExposureNs)) {
                staleFrames += 1
                val actual = frameExposureNs
                mismatchRun = if (abs(actual - lastActualNs) <= actual / 100) mismatchRun + 1 else 0
                lastActualNs = actual
                if (mismatchRun >= 20) {
                    // The sensor keeps applying the same exposure, not the requested one: relearn
                    // the range. Above the request: its real minimum. Below it: a real maximum
                    // lower than the advertised one (frame duration, vendor cap). Without this the
                    // 60 stale frames below would switch manual exposure off for good.
                    if (actual > requestedExposureNs) {
                        minExposureOverrideUs = actual / 1000.0 * 1.02
                        Log.i(TAG, "sensor minimum exposure is about ${actual / 1000} us, not the advertised one")
                    } else {
                        maxExposureOverrideUs = actual / 1000.0 * 0.98
                        Log.i(TAG, "sensor maximum exposure is about ${actual / 1000} us here, not the advertised one")
                    }
                    mismatchRun = 0
                    staleFrames = 0
                    pwm?.close(); pwm = null
                    last = null
                    requestedExposureNs = 0L
                    return
                }
                recordFrame(image.timestamp, frameExposureNs, last, -1.0, true)
                if (staleFrames == 60) {
                    Log.w(TAG, "sensor ignores the requested exposure (asked $requestedExposureNs ns, " +
                        "got ${frameExposureNs} ns): manual exposure unsupported in this mode, " +
                        "back to auto-exposure for good")
                    autoOnly = true
                    fallBackToAuto(SystemClock.elapsedRealtime(), last ?: unsupportedStatus())
                    onStatus(unsupportedStatus())
                    return
                }
                return
            }
            staleFrames = 0
            if (simFlickerHz > 0.0 && (matchHit % 40) == 0) {
                Log.d(TAG, "exposure asked %d ns, applied %d ns (%.2f %%)".format(requestedExposureNs, frameExposureNs,
                    100.0 * (frameExposureNs - requestedExposureNs) / maxOf(1L, requestedExposureNs)))
            }
            var lumaMean = 0.0
            val procStart = System.nanoTime()
            val cmd = if (pwmMode) {
                if (simFlickerHz > 0.0) {
                    // Simulated flicker: what a PWM light would show at the exposure the sensor
                    // really used for this frame, with a phase that follows the frame time.
                    val expUs = frameExposureNs / 1000.0
                    val phase = (image.timestamp * 1e-9 * simFlickerHz) % 1.0
                    val prof = NsJni.syntheticProfile(expUs, simFlickerHz, phase, 0.3, SIM_LINE_RATE_HZ, image.height)
                    lumaMean = if (prof.isEmpty()) 0.0 else prof.average()
                    pwm!!.onProfile(prof)
                } else {
                    val y = image.planes.getOrNull(0)
                    val buf = y?.buffer
                    if (buf != null && buf.isDirect) {
                        pwm!!.onYPlane(buf, image.width, image.height, y.rowStride, y.pixelStride, 1, 1)
                    } else {
                        val prof = LineProfileExtractor.rowProfile(image, 1)
                        lumaMean = if (prof.isEmpty()) 0.0 else prof.average()
                        pwm!!.onProfile(prof)
                    }
                }
            } else {
                lumaMean = LumaExtractor.meanLuma(image).toDouble()
                if (simFlickerHz > 0.0) {
                    // Simulated slow strobe (pulses of 10 % duty): the frame's brightness is the part
                    // of the pulse its exposure window caught, so the shutter really matters.
                    lumaMean = simStrobeLuma(image.timestamp, frameExposureNs, simFlickerHz)
                }
                strobe!!.onFrame(image.timestamp / 1000.0, lumaMean)
            }
            val elapsedUs = (System.nanoTime() - procStart) / 1000L
            avgProcessUs = if (avgProcessUs == 0L) elapsedUs else (avgProcessUs * 4 + elapsedUs) / 5
            // ISO that keeps the metered brightness at this exposure; the controller's own
            // ISO has no reference for the scene and is ignored once the budget is known.
            val applied = if (budgetUsIso > 0.0) {
                cmd.copy(iso = (budgetUsIso * Math.pow(2.0, evBias.toDouble()) / cmd.exposureUs).coerceIn(isoMin, isoMax))
            } else {
                cmd
            }
            if (applied.state == NsJni.STATE_LOCKED && (applied.flickerHz > 0.0 || applied.periodUs > 0.0)) {
                lockExposureUs = applied.exposureUs
                // The flicker frequency, not 1 / the locked exposure: the exposure is k / f, and
                // the high-speed choice (fps <= f) and the seed need f.
                lockHz = if (applied.flickerHz > 0.0) applied.flickerHz else (pwm?.frequencyHz() ?: 0.0)
                lockFps = applied.fps
                lockAtMs = SystemClock.elapsedRealtime()
            }
            onStatus(applied)
            val now = SystemClock.elapsedRealtime()
            if (applied.state == NsJni.STATE_LOCKED || probeSinceMs == 0L) {
                probeSinceMs = now
            } else if (now - probeSinceMs > (if (applied.confidence >= 0.25) PROBE_SIGNAL_MS else PROBE_MS)) {
                fallBackToAuto(now, applied)
                return
            }
            recordFrame(image.timestamp, frameExposureNs, applied, lumaMean, false)
            if (needsUpdate(last, applied)) {
                last = applied
                val b = builder!!
                val chars = runCatching { manager.getCameraCharacteristics(activePhysicalId ?: cameraId) }.getOrNull()
                Camera2Sync.apply(b, applied, chars)
                applyZoom(b)
                requestedExposureNs = (cmd.exposureUs * 1000.0).toLong()
                requestedFrameDurationNs = if (applied.fps > 0) (1_000_000_000.0 / applied.fps).toLong() else 0L
                session?.setRepeatingRequest(b.build(), resultCallback, handler)
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "frame dropped: ${e.message}")
        } catch (e: CameraAccessException) {
            Log.w(TAG, "camera lost while analysing: ${e.message}")
            reportLost()
        } catch (e: Exception) {
            // Nothing may escape the ImageReader thread: an uncaught exception kills the process.
            Log.w(TAG, "frame dropped: ${e.javaClass.simpleName} ${e.message}")
        } finally {
            image.close()
        }
    }

    /** Back to the camera's own auto-exposure; probing resumes after REST_MS. */
    private fun fallBackToAuto(now: Long, applied: NsCommand) {
        Log.i(TAG, "no flicker locked in $PROBE_MS ms: auto-exposure for $REST_MS ms")
        restUntilMs = now + REST_MS
        probeSinceMs = 0L
        pwm?.close(); pwm = null
        requestedExposureNs = 0L
        requestedFrameDurationNs = 0L
        timingMismatches = 0
        budgetUsIso = 0.0
        latestAutoBudget = 0.0
        autoWait = 0
        autoSettled = false
        staleFrames = 0
        last = null
        val d = device
        val sess = session
        if (d != null && sess != null) {
            try {
                // A fresh template request: AE on, no manual exposure, ISO or frame duration.
                val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                for (t in surfaceList) if (t !== photoReader?.surface) b.addTarget(t)
                applyZoom(b)
                applyBias(b)
                applyQuality(b)
                builder = b
                sess.setRepeatingRequest(b.build(), resultCallback, handler)
            } catch (e: Exception) {
                Log.w(TAG, "could not return to auto-exposure: ${e.message}")
            }
        }
        onStatus(applied.copy(mode = NsJni.MODE_AUTO, state = NsJni.STATE_SEARCH,
            periodUs = 0.0, confidence = 0.0))
    }

    private fun needsUpdate(prev: NsCommand?, cmd: NsCommand): Boolean {
        if (prev == null) return true
        val de = abs(cmd.exposureUs - prev.exposureUs) / prev.exposureUs
        val di = abs(cmd.iso - prev.iso) / prev.iso
        return de > 0.01 || di > 0.05 || cmd.fps != prev.fps
    }

    fun stop() {
        stopped = true
        // Stop the analysis first: closing the reader while the analysis thread is still
        // reading a plane throws "buffer is inaccessible".
        reader?.setOnImageAvailableListener(null, null)
        analysisThread?.quitSafely()
        try { analysisThread?.join(1000) } catch (_: InterruptedException) {}
        analysisThread = null; handler = null
        session?.close(); session = null
        device?.close(); device = null
        reader?.close(); reader = null
        photoReader?.close(); photoReader = null
        synchronized(this) { onPictureCallback = null }
        burst?.let { synchronized(it) { it.done = true } }; burst = null
        strobe?.close(); strobe = null
        pwm?.close(); pwm = null
        requestedExposureNs = 0L
        latestResultExposureNs = 0L
        staleFrames = 0
        synchronized(resultExposure) { resultExposure.clear() }
        last = null
        budgetUsIso = 0.0
        latestAutoBudget = 0.0
        autoWait = 0
        autoSettled = false
        probeSinceMs = 0L
        restUntilMs = 0L
        mismatchRun = 0
        lastActualNs = 0L
        recordingWarmupFrames = 0
        seedApplied = false
        readyCalled = false
        hsSession = null
    }

    private companion object {
        const val TAG = "nostrobe"
        const val PROBE_MS = 120_000L
        /** Longer while the controller reports a real flicker it is still homing in on. */
        const val PROBE_SIGNAL_MS = 150_000L
        const val REST_MS = 12_000L
        const val FOCUS_HOLD_MS = 3_000L
        const val SIM_LINE_RATE_HZ = 40_000.0
        /** Exposure wanted as a fraction of the frame time once locked: 0.5 = 180 degree shutter. */
        const val SHUTTER_FRACTION = 0.5
        const val LOCK_GRACE_MS = 4_000L
        /** ISO above which a longer exposure is preferred (noise matters more than blur). */
        const val ISO_SOFT_MAX = 3_200.0
        /** ISO the search may use: beyond it the preview and the probes are mostly noise. */
        const val ISO_SEARCH_MAX = 1_600.0
        /** Frames of a still burst: under a strobe the chance that at least one is not lit by a pulse. */
        const val BURST_COUNT = 6
        /** The burst is used as it stands if the pipeline has not delivered everything by then. */
        const val BURST_TIMEOUT_MS = 3_500L
        /** Lowest frame rate the dim-scene rule goes down to (a clip below this is not a video). */
        const val MIN_DIM_FPS = 24.0
        /** Auto-exposure runs this long before an open-loop recording takes over. */
        const val SEED_METER_MS = 1_500L
    }
}
