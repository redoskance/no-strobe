package dev.nostrobe

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.CamcorderProfile
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.color.DynamicColors
import java.io.File
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow

/**
 * True when a codec in the list is an encoder that handles HEVC. Takes (isEncoder, supportedTypes)
 * pairs so it can be unit-tested without MediaCodecInfo.
 */
internal fun hasHevcEncoder(codecs: List<Pair<Boolean, Array<String>>>): Boolean =
    codecs.any { (isEncoder, types) -> isEncoder && types.any { it.equals("video/hevc", ignoreCase = true) } }

/**
 * Preview, status readout, and record button. The shutter is driven by the Rust controller.
 */
class MainActivity : AppCompatActivity() {
    private companion object {
        const val MAX_VIDEO_BITRATE = 30_000_000
    }

    private var camera: NoStrobeCamera? = null
    private var recorder: MediaRecorder? = null
    private lateinit var surfaceView: SurfaceView
    private lateinit var focusRing: android.widget.ImageView
    private lateinit var status: TextView
    private lateinit var recordButton: android.view.View
    private lateinit var lensRow: android.widget.LinearLayout
    private lateinit var flipButton: TextView
    private lateinit var postButton: TextView
    private lateinit var micButton: TextView
    private lateinit var gridButton: TextView
    private lateinit var timerButton: TextView
    private lateinit var gridView: android.view.View
    private lateinit var countdownText: TextView
    private var micOn = true
    // The slow strobe search (1-20 Hz) instead of the fast LED/PWM one: the STROBE segment of the mode selector.
    private var strobeModeOn = false
    // Some devices (tablets, some foldables) have no microphone: no audio, no mic toggle, no permission prompt.
    private val hasMicrophone by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) }
    private var layoutPortrait = true
    private var relayoutAfterRecording = false
    private var timerSecs = 0
    // The last clip recorded and the row rate it was shot with.
    private var lastClip: File? = null
    private var currentRecordingFile: File? = null
    // True once the recorder is started: stop() throws on a recorder that is only prepared.
    @Volatile private var recorderStarted = false
    // The countdown before a recording runs on this handler, so a pause can cancel it.
    private val countdownHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var resumedNow = false
    // One restart after the camera was lost; reset when the camera delivers a status again.
    @Volatile private var cameraRetried = false
    // When the current camera started delivering frames: the retry guard is released only after a
    // few healthy seconds (a camera that opens, errors and reopens would loop once a second).
    @Volatile private var cameraHealthySince = 0L
    private var lastRowRate = 0.0
    private var recFps = 30
    private var hsFps = 0  // > 0 while recording at a high-speed rate chosen from the locked frequency
    // The flicker lock in force when the recording starts: the camera is reopened with the
    // recorder surface and must start on it, not search (probing exposures ruin a clip).
    private var carryExposureUs = 0.0
    private var carryHz = 0.0
    private lateinit var zoomBar: SeekBar
    private lateinit var zoomText: TextView
    private lateinit var exposureBar: SeekBar
    private lateinit var exposureText: TextView

    // Lens and zoom. The default is a pinned physical lens: the phone's logical camera
    // switches lenses by itself, which moves the sensor under the flicker measurement.
    private var lensOptions: List<LensOption> = emptyList()
    private var lensIndex = -1
    private var zoomRatio = 1f
    private var zoomMin = 1f
    private var zoomMax = 1f
    // Exposure compensation: index range and step come from the chosen camera.
    private var evBias = 0f
    private var evStep = 0f
    private var evMinIndex = 0
    private var pendingMeta: java.io.Writer? = null
    private var currentPreviewSize = Size(1920, 1080)
    private var lastScreenWidthDp = 0
    private var lastScreenHeightDp = 0
    private var lastOrientation = 0
    private var lastUiMode = 0
    private var lastDensityDpi = 0
    private var lastFontScale = 0f
    private var lastCommand: NsJni.NsCommand? = null

    /** STROBE: video, searched with the slow strobe controller (1-20 Hz) instead of the fast LED/PWM one. */
    enum class CaptureMode { VIDEO, PHOTO, STROBE }
    private var currentMode = CaptureMode.VIDEO
    private lateinit var modeVideoBtn: TextView
    private lateinit var modePhotoBtn: TextView
    private lateinit var modeStrobeBtn: TextView

    private lateinit var photoButton: android.view.View
    private lateinit var shutterFlash: android.view.View
    private var isSurfaceReady = false
    private var postProcessingListener: ((Boolean) -> Unit)? = null

    /**
     * Hide notification and navigation bars in camera immersive mode (transient on swipe).
     */
    private fun applyImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        val isNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                      android.content.res.Configuration.UI_MODE_NIGHT_YES
        controller.isAppearanceLightStatusBars = !isNight
        controller.isAppearanceLightNavigationBars = !isNight
    }

    /**
     * Choose the largest 16:9 preview size up to 1080p supported by the camera.
     * Setting this explicitly prevents the Camera2 HAL from falling back to square streams (e.g. 1920x1920).
     */
    private fun selectPreviewSize(manager: CameraManager, logicalId: String): Size {
        val chars = try { manager.getCameraCharacteristics(logicalId) } catch (_: Exception) { return Size(1920, 1080) }
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return Size(1920, 1080)
        val sizes = map.getOutputSizes(SurfaceHolder::class.java) ?: return Size(1920, 1080)
        val targetRatio = 16f / 9f
        val matching = sizes.filter { s ->
            val r = s.width.toFloat() / s.height.toFloat()
            kotlin.math.abs(r - targetRatio) < 0.05f && s.height <= 1080 && s.width <= 1920
        }
        return matching.maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.filter { it.height <= 1080 && it.width <= 1920 }.maxByOrNull { it.width.toLong() * it.height }
            ?: Size(1920, 1080)
    }

    /** The debug extras of the adb tests are honoured in debuggable builds only: the activity is exported. */
    private val debugBuild: Boolean
        get() = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    override fun onCreate(savedInstanceState: Bundle?) {
        android.util.Log.i("nostrobe", "lifecycle: onCreate (saved=${savedInstanceState != null})")
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        applyImmersiveMode()
        val config = resources.configuration
        lastScreenWidthDp = config.screenWidthDp
        lastScreenHeightDp = config.screenHeightDp
        lastOrientation = config.orientation
        lastUiMode = config.uiMode
        lastDensityDpi = config.densityDpi
        lastFontScale = config.fontScale
        layoutPortrait = config.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
        setContentView(R.layout.main)
        // A camera app: the screen must not go to sleep while it is in front. A recording longer than
        // the screen timeout (30 s on many phones) paused the activity and ended the clip.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surfaceView = findViewById(R.id.preview)
        val aspectLayout = findViewById<AspectFrameLayout?>(R.id.aspectLayout)
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        if (lensOptions.isEmpty()) {
            lensOptions = LensOptions.list(manager)
            // The lens is found again by its camera ids (a recreate relists the cameras).
            val savedLogical = savedInstanceState?.getString("lens_logical")
            val savedPhysical = savedInstanceState?.getString("lens_physical")
            val savedIdx = if (savedLogical == null) -1 else
                lensOptions.indexOfFirst { it.logicalId == savedLogical && it.physicalId == savedPhysical }
            // Zoom and EV are clamped to the new lens by configureZoom and configureExposure.
            if (savedIdx >= 0 && savedInstanceState != null) {
                zoomRatio = savedInstanceState.getFloat("zoom_ratio", 1f)
                evBias = savedInstanceState.getFloat("ev_bias", 0f)
            }
            val wantFront = savedInstanceState?.getBoolean("selected_is_front", false) ?: false
            lensIndex = if (savedIdx >= 0) {
                savedIdx
            } else if (wantFront) {
                val frontIdx = lensOptions.indexOfFirst { it.label.startsWith("front") }
                if (frontIdx >= 0) frontIdx else LensOptions.defaultIndex(lensOptions)
            } else {
                LensOptions.defaultIndex(lensOptions)
            }
        }
        val initialOption = lensOptions.getOrNull(lensIndex)
        val initialSize = if (initialOption != null) selectPreviewSize(manager, initialOption.logicalId) else Size(1920, 1080)
        currentPreviewSize = initialSize
        surfaceView.holder.setFixedSize(initialSize.width, initialSize.height)
        aspectLayout?.setAspectRatio(initialSize.width, initialSize.height)
        aspectLayout?.setRelativeOrientation(runCatching { sensorOrientation() }.getOrDefault(-1))
        stackIfUpright(aspectLayout)
        focusRing = findViewById(R.id.focusRing)
        val pinch = android.view.ScaleGestureDetector(this,
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                    stepZoom(d.scaleFactor); return true
                }
            })
        var pinched = false
        surfaceView.setOnTouchListener { v, e ->
            pinch.onTouchEvent(e)
            if (pinch.isInProgress || e.pointerCount > 1) pinched = true
            if (e.action == android.view.MotionEvent.ACTION_UP) {
                if (!pinched) {
                    camera?.focusAt(e.x / v.width, e.y / v.height)
                    showFocusRing(e.x, e.y)
                }
                pinched = false
                v.performClick()
            }
            true
        }
        status = findViewById(R.id.status)
        var debugTapCount = 0
        var lastDebugTapMs = 0L
        status.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastDebugTapMs > 1500L) {
                debugTapCount = 0
            }
            lastDebugTapMs = now
            debugTapCount++
            if (debugTapCount in 3..4) {
                val remaining = 5 - debugTapCount
                Toast.makeText(this, getString(R.string.msg_debug_taps_remaining_format, remaining), Toast.LENGTH_SHORT).show()
            } else if (debugTapCount >= 5) {
                debugTapCount = 0
                showDebugDialog()
            }
        }
        status.setOnLongClickListener {
            showDebugDialog()
            true
        }
        recordButton = findViewById(R.id.record)
        modeVideoBtn = findViewById(R.id.modeVideo)
        modePhotoBtn = findViewById(R.id.modePhoto)
        modeStrobeBtn = findViewById(R.id.modeStrobe)
        val savedModeStr = savedInstanceState?.getString("capture_mode")
            ?: prefs().getString("capture_mode", CaptureMode.VIDEO.name) ?: CaptureMode.VIDEO.name
        currentMode = try { CaptureMode.valueOf(savedModeStr) } catch (_: Exception) { CaptureMode.VIDEO }
        // The `strobe_mode` extra (adb tests) opens the app in the STROBE mode.
        if (intent.getBooleanExtra("strobe_mode", false)) currentMode = CaptureMode.STROBE
        strobeModeOn = currentMode == CaptureMode.STROBE

        recordButton.setOnClickListener {
            if (currentMode != CaptureMode.PHOTO) {
                if (recorder == null && timerSecs > 0) startCountdown(timerSecs) { toggleRecord() } else toggleRecord()
            } else {
                if (timerSecs > 0) startCountdown(timerSecs) { takeStillPhoto() } else takeStillPhoto()
            }
        }
        modeVideoBtn.setOnClickListener {
            if (recorder != null) {
                Toast.makeText(this, getString(R.string.msg_cant_switch_mode_recording), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            setCaptureMode(CaptureMode.VIDEO)
        }
        modePhotoBtn.setOnClickListener {
            if (recorder != null) {
                Toast.makeText(this, getString(R.string.msg_cant_switch_mode_recording), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            setCaptureMode(CaptureMode.PHOTO)
        }
        modeStrobeBtn.setOnClickListener {
            if (recorder != null) {
                Toast.makeText(this, getString(R.string.msg_cant_switch_mode_recording), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            setCaptureMode(CaptureMode.STROBE)
        }
        // photoButton must exist before updateModeUI() touches it (it crashed every cold start).
        photoButton = findViewById(R.id.photo)
        photoButton.setOnClickListener { takeStillPhoto() }
        updateModeUI()
        shutterFlash = findViewById(R.id.shutterFlash)
        val listener: (Boolean) -> Unit = { isProcessing ->
            runOnUiThread {
                recordButton.isEnabled = !isProcessing && recorder == null
                recordButton.alpha = if (isProcessing) 0.4f else 1.0f
                photoButton.isEnabled = !isProcessing && recorder == null
                photoButton.alpha = if (isProcessing) 0.4f else 1.0f
                if (isProcessing) {
                    status.text = getString(R.string.post_in_progress)
                }
            }
        }
        postProcessingListener = listener
        OnDevicePost.addListener(listener)
        micButton = findViewById(R.id.mic)
        if (!hasMicrophone) micButton.visibility = android.view.View.GONE
        gridButton = findViewById(R.id.gridToggle)
        timerButton = findViewById(R.id.timer)
        gridView = findViewById(R.id.grid)
        countdownText = findViewById(R.id.countdown)
        micOn = prefs().getBoolean("mic_on", true)
        timerSecs = prefs().getInt("timer_secs", 0)
        gridView.visibility = if (prefs().getBoolean("grid_on", false)) android.view.View.VISIBLE else android.view.View.GONE
        refreshToggles()
        micButton.setOnClickListener {
            micOn = !micOn
            prefs().edit().putBoolean("mic_on", micOn).apply()
            refreshToggles()
        }
        gridButton.setOnClickListener {
            val on = gridView.visibility != android.view.View.VISIBLE
            gridView.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
            prefs().edit().putBoolean("grid_on", on).apply()
            refreshToggles()
        }
        timerButton.setOnClickListener {
            timerSecs = when (timerSecs) { 0 -> 3; 3 -> 10; else -> 0 }
            prefs().edit().putInt("timer_secs", timerSecs).apply()
            refreshToggles()
        }
        lensRow = findViewById(R.id.lensRow)
        flipButton = findViewById(R.id.flip)
        zoomBar = findViewById(R.id.zoom)
        zoomText = findViewById(R.id.zoomText)
        flipButton.setOnClickListener { flipCamera() }
        postButton = findViewById(R.id.post)
        postButton.setOnClickListener { openGallery() }
        postButton.setOnLongClickListener {
            showPostMenu()
            true
        }
        val clipsDir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir, "clips")
        val moviesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "no-strobe")
        val photosDir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir, "photos")
        val dcimDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "no-strobe")
        fun isMedia(f: File) = f.extension.equals("mp4", ignoreCase = true) ||
                               f.extension.equals("jpg", ignoreCase = true) ||
                               f.extension.equals("jpeg", ignoreCase = true) ||
                               f.extension.equals("png", ignoreCase = true)
        val hasMedia = (clipsDir.exists() && clipsDir.listFiles { f -> isMedia(f) }?.isNotEmpty() == true) ||
                       (moviesDir.exists() && moviesDir.listFiles { f -> isMedia(f) }?.isNotEmpty() == true) ||
                       (photosDir.exists() && photosDir.listFiles { f -> isMedia(f) }?.isNotEmpty() == true) ||
                       (dcimDir.exists() && dcimDir.listFiles { f -> isMedia(f) }?.isNotEmpty() == true)
        if (hasMedia) {
            postButton.visibility = android.view.View.VISIBLE
            postButton.text = getString(R.string.btn_gallery)
        }
        exposureBar = findViewById(R.id.exposureBar)
        exposureText = findViewById(R.id.exposureText)
        exposureBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser || evStep <= 0f) return
                evBias = (evMinIndex + progress) * evStep
                exposureText.text = "%+.1f EV".format(evBias)
                camera?.setExposureBias(evBias)
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
        // A tap on the value resets the bias.
        exposureText.setOnClickListener {
            if (evStep <= 0f) return@setOnClickListener
            evBias = 0f
            exposureBar.progress = -evMinIndex
            exposureText.text = "%+.1f EV".format(0f)
            camera?.setExposureBias(0f)
        }
        findViewById<TextView>(R.id.evToggle).setOnClickListener { v ->
            val panel = findViewById<android.view.View>(R.id.evPanel)
            val show = panel.visibility != android.view.View.VISIBLE
            panel.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
            v.isSelected = show
        }
        zoomBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser || zoomMax <= zoomMin) return
                zoomRatio = zoomMin * (zoomMax / zoomMin).pow(progress / 100f)
                zoomText.text = "%.1fx".format(zoomRatio)
                camera?.setZoom(zoomRatio)
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        // The SurfaceView surface is created and destroyed with the window, so the camera
        // session must follow the surface, not the activity lifecycle. A Surface cached
        // across pause/resume is abandoned and createCaptureSession throws.
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                isSurfaceReady = true
                if (camera == null) startCamera()
            }
            override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hgt: Int) {
                isSurfaceReady = true
                if (camera == null) startCamera()
            }
            override fun surfaceDestroyed(h: SurfaceHolder) {
                isSurfaceReady = false
                camera?.stop(); camera = null
            }
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
        }
    }

    /** The manifest keeps the activity alive on fold/rotation, so the layout has to be inflated again by hand. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        android.util.Log.i("nostrobe", "lifecycle: onConfigurationChanged orientation=${newConfig.orientation} (recording=${recorder != null})")
        applyImmersiveMode()
        // The layout is inflated again for a size, a day/night, a density or a font-scale change: those
        // are handled here (the manifest keeps the activity alive), not by a recreate.
        val sizeChanged = newConfig.screenWidthDp != lastScreenWidthDp ||
                          newConfig.screenHeightDp != lastScreenHeightDp ||
                          newConfig.orientation != lastOrientation ||
                          newConfig.uiMode != lastUiMode ||
                          newConfig.densityDpi != lastDensityDpi ||
                          newConfig.fontScale != lastFontScale
        if (!sizeChanged) return
        lastScreenWidthDp = newConfig.screenWidthDp
        lastScreenHeightDp = newConfig.screenHeightDp
        lastOrientation = newConfig.orientation
        lastUiMode = newConfig.uiMode
        lastDensityDpi = newConfig.densityDpi
        lastFontScale = newConfig.fontScale
        if (recorder != null) { relayoutAfterRecording = true; return }
        recreate()
    }

    override fun onResume() {
        super.onResume()
        android.util.Log.i("nostrobe", "lifecycle: onResume")
        applyImmersiveMode()
        resumedNow = true
        if (relayoutAfterRecording && recorder == null) { relayoutAfterRecording = false; recreate(); return }
        // The camera was stopped by a pause during a recording: reopen it on the preview.
        if (camera == null && isSurfaceReady) startCamera()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveMode()
        }
    }

    override fun onPause() {
        android.util.Log.i("nostrobe", "lifecycle: onPause (recording=${recorder != null})")
        // A pause alone is a dialog or a shade over a screen that is still visible: the system's microphone
        // privacy prompt ("unblock the microphone?", when the privacy switch is off), a permission request,
        // the notification shade. It must NOT end a recording (it did: a clip of 0.3 s on a phone
        // whose microphone switch was off). Ending the recording belongs to onStop.
        resumedNow = false
        super.onPause()
    }

    override fun onStop() {
        android.util.Log.i("nostrobe", "lifecycle: onStop (recording=${recorder != null})")
        // Not visible any more (home, another app, screen off): the camera goes with it.
        countdownHandler.removeCallbacksAndMessages(null)
        countdownText.visibility = android.view.View.GONE
        recordButton.isEnabled = !OnDevicePost.isProcessing
        camera?.closeMeta()
        if (recorder != null) endRecordingNow()
        recordButton.isSelected = false
        super.onStop()
    }

    private fun startCamera() {
        cameraHealthySince = 0L
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) return  // started again from onRequestPermissionsResult
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        if (lensOptions.isEmpty()) {
            lensOptions = try { LensOptions.list(manager) } catch (e: Exception) { emptyList() }
            lensIndex = LensOptions.defaultIndex(lensOptions)
        }
        // No camera listed (an emulator without one, a TV box): say so instead of an index error.
        val option = lensOptions.getOrNull(lensIndex)
        if (option == null) {
            Toast.makeText(this, getString(R.string.msg_no_camera), Toast.LENGTH_LONG).show()
            return
        }
        try {
            configureZoom(manager, option)
            configureExposure(manager, option)
        } catch (e: CameraAccessException) {
            android.util.Log.w("nostrobe", "camera characteristics unavailable: ${e.message}")
            Toast.makeText(this, getString(R.string.msg_camera_busy), Toast.LENGTH_LONG).show()
            return
        }
        val holder = surfaceView.holder
        // Note: holder.setFixedSize was configured beforehand (in onCreate or selectLens),
        // ensuring the buffer geometry matches the 16:9 preview stream and eliminates anamorphic distortion.
        camera = NoStrobeCamera(
            manager, option.logicalId, holder.surface, recorder, pwmMode = !strobeModeOn,
            // While recording, do not pin a physical lens: on a foldable the recorder
            // surface of a pinned-lens stream received garbage frames (pure noise). The lock
            // (an exposure that is a multiple of the period) does not depend on the sensor, so
            // the logical camera is fine; the lens chip becomes a zoom ratio instead.
            physicalId = if (recorder != null) null else option.physicalId,
            zoomRatio = if (recorder != null && option.physicalId != null) logicalZoomFor(option) else zoomRatio,
            evBias = evBias,
            simFlickerHz = if (debugBuild) intent.getDoubleExtra("sim_hz", 0.0) else 0.0,
            seedExposureUs = if (recorder != null) carryExposureUs else 0.0,
            seedHz = if (recorder != null) carryHz else 0.0,
            holdAutoNoSeed = recorder != null,
            forceAutoOnly = debugBuild && intent.getBooleanExtra("force_auto_only", false),
            // Recording: no analysis stream (preview + recorder only). With the third stream a
            // A foldable wrote pure noise into the clip; the lock is applied open loop.
            dumpProfiles = debugBuild && intent.getBooleanExtra("dump_profiles", false).also {
                dumpExposuresOverride = intent.getStringExtra("dump_exposures")
                    ?.split(',')?.mapNotNull { v -> v.trim().toDoubleOrNull() }?.takeIf { l -> l.isNotEmpty() }?.toDoubleArray()
            },
            outDir = externalCacheDir ?: cacheDir,
            highSpeedFps = if (recorder != null) hsFps else 0,
            startLevel = if (recorder != null) 2 else if (debugBuild) intent.getIntExtra("session_level", 0) else 0,
            recordFps = recFps,
            onReady = {
                // Session configured with the recorder surface: start recording now.
                val r = recorder
                if (r != null) {
                    try {
                        r.start()
                        recorderStarted = true
                    } catch (e: RuntimeException) {
                        android.util.Log.w("nostrobe", "recorder did not start: ${e.message}")
                        // Nothing is recorded: the recording ends with a message, and its empty file goes.
                        runOnUiThread {
                            if (recorder === r) {
                                Toast.makeText(this, getString(R.string.msg_record_cant_start), Toast.LENGTH_LONG).show()
                                endRecordingNow()
                            }
                        }
                    }
                }
            },
            onFailure = { code -> runOnUiThread { onCameraFailure(code) } },
        ) { cmd ->
            lastCommand = cmd
            val nowMs = android.os.SystemClock.elapsedRealtime()
            if (cameraHealthySince == 0L) cameraHealthySince = nowMs
            else if (nowMs - cameraHealthySince > 5000L) cameraRetried = false
            var s = describe(cmd)
            DiagnosticReport.log("engine", s)
            // Live correction is not available: tell the user when the clip will need it.
            if (recorder != null && cmd.state != NsJni.STATE_LOCKED) s += "  [post needed]"
            android.util.Log.d("nostrobe", s)
            var shortText = shortStatus(cmd)
            if (recorder != null && cmd.state != NsJni.STATE_LOCKED) shortText += " · post"
            runOnUiThread { status.text = shortText }
        }
        camera?.meta = pendingMeta
        if (debugBuild && intent.getBooleanExtra("luma_csv", false) && pendingMeta == null) {
            // Debug: one line per analysed frame (timestamp, exposure, ISO, luma...) to luma.csv.
            val w = java.io.FileWriter(File(externalCacheDir ?: cacheDir, "luma.csv"))
            w.write("timestamp_ns,exposure_ns,iso,luma,state,freq_hz,stale\n")
            camera?.meta = w
        }
        pendingMeta = null
        camera?.start()
    }

    /** Range and step of the exposure compensation of the chosen camera. */
    private fun configureExposure(manager: CameraManager, option: LensOption) {
        val chars = manager.getCameraCharacteristics(option.logicalId)
        val range = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
        if (range == null || step <= 0f || range.upper <= range.lower) {
            evStep = 0f
            exposureBar.isEnabled = false
            exposureBar.max = 0
            exposureText.text = "n/a"
            return
        }
        evStep = step
        evMinIndex = range.lower
        exposureBar.isEnabled = true
        exposureBar.max = range.upper - range.lower
        evBias = evBias.coerceIn(range.lower * step, range.upper * step)
        exposureBar.progress = Math.round(evBias / step) - evMinIndex
        exposureText.text = "%+.1f EV".format(evBias)
    }

    /** Zoom ratio on the logical camera that frames like [option]'s lens times the user zoom. */
    private fun logicalZoomFor(option: LensOption): Float {
        val main = lensOptions.getOrNull(LensOptions.defaultIndex(lensOptions))?.equivMm ?: 0.0
        val ratio = if (main > 0.0 && option.equivMm > 0.0) (option.equivMm / main).toFloat() else 1f
        var lo = 0.5f
        var hi = 8f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val manager = getSystemService(CAMERA_SERVICE) as CameraManager
            manager.getCameraCharacteristics(option.logicalId)
                .get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { lo = it.lower; hi = it.upper }
        }
        return (ratio * zoomRatio).coerceIn(lo, hi)
    }

    /** Zoom range of the chosen camera; a pinned lens starts at its own 1x. */
    private fun configureZoom(manager: CameraManager, option: LensOption) {
        refreshLensChips()
        var lo = 1f
        var hi = 1f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val r = manager.getCameraCharacteristics(option.logicalId)
                .get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (r != null) { lo = r.lower; hi = r.upper }
        }
        if (option.physicalId != null) { lo = maxOf(lo, 1f); hi = min(hi, 8f) }
        zoomMin = lo
        zoomMax = maxOf(hi, lo)
        zoomRatio = zoomRatio.coerceIn(zoomMin, zoomMax)
        zoomBar.isEnabled = zoomMax > zoomMin
        android.util.Log.i("nostrobe", "zoom range %.2f..%.2f (logical %.2f..%.2f) lens=%s".format(zoomMin, zoomMax, lo, hi, option.label))
        zoomBar.progress = if (zoomMax > zoomMin) {
            (100f * ln(zoomRatio / zoomMin) / ln(zoomMax / zoomMin)).toInt()
        } else 0
        zoomText.text = "%.1fx".format(zoomRatio)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, getString(R.string.msg_camera_perm_required), Toast.LENGTH_LONG).show()
            return
        }
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED &&
            surfaceView.holder.surface.isValid && camera == null
        ) startCamera()
    }

    /** The ring appears at the tap, shrinks onto it and fades out. */
    private fun showFocusRing(x: Float, y: Float) {
        focusRing.animate().cancel()
        // The ring is GONE between taps, so its measured size can be 0: use the layout size.
        val size = 76f * resources.displayMetrics.density
        focusRing.translationX = x - size / 2f
        focusRing.translationY = y - size / 2f
        focusRing.visibility = android.view.View.VISIBLE
        focusRing.alpha = 1f
        focusRing.scaleX = 1.5f
        focusRing.scaleY = 1.5f
        focusRing.animate().scaleX(1f).scaleY(1f).setDuration(200).withEndAction {
            focusRing.animate().alpha(0f).setStartDelay(900).setDuration(300).withEndAction {
                focusRing.visibility = android.view.View.GONE
            }.start()
        }.start()
    }

    /** First camcorder profile the device has, 1080p preferred; null when it has none. */
    private fun pickProfile(): CamcorderProfile? {
        for (q in intArrayOf(CamcorderProfile.QUALITY_1080P, CamcorderProfile.QUALITY_720P,
            CamcorderProfile.QUALITY_HIGH, CamcorderProfile.QUALITY_480P)) {
            try {
                if (CamcorderProfile.hasProfile(q)) return CamcorderProfile.get(q)
            } catch (_: RuntimeException) {}
        }
        return null
    }

    /**
     * Rotation to store in the clip so it plays upright: the sensor's mounting angle corrected
     * by how the screen is turned (a foldable held sideways records landscape).
     */
    /**
     * A tablet or an unfolded foldable held upright (clearly taller than wide): the side-by-side layout gives
     * the whole width to the viewfinder and the controls none. Stack them instead, viewfinder on top and
     * the controls under it. A nearly square screen (an unfolded foldable) keeps the side-by-side layout.
     */
    private fun stackIfUpright(aspect: AspectFrameLayout?) {
        val panel = findViewById<android.view.View?>(R.id.rightPanel) ?: return
        val root = panel.parent as? android.widget.LinearLayout ?: return
        val c = resources.configuration
        if (aspect == null || c.screenHeightDp < c.screenWidthDp * 1.25) return
        root.orientation = android.widget.LinearLayout.VERTICAL
        aspect.layoutParams = android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        aspect.maxHeightShare = 0.6f
        panel.layoutParams = android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    }

    private fun sensorOrientation(): Int {
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        val option = lensOptions.getOrNull(lensIndex) ?: return 90
        val chars = manager.getCameraCharacteristics(option.logicalId)
        val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        @Suppress("DEPRECATION")
        val screen = when (windowManager.defaultDisplay.rotation) {
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
        val front = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        return if (front) (sensor + screen) % 360 else (sensor - screen + 360) % 360
    }

    /**
     * A constrained high-speed mode this camera lists whose frame time still holds an exposure
     * of one flicker period (rate <= 0.9 x f), the fastest and then the largest size up to
     * 1080p: (fps, width, height), or null (then the normal 30/60 fps recording).
     */
    private fun pickHighSpeed(hz: Double): Triple<Int, Int, Int>? {
        val option = lensOptions.getOrNull(lensIndex) ?: return null
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        val map = manager.getCameraCharacteristics(option.logicalId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val ranges = try { map.highSpeedVideoFpsRanges } catch (_: Exception) { return null }
        return HighSpeedPick.pick(
            ranges.map { HighSpeedPick.FpsRange(it.lower, it.upper) },
            { r -> map.getHighSpeedVideoSizesFor(android.util.Range(r.lower, r.upper))
                .map { HighSpeedPick.Size(it.width, it.height) } },
            hz
        )
    }

    private fun selectLens(index: Int) {
        if (index == lensIndex || index !in lensOptions.indices) return
        val option = lensOptions[index]
        if (recorder != null) {
            val current = lensOptions.getOrNull(lensIndex)
            val isCurrentFront = current?.label?.startsWith("front") == true
            val isTargetFront = option.label.startsWith("front")
            if (isCurrentFront != isTargetFront || current?.logicalId != option.logicalId) {
                Toast.makeText(this, getString(R.string.msg_cant_switch_lens_recording), Toast.LENGTH_SHORT).show()
                return
            }
            // Same logical camera (back lenses 0.5x, 1x, 5x, auto): switch smoothly via zoom without restarting
            lensIndex = index
            zoomRatio = logicalZoomFor(option)
            if (zoomMax > zoomMin) {
                zoomBar.progress = (100f * ln(zoomRatio / zoomMin) / ln(zoomMax / zoomMin)).toInt().coerceIn(0, 100)
            }
            zoomText.text = "%.1fx".format(zoomRatio)
            camera?.setZoom(zoomRatio)
            refreshLensChips()
            return
        }
        lensIndex = index
        zoomRatio = 1f
        camera?.stop()
        camera = null
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        val pSize = selectPreviewSize(manager, option.logicalId)
        // A front camera is turned the other way round: the box may change from portrait to landscape.
        findViewById<AspectFrameLayout?>(R.id.aspectLayout)?.setRelativeOrientation(runCatching { sensorOrientation() }.getOrDefault(-1))
        if (pSize != currentPreviewSize) {
            currentPreviewSize = pSize
            surfaceView.holder.setFixedSize(pSize.width, pSize.height)
            findViewById<AspectFrameLayout?>(R.id.aspectLayout)?.setAspectRatio(pSize.width, pSize.height)
            surfaceView.postDelayed({
                if (camera == null && isSurfaceReady) {
                    startCamera()
                }
            }, 100)
        } else {
            if (isSurfaceReady) {
                startCamera()
            }
        }
    }

    /** Back <-> front. Going back, return to the lens used last on the back. */
    private var lastBackIndex = -1
    private fun flipCamera() {
        if (lensOptions.isEmpty()) return
        if (recorder != null) {
            Toast.makeText(this, getString(R.string.msg_cant_switch_camera_recording), Toast.LENGTH_SHORT).show()
            return
        }
        val isFront = lensOptions.getOrNull(lensIndex)?.label?.startsWith("front") == true
        if (isFront) {
            selectLens(if (lastBackIndex in lensOptions.indices) lastBackIndex else LensOptions.defaultIndex(lensOptions))
        } else {
            lastBackIndex = lensIndex
            val front = lensOptions.indexOfFirst { it.label.startsWith("front") }
            if (front >= 0) selectLens(front)
        }
    }

    /** One chip per lens, labelled by magnification relative to the main lens (0.5x, 1x, 5x). */
    private fun refreshLensChips() {
        lensRow.removeAllViews()
        val main = lensOptions.getOrNull(LensOptions.defaultIndex(lensOptions))?.equivMm ?: 0.0
        for ((i, o) in lensOptions.withIndex()) {
            if (o.label.startsWith("front")) continue
            val text = when {
                o.physicalId == null && o.equivMm == 0.0 -> "auto"
                main > 0 && o.equivMm > 0 -> {
                    val m = o.equivMm / main
                    (if (m >= 10 || Math.abs(m - Math.round(m)) < 0.05) "%.0f" else "%.1f").format(m) + "x"
                }
                else -> o.label
            }
            val dp = resources.displayMetrics.density
            val b = TextView(this).apply {
                this.text = text
                gravity = android.view.Gravity.CENTER
                textSize = 13f
                setTextColor(androidx.core.content.res.ResourcesCompat.getColorStateList(resources, R.color.chip_text, theme))
                setBackgroundResource(R.drawable.bg_lens_chip)
                setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
                isSelected = i == lensIndex
                setOnClickListener { selectLens(i) }
            }
            lensRow.addView(b, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = (6 * dp).toInt() })
        }
    }

    private fun stepZoom(factor: Float) {
        if (zoomMax <= zoomMin) return
        zoomRatio = (zoomRatio * factor).coerceIn(zoomMin, zoomMax)
        zoomBar.progress = (100f * ln(zoomRatio / zoomMin) / ln(zoomMax / zoomMin)).toInt()
        zoomText.text = "%.1fx".format(zoomRatio)
        camera?.setZoom(zoomRatio)
    }

    private fun toggleRecord() {
        if (recorder != null) {
            val (total, locked) = camera?.recordingStats() ?: (0 to 0)
            lastRowRate = camera?.rowRateHz ?: 0.0
            camera?.closeMeta()
            try { recorder?.stop() } catch (e: RuntimeException) {
                android.util.Log.w("nostrobe", "recorder stopped without data: ${e.message}")
            }
            recorder?.release(); recorder = null
            recorderStarted = false
            hsFps = 0
            recordButton.isSelected = false
            updateModeUI()
            flipButton.isEnabled = true
            flipButton.alpha = 1.0f
            photoButton.isEnabled = !OnDevicePost.isProcessing
            photoButton.alpha = if (OnDevicePost.isProcessing) 0.4f else 1.0f
            micButton.isEnabled = true
            micButton.alpha = 1.0f
            timerButton.isEnabled = true
            timerButton.alpha = 1.0f
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            finishClip()
            notifyPost(total, locked)
            if (relayoutAfterRecording) { relayoutAfterRecording = false; recreate(); return }
            camera?.stop(); startCamera()
            return
        }
        if (OnDevicePost.isProcessing) {
            Toast.makeText(this, getString(R.string.msg_processing_wait_record), Toast.LENGTH_SHORT).show()
            return
        }
        photoButton.isEnabled = false
        photoButton.alpha = 0.4f
        // The encoder may refuse this configuration (high speed, HEVC, 1080p): once more with the
        // one every device has, before giving up.
        if (!startRecording(conservative = false) && !startRecording(conservative = true)) {
            photoButton.isEnabled = !OnDevicePost.isProcessing
            photoButton.alpha = if (OnDevicePost.isProcessing) 0.4f else 1.0f
            Toast.makeText(this, getString(R.string.msg_record_cant_start), Toast.LENGTH_LONG).show()
        }
    }

    /** Sets up the recorder and prepares it; false when the encoder refuses the configuration. */
    private fun startRecording(conservative: Boolean): Boolean {
        // The lock in force now, with the frame rate it was found at (a dim scene runs at a lower
        // rate): the recording must use that rate, or the exposure would not fit in the frame.
        val carriedLock = camera?.carryLock() ?: Triple(0.0, 0.0, 0.0)
        val hs = if (!conservative && carriedLock.second > 0.0) pickHighSpeed(carriedLock.second) else null
        hsFps = hs?.first ?: 0
        // 1080p keeps the encoder and the lens stream manageable; QUALITY_HIGH can be 4K.
        val p = pickProfile()
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
        // The call order matters: the source first, then the format, then the encoders.
        // Video only for now: audio would need the RECORD_AUDIO permission.
        val withAudio = hs == null && micOn && hasMicrophone && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!conservative && hs == null && micOn && hasMicrophone && !withAudio) {
            // Ask once; this clip is recorded without sound, the next one will have it.
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            Toast.makeText(this, getString(R.string.msg_mic_perm_required), Toast.LENGTH_SHORT).show()
        }
        if (withAudio) r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        // Cap the requested rate (the profile asks 33 Mbit/s at 1080p60). Some phones' encoders
        // still overshoots on very noisy footage: measured ~143 Mbit/s in a dark scene.
        // A device with no camcorder profile at all gets plain 720p30.
        val rate = p?.videoBitRate ?: 8_000_000
        val fps = (if (debugBuild) intent.getIntExtra("rec_fps", 0) else 0).takeIf { it > 0 }
            ?: carriedLock.third.toInt().takeIf { it > 0 }
            ?: (if (conservative) 30 else fpsFor(p?.videoFrameRate ?: 30))
        // The size the chosen camera outputs to a recorder (the profile is camera 0's).
        val rs = if (conservative) null else recorderSize(p?.let { Size(it.videoFrameWidth, it.videoFrameHeight) })
        val vw = if (conservative) 1280 else rs?.width ?: p?.videoFrameWidth ?: 1280
        val vh = if (conservative) 720 else rs?.height ?: p?.videoFrameHeight ?: 720
        // HEVC when the device has an encoder for it: at the same rate it keeps far less noise
        // than H.264, which matters at ISO 1750 in a dark room (one phone's H.264 encoder
        // measured ~143 Mbit/s on a noisy scene against the 30 Mbit/s asked). HEVC needs about
        // 60 % of the H.264 rate for the same quality, so the cap is lowered with it.
        val hevc = !conservative && hevcEncoder()
        val askBps = if (hs != null) 60_000_000
        else if (conservative) minOf(rate, 16_000_000)
        else if (hevc) minOf((rate * 0.6).toInt(), MAX_VIDEO_BITRATE) else minOf(rate, MAX_VIDEO_BITRATE)
        r.setVideoEncodingBitRate(askBps)
        android.util.Log.i("nostrobe", "recorder: profile %d bps -> asked %d bps, %s, %dx%d @ %d".format(
            rate, askBps, if (hevc) "HEVC" else "H.264", vw, vh, fps))
        if (hs != null) {
            // High speed: the rate and size the camera lists, the capture rate = the frame rate
            // (real-time video, not slow motion), and a rate that fits a 240 fps stream.
            r.setVideoFrameRate(hs.first)
            r.setCaptureRate(hs.first.toDouble())
            recFps = hs.first
            r.setVideoSize(hs.second, hs.third)
            android.util.Log.i("nostrobe", "high speed chosen: ${hs.first} fps ${hs.second}x${hs.third} for %.1f Hz".format(carriedLock.second))
        } else {
            r.setVideoFrameRate(fps)
            recFps = fps
            r.setVideoSize(vw, vh)
        }
        r.setVideoEncoder(if (hevc) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
        if (withAudio) {
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128_000)
            r.setAudioSamplingRate(44_100)
        }
        r.setOrientationHint(sensorOrientation())
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        val clipsDir = File(dir, "clips").apply { mkdirs() }
        val clipFile = File(clipsDir, "nostrobe-$timestamp.mp4")
        currentRecordingFile = clipFile
        r.setOutputFile(clipFile.path)
        try {
            r.prepare()
        } catch (e: Exception) {
            // The encoder refused this configuration (IOException, or a RuntimeException from the codec).
            android.util.Log.w("nostrobe", "recorder refused (conservative=$conservative): ${e.message}")
            try { r.release() } catch (_: RuntimeException) {}
            clipFile.delete()
            currentRecordingFile = null
            hsFps = 0
            return false
        }
        recorderStarted = false
        recorder = r
        flipButton.isEnabled = false
        flipButton.alpha = 0.4f
        micButton.isEnabled = false
        micButton.alpha = 0.4f
        timerButton.isEnabled = false
        timerButton.alpha = 0.4f
        // The activity survives rotation (configChanges in the manifest), so the recording is
        // not cut; the clip's orientation hint is read from the screen at this point.
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
        carryExposureUs = carriedLock.first
        carryHz = carriedLock.second
        recordButton.isSelected = true
        updateModeUI()
        val w = java.io.FileWriter(File(externalCacheDir ?: cacheDir, "clip.csv"))
        w.write("timestamp_ns,exposure_ns,iso,luma,state,freq_hz,stale\n")
        pendingMeta = w
        // The recorder surface is added by the session, so rebuild it; the recorder starts
        // once the session is configured (MediaRecorder needs frames to flow to its surface).
        camera?.stop(); startCamera()
        return true
    }

    /** Largest 16:9 size up to 1080p that the chosen camera outputs to a MediaRecorder; null when unknown. */
    private fun recorderSize(preferred: Size? = null): Size? {
        val option = lensOptions.getOrNull(lensIndex) ?: return null
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        val map = runCatching { manager.getCameraCharacteristics(option.logicalId) }.getOrNull()
            ?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val sizes = map.getOutputSizes(MediaRecorder::class.java) ?: return null
        // The profile's own size (what clips were recorded at before) when this camera outputs it.
        if (preferred != null && sizes.any { it.width == preferred.width && it.height == preferred.height }) return preferred
        val fits = sizes.filter { it.height <= 1080 && it.width <= 1920 }
        val matching = fits.filter { s -> kotlin.math.abs(s.width.toFloat() / s.height - 16f / 9f) < 0.05f }
        return matching.ifEmpty { fits }.maxByOrNull { it.width.toLong() * it.height }
    }

    /** The profile's frame rate when an AE rate range of the chosen camera contains it. */
    private fun fpsFor(profileFps: Int): Int {
        val option = lensOptions.getOrNull(lensIndex) ?: return profileFps
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        val ranges = runCatching { manager.getCameraCharacteristics(option.logicalId) }.getOrNull()
            ?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return profileFps
        if (ranges.any { profileFps in it.lower..it.upper }) return profileFps
        return ranges.map { it.upper }.filter { it <= 60 }.maxOrNull() ?: profileFps
    }

    /**
     * Ends a recording without the stop button (pause, lost camera). A recorder still in the
     * warm-up never started: its file goes. A clip that did start is finished like the stop
     * button does, so it is not lost. The camera is stopped: its session holds the released
     * recorder surface, and it is started again on the preview.
     */
    private fun endRecordingNow() {
        val r = recorder ?: return
        val started = recorderStarted
        val (total, locked) = camera?.recordingStats() ?: (0 to 0)
        lastRowRate = camera?.rowRateHz ?: 0.0
        camera?.closeMeta()
        if (started) {
            try { r.stop() } catch (e: RuntimeException) {
                android.util.Log.w("nostrobe", "recorder stopped without data: ${e.message}")
            }
        }
        try { r.release() } catch (_: RuntimeException) {}
        recorder = null
        recorderStarted = false
        hsFps = 0
        carryExposureUs = 0.0
        carryHz = 0.0
        camera?.stop(); camera = null
        recordButton.isSelected = false
        recordButton.isEnabled = !OnDevicePost.isProcessing
        updateModeUI()
        flipButton.isEnabled = true
        flipButton.alpha = 1.0f
        photoButton.isEnabled = !OnDevicePost.isProcessing
        photoButton.alpha = if (OnDevicePost.isProcessing) 0.4f else 1.0f
        micButton.isEnabled = true
        micButton.alpha = 1.0f
        timerButton.isEnabled = true
        timerButton.alpha = 1.0f
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (started) { finishClip(); notifyPost(total, locked) } else currentRecordingFile?.delete()
        currentRecordingFile = null
    }

    /**
     * The camera failed (CAMERA_FAIL_* codes). A recording in progress is ended first; a lost
     * camera is reopened once while the activity is resumed, otherwise the user is told.
     */
    private fun onCameraFailure(code: Int) {
        cameraHealthySince = 0L
        if (recorder != null) endRecordingNow()
        camera?.stop(); camera = null
        when (code) {
            CAMERA_FAIL_LOST -> {
                if (!cameraRetried && resumedNow) {
                    cameraRetried = true
                    surfaceView.postDelayed({
                        if (camera == null && isSurfaceReady && resumedNow) startCamera()
                    }, 1000)
                } else {
                    Toast.makeText(this, getString(R.string.msg_camera_disconnected), Toast.LENGTH_LONG).show()
                }
            }
            CAMERA_FAIL_NO_RECORD -> {
                Toast.makeText(this, getString(R.string.msg_camera_cant_record), Toast.LENGTH_LONG).show()
                if (resumedNow && isSurfaceReady) startCamera()  // the preview goes on
            }
            CAMERA_FAIL_NO_SESSION ->
                Toast.makeText(this, getString(R.string.msg_camera_config_failed), Toast.LENGTH_LONG).show()
            else ->
                Toast.makeText(this, getString(R.string.msg_camera_busy), Toast.LENGTH_LONG).show()
        }
    }

    /** An encoder for HEVC exists on this device (the phones tested do). */
    private fun hevcEncoder(): Boolean = hasHevcEncoder(
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.map { it.isEncoder to it.supportedTypes })

    /** The clip is in the cache; copy it to Movies/no-strobe so the gallery sees it. */
    private fun finishClip() {
        val clip = currentRecordingFile ?: File(externalCacheDir ?: cacheDir, "clip.mp4")
        if (!clip.exists() || clip.length() == 0L) return
        lastClip = clip
        postButton.visibility = android.view.View.VISIBLE
        postButton.text = getString(R.string.btn_gallery)
        val name = clip.name
        Thread {
            val where = saveToGallery(clip, name)
            runOnUiThread {
                Toast.makeText(this, if (where != null) getString(R.string.msg_video_saved_format, where) else getString(R.string.msg_video_saved),
                    Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    /** Movies/no-strobe through MediaStore (no permission needed from Android 10). */
    private fun saveToGallery(src: File, displayName: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/no-strobe")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri: Uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
                contentResolver.openOutputStream(uri)?.use { o -> src.inputStream().use { it.copyTo(o) } }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                "Movies/no-strobe/$displayName"
            } else {
                // Before Android 10 a shared-storage write needs a runtime permission: keep the
                // copy in the app's own movies folder instead.
                val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
                src.copyTo(File(dir, displayName), overwrite = true).path
            }
        } catch (e: Exception) {
            android.util.Log.w("nostrobe", "gallery save failed: ${e.message}")
            null
        }
    }

    private fun refreshToggles() {
        micButton.text = if (micOn) getString(R.string.btn_mic) else getString(R.string.btn_mic_muted)
        micButton.isSelected = micOn
        val gridOn = gridView.visibility == android.view.View.VISIBLE
        gridButton.text = getString(R.string.btn_grid)
        gridButton.isSelected = gridOn
        timerButton.text = if (timerSecs == 0) getString(R.string.btn_timer) else "${timerSecs}s"
        timerButton.isSelected = timerSecs > 0
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val current = lensOptions.getOrNull(lensIndex)
        outState.putBoolean("selected_is_front", current?.label?.startsWith("front") == true)
        current?.let {
            outState.putString("lens_logical", it.logicalId)
            outState.putString("lens_physical", it.physicalId)
        }
        outState.putFloat("zoom_ratio", zoomRatio)
        outState.putFloat("ev_bias", evBias)
        outState.putString("capture_mode", currentMode.name)
    }

    override fun onDestroy() {
        android.util.Log.i("nostrobe", "lifecycle: onDestroy (changingConfig=$isChangingConfigurations)")
        postProcessingListener?.let { OnDevicePost.removeListener(it) }
        super.onDestroy()
    }

    private fun setCaptureMode(mode: CaptureMode) {
        val wasStrobe = currentMode == CaptureMode.STROBE
        currentMode = mode
        prefs().edit().putString("capture_mode", mode.name).apply()
        updateModeUI()
        // The strobe search is another controller: the camera restarts with it (not for VIDEO <-> PHOTO).
        val strobe = mode == CaptureMode.STROBE
        if (strobe != wasStrobe) {
            strobeModeOn = strobe
            Toast.makeText(this, if (strobe) getString(R.string.msg_strobe_mode_slow) else getString(R.string.msg_strobe_mode_fast), Toast.LENGTH_SHORT).show()
            camera?.stop(); camera = null
            if (isSurfaceReady) startCamera()
        }
    }

    private fun updateModeUI() {
        if (!::modeVideoBtn.isInitialized || !::modePhotoBtn.isInitialized || !::modeStrobeBtn.isInitialized ||
            !::photoButton.isInitialized) return
        val selected = when (currentMode) {
            CaptureMode.VIDEO -> modeVideoBtn
            CaptureMode.PHOTO -> modePhotoBtn
            CaptureMode.STROBE -> modeStrobeBtn
        }
        for (b in listOf(modeVideoBtn, modePhotoBtn, modeStrobeBtn)) {
            if (b === selected) {
                b.setBackgroundResource(R.drawable.bg_mode_selected)
                // The theme's text colour: white on the pale strip of the light theme was unreadable.
                b.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.m3_on_surface))
            } else {
                b.background = null
                b.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.m3_on_surface))
            }
        }
        if (currentMode != CaptureMode.PHOTO) {
            recordButton.setBackgroundResource(if (recorder != null) R.drawable.capture_recording else R.drawable.capture_button)
            recordButton.contentDescription = "record"
            photoButton.visibility = android.view.View.VISIBLE
        } else {
            recordButton.setBackgroundResource(R.drawable.capture_idle)
            recordButton.contentDescription = "photo"
            photoButton.visibility = android.view.View.GONE
        }
    }

    private fun takeStillPhoto() {
        if (recorder != null || OnDevicePost.isProcessing) return
        val cam = camera ?: return
        runOnUiThread {
            shutterFlash.visibility = android.view.View.VISIBLE
            shutterFlash.alpha = 0.75f
            shutterFlash.animate().alpha(0f).setDuration(180L).withEndAction {
                shutterFlash.visibility = android.view.View.GONE
            }.start()
        }
        val orientation = sensorOrientation()
        cam.takePicture(orientation, onFailure = {
            runOnUiThread { Toast.makeText(this, getString(R.string.msg_photo_error), Toast.LENGTH_SHORT).show() }
        }) { bytes ->
            savePhoto(bytes)
        }
    }

    private fun savePhoto(bytes: ByteArray) {
        val appPhotosDir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir, "photos").apply { mkdirs() }
        val dateStr = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val name = "nostrobe-shot-" + dateStr + ".jpg"
        val appFile = File(appPhotosDir, name)
        // The app's own folder: no permission needed, and the gallery reads it.
        var saved = false
        try {
            appFile.writeBytes(bytes)
            lastClip = appFile
            saved = true
        } catch (e: Exception) {
            android.util.Log.w("nostrobe", "failed to save photo in the app folder: ${e.message}")
        }
        // A copy for the system gallery. From Android 10 only MediaStore may write DCIM: a java.io
        // write there is refused (EACCES) and used to abort this function before the toast.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var uri: Uri? = null
            try {
                val cv = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "DCIM/no-strobe")
                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                }
                uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { os -> os.write(bytes) }
                        ?: throw java.io.IOException("no output stream for the photo")
                    cv.clear()
                    cv.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(uri, cv, null, null)
                    saved = true
                }
            } catch (e: Exception) {
                android.util.Log.w("nostrobe", "failed to copy the photo to DCIM: ${e.message}")
                try { uri?.let { contentResolver.delete(it, null, null) } } catch (_: Exception) {}
            }
        }
        runOnUiThread {
            if (saved) {
                postButton.visibility = android.view.View.VISIBLE
                postButton.text = getString(R.string.btn_gallery)
                Toast.makeText(this, getString(R.string.msg_photo_saved), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.msg_photo_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Counts down on the preview, then runs [then] (starting the recording). */
    private fun startCountdown(seconds: Int, then: () -> Unit) {
        recordButton.isEnabled = false
        countdownText.visibility = android.view.View.VISIBLE
        val handler = countdownHandler
        var left = seconds
        val tick = object : Runnable {
            override fun run() {
                if (left > 0) {
                    countdownText.text = left.toString()
                    left -= 1
                    handler.postDelayed(this, 1000)
                } else {
                    countdownText.visibility = android.view.View.GONE
                    recordButton.isEnabled = true
                    then()
                }
            }
        }
        handler.post(tick)
    }

    private fun prefs() = getSharedPreferences("nostrobe", MODE_PRIVATE)

    private fun showDebugDialog() {
        DiagnosticReport.showDialog(
            activity = this,
            camera = camera,
            lastCmd = lastCommand,
            previewSize = currentPreviewSize,
        )
    }

    private fun openGallery(clip: File? = lastClip) {
        if (recorder != null) {
            Toast.makeText(this, getString(R.string.msg_cant_open_gallery_recording), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, GalleryActivity::class.java).apply {
            clip?.let { putExtra("clip_path", it.absolutePath) }
            putExtra("line_rate", if (lastRowRate > 0.0) lastRowRate else (camera?.rowRateHz ?: 0.0))
        }
        startActivity(intent)
    }

    private fun showPostMenu() {
        if (recorder != null) {
            Toast.makeText(this, getString(R.string.msg_cant_open_menu_recording), Toast.LENGTH_SHORT).show()
            return
        }
        val items = arrayOf(
            getString(R.string.menu_quick_gallery),
            getString(R.string.menu_post_local),
            getString(R.string.menu_post_lan),
            getString(R.string.menu_lan_settings)
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.menu_post_gallery_title))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openGallery()
                    1 -> fixOnPhone()
                    2 -> sendToDesktop()
                    else -> showServerSettings()
                }
            }
            .show()
    }

    private fun showServerSettings() {
        LanServerConfig.showDialog(this)
    }

    /** Brightness-pumping and anti-banding fix on the phone itself (no network). */
    private fun fixOnPhone() {
        if (recorder != null) {
            Toast.makeText(this, getString(R.string.msg_cant_process_recording), Toast.LENGTH_SHORT).show()
            return
        }
        if (OnDevicePost.isProcessing) {
            Toast.makeText(this, getString(R.string.post_already_running), Toast.LENGTH_SHORT).show()
            return
        }
        val clip = lastClip
        if (clip == null || !clip.exists() || clip.length() == 0L) {
            Toast.makeText(this, getString(R.string.msg_no_clip_to_process), Toast.LENGTH_SHORT).show(); return
        }
        if (clip.extension != "mp4") {
            Toast.makeText(this, getString(R.string.msg_not_a_video_clip), Toast.LENGTH_SHORT).show(); return
        }
        val out = File(clip.parentFile, clip.nameWithoutExtension + "-fixed.mp4")
        postButton.isEnabled = false
        Thread {
            try {
                val t0 = System.currentTimeMillis()
                android.util.Log.i("nostrobe", "phone post: start (${clip.length()} bytes)")
                val lr = if (lastRowRate > 0.0) lastRowRate else (camera?.rowRateHz ?: 0.0)
                OnDevicePost.fix(clip, out, lr) { pct ->
                    runOnUiThread { status.text = getString(R.string.post_progress_local_format, pct) }
                }
                val fixedName = out.name
                android.util.Log.i("nostrobe", "phone post: done in ${System.currentTimeMillis() - t0} ms, ${out.length()} bytes")
                val where = saveToGallery(out, fixedName)
                runOnUiThread {
                    status.text = getString(R.string.post_complete_seconds_format, (System.currentTimeMillis() - t0) / 1000)
                    Toast.makeText(this, if (where != null) getString(R.string.post_clip_ready_format, where) else getString(R.string.post_clip_ready),
                        Toast.LENGTH_LONG).show()
                    openGallery(clip)
                }
            } catch (e: Exception) {
                android.util.Log.w("nostrobe", "phone post failed: ${e.javaClass.simpleName}: ${e.message}")
                runOnUiThread {
                    status.text = getString(R.string.post_failed)
                    Toast.makeText(this, getString(R.string.post_error_format, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            } finally {
                runOnUiThread { postButton.isEnabled = true }
            }
        }.start()
    }

    private fun sendToDesktop() {
        if (recorder != null) {
            Toast.makeText(this, getString(R.string.msg_cant_send_recording), Toast.LENGTH_SHORT).show()
            return
        }
        val clip = lastClip
        if (clip == null || !clip.exists()) {
            Toast.makeText(this, getString(R.string.msg_no_clip_to_send), Toast.LENGTH_SHORT).show(); return
        }
        if (clip.extension != "mp4") {
            Toast.makeText(this, getString(R.string.msg_not_a_video_send), Toast.LENGTH_SHORT).show(); return
        }
        val base = LanServerConfig.getServerUrl(this)
        if (base.isBlank()) {
            LanServerConfig.showDialog(this) { _, _ -> sendToDesktop() }
            return
        }
        val token = LanServerConfig.getServerToken(this)
        val out = File(clip.parentFile, "clip-fixed.mp4")
        postButton.isEnabled = false
        val rate = lastRowRate
        Thread {
            try {
                DesktopPost.send(clip, out, base, token, rate) { pct ->
                    runOnUiThread { status.text = getString(R.string.post_progress_lan_format, pct) }
                }
                val name = "nostrobe-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                    .format(java.util.Date()) + "-fixed.mp4"
                val where = saveToGallery(out, name)
                runOnUiThread {
                    status.text = getString(R.string.lan_complete)
                    Toast.makeText(this, if (where != null) getString(R.string.post_clip_ready_format, where) else getString(R.string.post_clip_ready),
                        Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                android.util.Log.w("nostrobe", "desktop post failed: ${e.message}")
                runOnUiThread {
                    status.text = getString(R.string.lan_failed)
                    Toast.makeText(this, getString(R.string.lan_error_format, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            } finally {
                runOnUiThread { postButton.isEnabled = true }
            }
        }.start()
    }

    /** Live correction is not available (MediaRecorder), so say when post-processing is needed. */
    private fun notifyPost(total: Int, locked: Int) {
        val pct = if (total > 0) 100 * locked / total else 0
        val msg = when {
            total == 0 -> getString(R.string.msg_notify_post_no_analysis)
            pct >= 95 -> getString(R.string.msg_notify_post_locked_format, pct)
            else -> getString(R.string.msg_notify_post_unlocked_format, pct)
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    /** The on-screen line: state, flicker frequency, exposure and ISO. */
    private fun shortStatus(cmd: NsCommand): String {
        val locked = cmd.state == NsJni.STATE_LOCKED
        val hz = cmd.flickerHz
        val head = when {
            cmd.mode == NsJni.MODE_AUTO || cmd.mode == NsJni.MODE_UNSUPPORTED -> "AUTO"
            locked && hz > 0 -> "LOCK %.0f Hz".format(hz)
            locked -> "LOCK"
            else -> "SEARCH"
        }
        if (cmd.exposureUs <= 0) return head
        val exp = if (cmd.exposureUs >= 1000) "%.1f ms".format(cmd.exposureUs / 1000) else "%.0f us".format(cmd.exposureUs)
        val hs = if (hsFps > 0) " (HS %d)".format(hsFps) else ""
        return "$head$hs · $exp · ISO %.0f".format(cmd.iso)
    }

    private fun describe(cmd: NsCommand): String {
        val mode = when (cmd.mode) {
            NsJni.MODE_INTEGRATE -> "integrate"
            NsJni.MODE_GAP -> "gap"
            NsJni.MODE_FLASH -> "flash"
            NsJni.MODE_PWM -> "pwm"
            NsJni.MODE_AUTO -> "auto (no flicker found)"
            NsJni.MODE_UNSUPPORTED -> "auto (no manual exposure on this camera: post-processing only)"
            else -> "?"
        }
        val state = if (cmd.state == NsJni.STATE_LOCKED) "locked" else "search"
        val hz = cmd.flickerHz
        return "%s  %s  %.0f us  fps %.0f  iso %.0f  %.1f Hz  conf %.2f".format(
            state, mode, cmd.exposureUs, cmd.fps, cmd.iso, hz, cmd.confidence
        )
    }
}
