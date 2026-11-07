package dev.nostrobe

import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GalleryActivity : AppCompatActivity() {

    enum class MediaType { VIDEO, PHOTO }

    data class MediaItem(
        val baseName: String,
        val dateDisplay: String,
        val type: MediaType,
        val originalFile: File?,
        val originalUri: Uri?,
        var fixedFile: File?,
        var fixedUri: Uri?,
        val timestamp: Long,
    ) {
        val isFixed: Boolean get() = (fixedFile != null && fixedFile!!.exists() && fixedFile!!.length() > 0L) || fixedUri != null
        fun getOriginalTarget(): Any = (if (originalFile?.canRead() == true) originalFile else originalUri) ?: originalFile ?: Uri.EMPTY
        fun getFixedTarget(): Any? = (if (fixedFile?.canRead() == true) fixedFile else fixedUri) ?: fixedFile
    }

    private lateinit var videoPlayer: VideoView
    private lateinit var photoViewer: ImageView
    private lateinit var modeBadge: TextView
    private lateinit var playPauseOverlay: TextView
    private lateinit var postProgressContainer: LinearLayout
    private lateinit var postProgressBar: ProgressBar
    private lateinit var postProgressText: TextView
    private lateinit var playbackControls: LinearLayout
    private lateinit var videoSeekBar: SeekBar
    private lateinit var timeCurrent: TextView
    private lateinit var timeTotal: TextView

    private lateinit var comparisonToggles: LinearLayout
    private lateinit var notFixedActions: LinearLayout
    private lateinit var btnBefore: TextView
    private lateinit var btnAfter: TextView
    private lateinit var btnHoldCompare: TextView
    private lateinit var btnProcessLocal: TextView
    private lateinit var btnProcessDesktop: TextView
    private lateinit var clipsStrip: LinearLayout
    private lateinit var galleryTitle: TextView

    private val mediaItems = ArrayList<MediaItem>()
    private var selectedIndex = 0
    private var isShowingFixed = false
    private var lineRateHz = 0.0
    private var currentPhotoBitmap: Bitmap? = null

    private val progressHandler = Handler(Looper.getMainLooper())
    private var isTrackingSeekBar = false

    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            if (!isTrackingSeekBar && videoPlayer.isPlaying) {
                val current = videoPlayer.currentPosition
                val duration = videoPlayer.duration
                if (duration > 0) {
                    videoSeekBar.max = duration
                    videoSeekBar.progress = current
                    timeCurrent.text = formatTime(current)
                    timeTotal.text = formatTime(duration)
                }
            }
            progressHandler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        lineRateHz = intent.getDoubleExtra("line_rate", 0.0)

        videoPlayer = findViewById(R.id.videoPlayer)
        photoViewer = findViewById(R.id.photoViewer)
        modeBadge = findViewById(R.id.modeBadge)
        playPauseOverlay = findViewById(R.id.playPauseOverlay)
        postProgressContainer = findViewById(R.id.postProgressContainer)
        postProgressBar = findViewById(R.id.postProgressBar)
        postProgressText = findViewById(R.id.postProgressText)
        playbackControls = findViewById(R.id.playbackControls)
        videoSeekBar = findViewById(R.id.videoSeekBar)
        timeCurrent = findViewById(R.id.timeCurrent)
        timeTotal = findViewById(R.id.timeTotal)

        comparisonToggles = findViewById(R.id.comparisonToggles)
        notFixedActions = findViewById(R.id.notFixedActions)
        btnBefore = findViewById(R.id.btnBefore)
        btnAfter = findViewById(R.id.btnAfter)
        btnHoldCompare = findViewById(R.id.btnHoldCompare)
        btnProcessLocal = findViewById(R.id.btnProcessLocal)
        btnProcessDesktop = findViewById(R.id.btnProcessDesktop)
        clipsStrip = findViewById(R.id.clipsStrip)
        galleryTitle = findViewById(R.id.galleryTitle)

        findViewById<View>(R.id.galleryBack).setOnClickListener { finish() }
        findViewById<View>(R.id.galleryServer).setOnClickListener { LanServerConfig.showDialog(this) }
        findViewById<View>(R.id.galleryShare).setOnClickListener { shareCurrentMedia() }
        findViewById<View>(R.id.galleryDelete).setOnClickListener { confirmDeleteCurrentMedia() }

        videoPlayer.setOnPreparedListener { mp ->
            mp.isLooping = true
            val duration = mp.duration
            if (duration > 0) {
                videoSeekBar.max = duration
                timeTotal.text = formatTime(duration)
            }
            mp.start()
            playPauseOverlay.visibility = View.GONE
        }

        videoPlayer.setOnErrorListener { _, what, extra ->
            android.util.Log.w("nostrobe", "VideoView error: what=$what extra=$extra")
            Toast.makeText(this, getString(R.string.gallery_err_playback), Toast.LENGTH_SHORT).show()
            true
        }

        findViewById<View>(R.id.videoContainer).setOnClickListener {
            val item = mediaItems.getOrNull(selectedIndex) ?: return@setOnClickListener
            if (item.type == MediaType.VIDEO) {
                if (videoPlayer.isPlaying) {
                    videoPlayer.pause()
                    playPauseOverlay.visibility = View.VISIBLE
                } else {
                    videoPlayer.start()
                    playPauseOverlay.visibility = View.GONE
                }
            }
        }

        videoSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    timeCurrent.text = formatTime(progress)
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar) {
                isTrackingSeekBar = true
            }
            override fun onStopTrackingTouch(bar: SeekBar) {
                videoPlayer.seekTo(bar.progress)
                isTrackingSeekBar = false
            }
        })

        btnBefore.setOnClickListener {
            if (isShowingFixed) {
                isShowingFixed = false
                switchVideoSource(preservePosition = true)
            }
        }

        btnAfter.setOnClickListener {
            if (!isShowingFixed) {
                isShowingFixed = true
                switchVideoSource(preservePosition = true)
            }
        }

        btnHoldCompare.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (isShowingFixed) {
                        isShowingFixed = false
                        switchVideoSource(preservePosition = true)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val currentPair = mediaItems.getOrNull(selectedIndex)
                    if (currentPair?.isFixed == true && !isShowingFixed) {
                        isShowingFixed = true
                        switchVideoSource(preservePosition = true)
                    }
                    true
                }
                else -> false
            }
        }

        btnProcessLocal.setOnClickListener { startLocalPostProcessing() }
        btnProcessDesktop.setOnClickListener { startDesktopPostProcessing() }
        btnProcessDesktop.setOnLongClickListener {
            LanServerConfig.showDialog(this)
            true
        }

        loadMedia(intent.getStringExtra("clip_path"))
    }

    override fun onResume() {
        super.onResume()
        progressHandler.post(updateProgressRunnable)
        val item = mediaItems.getOrNull(selectedIndex)
        if (item?.type == MediaType.VIDEO && !videoPlayer.isPlaying && videoPlayer.currentPosition > 0) {
            videoPlayer.start()
        }
    }

    override fun onPause() {
        progressHandler.removeCallbacks(updateProgressRunnable)
        if (videoPlayer.isPlaying) {
            videoPlayer.pause()
        }
        super.onPause()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadMedia(intent?.getStringExtra("clip_path"))
    }

    override fun onDestroy() {
        currentPhotoBitmap?.recycle()
        currentPhotoBitmap = null
        super.onDestroy()
    }

    private class MediaDataHolder(val type: MediaType) {
        var origFile: File? = null
        var origUri: Uri? = null
        var fixedFile: File? = null
        var fixedUri: Uri? = null
        var timestamp: Long = 0L
    }

    private fun loadMedia(preferredPath: String? = null) {
        mediaItems.clear()
        val dirs = listOfNotNull(
            File(getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir, "photos"),
            File(getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir, "clips"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "no-strobe"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "no-strobe"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "no-strobe"),
            externalCacheDir,
            cacheDir
        )

        val grouped = HashMap<String, MediaDataHolder>()

        // 1. Scan filesystem directories for both videos and photos
        for (d in dirs) {
            if (d.exists() && d.isDirectory) {
                d.listFiles()?.forEach { f ->
                    if (f.isFile && f.length() > 0L) {
                        try { f.setReadable(true, false) } catch (_: Exception) {}
                        val ext = f.extension.lowercase(Locale.US)
                        val isVideo = ext == "mp4"
                        val isPhoto = ext == "jpg" || ext == "jpeg" || ext == "png"
                        if (isVideo || isPhoto) {
                            val type = if (isPhoto) MediaType.PHOTO else MediaType.VIDEO
                            val name = f.nameWithoutExtension
                            val isFixed = isVideo && name.endsWith("-fixed")
                            val base = if (isFixed) name.removeSuffix("-fixed") else name
                            val holder = grouped.getOrPut(base) { MediaDataHolder(type) }
                            if (isFixed) {
                                holder.fixedFile = f
                            } else {
                                if (holder.origFile == null) {
                                    holder.origFile = f
                                }
                                holder.timestamp = maxOf(holder.timestamp, f.lastModified())
                            }
                        }
                    }
                }
            }
        }

        // 2. Query MediaStore for exported videos in Movies/no-strobe
        try {
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DATE_ADDED
            )
            // Only this app's folders: before Android 10 the query sees every video on the device,
            // and the delete below removes whatever rows it returned.
            val appMovies = getExternalFilesDir(Environment.DIRECTORY_MOVIES)?.absolutePath
            val folderPatterns = listOfNotNull("%/Movies/no-strobe/%", appMovies?.let { "$it/%" })
            val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
            } else {
                folderPatterns.joinToString(" OR ") { "${MediaStore.MediaColumns.DATA} LIKE ?" }
            }
            val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf("%Movies/no-strobe%")
            } else {
                folderPatterns.toTypedArray()
            }
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: continue
                    val addedSec = cursor.getLong(dateCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    val nameNoExt = name.removeSuffix(".mp4").removeSuffix(".MP4")
                    val isFixed = nameNoExt.endsWith("-fixed")
                    val base = if (isFixed) nameNoExt.removeSuffix("-fixed") else nameNoExt
                    val holder = grouped.getOrPut(base) { MediaDataHolder(MediaType.VIDEO) }
                    if (isFixed) {
                        if (holder.fixedFile == null) holder.fixedUri = uri
                    } else {
                        if (holder.origFile == null) holder.origUri = uri
                        holder.timestamp = maxOf(holder.timestamp, addedSec * 1000L)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("nostrobe", "MediaStore video query: ${e.message}")
        }

        // 3. Query MediaStore for exported photos in DCIM/no-strobe and Pictures/no-strobe
        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED
            )
            val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            } else null
            val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf("%DCIM/no-strobe%", "%Pictures/no-strobe%")
            } else null
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: continue
                    val addedSec = cursor.getLong(dateCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    val nameNoExt = name.substringBeforeLast('.')
                    val holder = grouped.getOrPut(nameNoExt) { MediaDataHolder(MediaType.PHOTO) }
                    if (holder.origFile == null && holder.origUri == null) {
                        holder.origUri = uri
                        holder.timestamp = maxOf(holder.timestamp, addedSec * 1000L)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("nostrobe", "MediaStore image query: ${e.message}")
        }

        val sdf = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        val displaySdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

        for ((base, holder) in grouped) {
            if (holder.origFile == null && holder.origUri == null) continue
            val dateStr = if (base.startsWith("nostrobe-shot-")) {
                try {
                    val datePart = base.removePrefix("nostrobe-shot-")
                    val parsed = sdf.parse(datePart)
                    if (parsed != null) displaySdf.format(parsed) else base
                } catch (_: Exception) { base }
            } else if (base.startsWith("nostrobe-")) {
                try {
                    val datePart = base.removePrefix("nostrobe-")
                    val parsed = sdf.parse(datePart)
                    if (parsed != null) displaySdf.format(parsed) else base
                } catch (_: Exception) { base }
            } else {
                displaySdf.format(Date(if (holder.timestamp > 0) holder.timestamp else System.currentTimeMillis()))
            }
            mediaItems.add(
                MediaItem(
                    baseName = base,
                    dateDisplay = dateStr,
                    type = holder.type,
                    originalFile = holder.origFile,
                    originalUri = holder.origUri,
                    fixedFile = holder.fixedFile,
                    fixedUri = holder.fixedUri,
                    timestamp = holder.timestamp
                )
            )
        }

        mediaItems.sortByDescending { it.timestamp }

        if (mediaItems.isEmpty()) {
            Toast.makeText(this, getString(R.string.gallery_err_empty), Toast.LENGTH_SHORT).show()
            galleryTitle.text = getString(R.string.gallery_no_media)
            return
        }

        selectedIndex = 0
        if (!preferredPath.isNullOrBlank()) {
            val prefFile = File(preferredPath)
            val idx = mediaItems.indexOfFirst {
                it.originalFile?.absolutePath == prefFile.absolutePath ||
                it.fixedFile?.absolutePath == prefFile.absolutePath ||
                it.baseName == prefFile.nameWithoutExtension.removeSuffix("-fixed")
            }
            if (idx >= 0) selectedIndex = idx
        }

        val item = mediaItems[selectedIndex]
        isShowingFixed = item.isFixed
        refreshStrip()
        displayMedia(item, initial = true)
    }

    private fun refreshStrip() {
        clipsStrip.removeAllViews()
        val dp = resources.displayMetrics.density
        for ((idx, item) in mediaItems.withIndex()) {
            val isSelected = idx == selectedIndex
            val itemView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setBackgroundResource(if (isSelected) R.drawable.bg_tool else R.drawable.bg_chip)
                this.isSelected = isSelected
                setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
                setOnClickListener {
                    if (selectedIndex != idx) {
                        selectedIndex = idx
                        isShowingFixed = item.isFixed
                        refreshStrip()
                        displayMedia(item, initial = false)
                    }
                }
            }

            val title = TextView(this).apply {
                text = item.dateDisplay
                textSize = 12f
                // Theme colours: a fixed dark teal vanished on the dark theme's teal chip.
                setTextColor(androidx.core.content.ContextCompat.getColor(
                    context, if (isSelected) R.color.m3_on_primary_container else R.color.m3_on_surface_variant))
                textStyle(isSelected)
            }
            itemView.addView(title)

            val badge = TextView(this).apply {
                text = when {
                    item.type == MediaType.PHOTO -> getString(R.string.badge_strip_photo)
                    item.isFixed -> getString(R.string.badge_strip_fixed)
                    else -> "Original"
                }
                textSize = 10f
                setTextColor(when {
                    item.type == MediaType.PHOTO -> 0xFF0284C7.toInt()
                    item.isFixed -> 0xFF059669.toInt()
                    else -> 0xFF888888.toInt()
                })
            }
            itemView.addView(badge)

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * dp).toInt() }
            clipsStrip.addView(itemView, lp)
        }
    }

    private fun TextView.textStyle(bold: Boolean) {
        setTypeface(null, if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    private fun displayMedia(item: MediaItem, initial: Boolean) {
        galleryTitle.text = item.dateDisplay

        if (item.type == MediaType.PHOTO) {
            // Stop and hide video playback views
            if (videoPlayer.isPlaying) videoPlayer.pause()
            videoPlayer.visibility = View.GONE
            playPauseOverlay.visibility = View.GONE
            playbackControls.visibility = View.GONE
            comparisonToggles.visibility = View.GONE
            notFixedActions.visibility = View.GONE

            photoViewer.visibility = View.VISIBLE
            modeBadge.visibility = View.VISIBLE
            modeBadge.text = getString(R.string.badge_photo_synchro)
            modeBadge.setBackgroundResource(R.drawable.bg_lens_chip)

            displayPhoto(item)
        } else {
            // Video mode
            photoViewer.visibility = View.GONE
            currentPhotoBitmap?.recycle()
            currentPhotoBitmap = null
            photoViewer.setImageDrawable(null)

            videoPlayer.visibility = View.VISIBLE
            playbackControls.visibility = View.VISIBLE

            if (item.isFixed) {
                comparisonToggles.visibility = View.VISIBLE
                notFixedActions.visibility = View.GONE
            } else {
                comparisonToggles.visibility = View.GONE
                notFixedActions.visibility = View.VISIBLE
            }
            updateBadge()
            switchVideoSource(preservePosition = !initial)
        }
    }

    private fun displayPhoto(item: MediaItem) {
        val target = item.getOriginalTarget()
        Thread {
            val bitmap = loadOrientedBitmap(target)
            runOnUiThread {
                if (mediaItems.getOrNull(selectedIndex)?.baseName == item.baseName) {
                    currentPhotoBitmap?.recycle()
                    currentPhotoBitmap = bitmap
                    photoViewer.setImageBitmap(bitmap)
                } else {
                    bitmap?.recycle()
                }
            }
        }.start()
    }

    /**
     * Reads the EXIF orientation metadata from [target] and decodes a properly rotated [Bitmap].
     * Downsamples safely to prevent OutOfMemory errors on high-resolution camera frames.
     */
    private fun loadOrientedBitmap(target: Any): Bitmap? {
        return try {
            val exifOrientation = when (target) {
                is File -> {
                    if (!target.canRead()) return null
                    val exif = ExifInterface(target.absolutePath)
                    exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }
                is Uri -> {
                    contentResolver.openInputStream(target)?.use { stream ->
                        val exif = ExifInterface(stream)
                        exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                    } ?: ExifInterface.ORIENTATION_NORMAL
                }
                else -> ExifInterface.ORIENTATION_NORMAL
            }

            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            when (target) {
                is File -> BitmapFactory.decodeFile(target.absolutePath, boundsOpts)
                is Uri -> contentResolver.openInputStream(target)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }
            }

            val maxDimension = 2560
            var sampleSize = 1
            while (boundsOpts.outWidth / sampleSize > maxDimension || boundsOpts.outHeight / sampleSize > maxDimension) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val raw = when (target) {
                is File -> BitmapFactory.decodeFile(target.absolutePath, decodeOpts)
                is Uri -> contentResolver.openInputStream(target)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
                else -> null
            } ?: return null

            val matrix = Matrix()
            when (exifOrientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postRotate(90f)
                    matrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postRotate(270f)
                    matrix.postScale(-1f, 1f)
                }
                else -> {}
            }

            if (!matrix.isIdentity) {
                val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                if (rotated != raw) raw.recycle()
                rotated
            } else {
                raw
            }
        } catch (e: Exception) {
            android.util.Log.w("nostrobe", "loadOrientedBitmap failed: ${e.message}")
            null
        }
    }

    /** Text colour of the glass buttons: dark on the light theme, light on the dark one. */
    private fun onSurface(): Int = androidx.core.content.ContextCompat.getColor(this, R.color.m3_on_surface)

    private fun updateBadge() {
        val item = mediaItems.getOrNull(selectedIndex) ?: return
        if (item.type == MediaType.PHOTO) return
        if (isShowingFixed && item.isFixed) {
            modeBadge.text = getString(R.string.badge_after_fixed)
            modeBadge.setBackgroundResource(R.drawable.bg_strip)
            btnAfter.setBackgroundResource(R.drawable.bg_strip)
            btnAfter.setTextColor(onSurface())
            btnBefore.setBackgroundResource(R.drawable.bg_tool)
            btnBefore.setTextColor(onSurface())
        } else {
            modeBadge.text = getString(R.string.badge_before_orig)
            modeBadge.setBackgroundResource(R.drawable.bg_lens_chip)
            btnBefore.setBackgroundResource(R.drawable.bg_strip)
            btnBefore.setTextColor(onSurface())
            btnAfter.setBackgroundResource(R.drawable.bg_tool)
            btnAfter.setTextColor(onSurface())
        }
    }

    private fun switchVideoSource(preservePosition: Boolean) {
        val item = mediaItems.getOrNull(selectedIndex) ?: return
        if (item.type != MediaType.VIDEO) return
        val target = if (isShowingFixed && item.isFixed) item.getFixedTarget() else item.getOriginalTarget()

        val currentPos = if (preservePosition) videoPlayer.currentPosition else 0
        updateBadge()

        if (target is File) {
            videoPlayer.setVideoPath(target.absolutePath)
        } else if (target is Uri) {
            videoPlayer.setVideoURI(target)
        } else return

        videoPlayer.setOnPreparedListener { mp ->
            mp.isLooping = true
            val dur = mp.duration
            if (dur > 0) {
                videoSeekBar.max = dur
                timeTotal.text = formatTime(dur)
            }
            if (currentPos in 1 until dur) {
                mp.seekTo(currentPos)
            }
            mp.start()
            playPauseOverlay.visibility = View.GONE
        }
    }

    private fun startLocalPostProcessing() {
        if (OnDevicePost.isProcessing) {
            Toast.makeText(this, "Un post-traitement est déjà en cours d'exécution.", Toast.LENGTH_SHORT).show()
            return
        }
        val item = mediaItems.getOrNull(selectedIndex) ?: return
        if (item.type != MediaType.VIDEO) return
        val freeBytes = (getExternalFilesDir(null) ?: filesDir).usableSpace
        val srcLength = item.originalFile?.length() ?: 0L
        if (freeBytes < 500 * 1024 * 1024L || (srcLength > 0 && freeBytes < srcLength * 3)) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_low_storage_title))
                .setMessage(getString(R.string.dialog_low_storage_msg, freeBytes / (1024 * 1024)))
                .setPositiveButton(getString(R.string.btn_use_lan_server)) { _, _ ->
                    startDesktopPostProcessing()
                }
                .setNegativeButton(getString(R.string.btn_continue_local)) { _, _ ->
                    executeLocalPost(item)
                }
                .show()
            return
        }
        executeLocalPost(item)
    }

    private fun getClipsDir(): File {
        val ext = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        if (ext != null) {
            val d = File(ext, "clips")
            if ((d.exists() && d.canWrite()) || d.mkdirs()) {
                if (d.canWrite()) return d
            }
        }
        return File(filesDir, "clips").apply { mkdirs() }
    }

    private fun executeLocalPost(item: MediaItem) {
        val clipsDir = getClipsDir()

        postProgressContainer.visibility = View.VISIBLE
        postProgressBar.progress = 0
        postProgressText.text = getString(R.string.post_progress_local)
        btnProcessLocal.isEnabled = false

        Thread {
            try {
                val srcFile: File = if (item.originalFile?.canRead() == true) {
                    item.originalFile
                } else if (item.originalUri != null) {
                    val cacheClip = File(cacheDir, "${item.baseName}.mp4")
                    contentResolver.openInputStream(item.originalUri)?.use { input ->
                        cacheClip.outputStream().use { output -> input.copyTo(output) }
                    }
                    cacheClip
                } else {
                    throw Exception("Fichier source introuvable")
                }

                val dst = File(clipsDir, item.baseName + "-fixed.mp4")
                val t0 = System.currentTimeMillis()
                OnDevicePost.fix(srcFile, dst, lineRateHz) { pct ->
                    runOnUiThread {
                        postProgressBar.progress = pct
                        postProgressText.text = getString(R.string.post_progress_local_format, pct)
                    }
                }
                try { dst.setReadable(true, false) } catch (_: Exception) {}
                saveToGallery(dst, dst.name)
                runOnUiThread {
                    postProgressContainer.visibility = View.GONE
                    btnProcessLocal.isEnabled = true
                    item.fixedFile = dst
                    isShowingFixed = true
                    Toast.makeText(this, getString(R.string.post_complete_seconds_format, (System.currentTimeMillis() - t0) / 1000), Toast.LENGTH_SHORT).show()
                    refreshStrip()
                    displayMedia(item, initial = false)
                }
            } catch (e: Exception) {
                android.util.Log.w("nostrobe", "Gallery post fix failed", e)
                runOnUiThread {
                    postProgressContainer.visibility = View.GONE
                    btnProcessLocal.isEnabled = true
                    Toast.makeText(this, getString(R.string.post_error_format, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun startDesktopPostProcessing() {
        val base = LanServerConfig.getServerUrl(this)
        if (base.isBlank()) {
            LanServerConfig.showDialog(this) { _, _ ->
                startDesktopPostProcessing()
            }
            return
        }
        val item = mediaItems.getOrNull(selectedIndex) ?: return
        if (item.type != MediaType.VIDEO) return
        val clipsDir = getClipsDir()
        val token = LanServerConfig.getServerToken(this)
        val dst = File(clipsDir, item.baseName + "-fixed.mp4")

        postProgressContainer.visibility = View.VISIBLE
        postProgressBar.progress = 0
        postProgressText.text = getString(R.string.post_progress_lan)
        btnProcessDesktop.isEnabled = false

        Thread {
            try {
                val srcFile: File = if (item.originalFile?.canRead() == true) {
                    item.originalFile
                } else if (item.originalUri != null) {
                    val cacheClip = File(cacheDir, "${item.baseName}.mp4")
                    contentResolver.openInputStream(item.originalUri)?.use { input ->
                        cacheClip.outputStream().use { output -> input.copyTo(output) }
                    }
                    cacheClip
                } else {
                    throw Exception("Fichier source introuvable")
                }

                DesktopPost.send(srcFile, dst, base, token, lineRateHz) { pct ->
                    runOnUiThread {
                        postProgressBar.progress = pct
                        postProgressText.text = getString(R.string.post_progress_lan_format, pct)
                    }
                }
                saveToGallery(dst, dst.name)
                runOnUiThread {
                    postProgressContainer.visibility = View.GONE
                    btnProcessDesktop.isEnabled = true
                    item.fixedFile = dst
                    isShowingFixed = true
                    Toast.makeText(this, getString(R.string.lan_complete), Toast.LENGTH_SHORT).show()
                    refreshStrip()
                    displayMedia(item, initial = false)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    postProgressContainer.visibility = View.GONE
                    btnProcessDesktop.isEnabled = true
                    Toast.makeText(this, getString(R.string.lan_error_format, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun shareCurrentMedia() {
        val item = mediaItems.getOrNull(selectedIndex) ?: return
        val target = if (isShowingFixed && item.isFixed) item.getFixedTarget() else item.getOriginalTarget()

        val uri: Uri = when (target) {
            is File -> {
                try {
                    FileProvider.getUriForFile(this, "$packageName.fileprovider", target)
                } catch (_: Exception) {
                    // Outside the FileProvider roots (the internal cache): share a copy from the external cache.
                    val dir = externalCacheDir ?: run {
                        Toast.makeText(this, getString(R.string.share_error), Toast.LENGTH_SHORT).show()
                        return
                    }
                    val copy = File(dir, target.name)
                    target.copyTo(copy, overwrite = true)
                    FileProvider.getUriForFile(this, "$packageName.fileprovider", copy)
                }
            }
            is Uri -> target
            else -> return
        }

        val isPhoto = item.type == MediaType.PHOTO
        val mime = if (isPhoto) "image/jpeg" else "video/mp4"
        val chooserTitle = if (isPhoto) getString(R.string.share_photo_title) else getString(R.string.share_video_title)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, chooserTitle))
    }

    private fun confirmDeleteCurrentMedia() {
        val item = mediaItems.getOrNull(selectedIndex) ?: return
        val isPhoto = item.type == MediaType.PHOTO
        val dialogTitle = if (isPhoto) getString(R.string.dialog_delete_photo_title) else getString(R.string.dialog_delete_clip_title)
        val dialogMsg = if (isPhoto) {
            getString(R.string.dialog_delete_photo_msg)
        } else {
            getString(R.string.dialog_delete_clip_msg)
        }

        AlertDialog.Builder(this)
            .setTitle(dialogTitle)
            .setMessage(dialogMsg)
            .setPositiveButton(getString(R.string.btn_delete)) { _, _ ->
                try { item.originalFile?.delete() } catch (_: Exception) {}
                try { item.fixedFile?.delete() } catch (_: Exception) {}
                item.originalUri?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) {} }
                item.fixedUri?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) {} }
                Toast.makeText(this, if (isPhoto) getString(R.string.toast_photo_deleted) else getString(R.string.toast_clip_deleted), Toast.LENGTH_SHORT).show()
                loadMedia()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

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
                val dir = getClipsDir()
                src.copyTo(File(dir, displayName), overwrite = true).path
            }
        } catch (e: Exception) {
            android.util.Log.w("nostrobe", "gallery save failed: ${e.message}")
            null
        }
    }

    private fun formatTime(millis: Int): String {
        val totalSec = millis / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return "%02d:%02d".format(m, s)
    }
}
