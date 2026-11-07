package dev.nostrobe

import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
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

    data class ClipPair(
        val baseName: String,
        val dateDisplay: String,
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
    private lateinit var modeBadge: TextView
    private lateinit var playPauseOverlay: TextView
    private lateinit var postProgressContainer: LinearLayout
    private lateinit var postProgressBar: ProgressBar
    private lateinit var postProgressText: TextView
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

    private val clipPairs = ArrayList<ClipPair>()
    private var selectedIndex = 0
    private var isShowingFixed = false
    private var lineRateHz = 0.0

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
        modeBadge = findViewById(R.id.modeBadge)
        playPauseOverlay = findViewById(R.id.playPauseOverlay)
        postProgressContainer = findViewById(R.id.postProgressContainer)
        postProgressBar = findViewById(R.id.postProgressBar)
        postProgressText = findViewById(R.id.postProgressText)
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
        findViewById<View>(R.id.galleryShare).setOnClickListener { shareCurrentVideo() }
        findViewById<View>(R.id.galleryDelete).setOnClickListener { confirmDeleteCurrentClip() }

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
            true
        }

        findViewById<View>(R.id.videoContainer).setOnClickListener {
            if (videoPlayer.isPlaying) {
                videoPlayer.pause()
                playPauseOverlay.visibility = View.VISIBLE
            } else {
                videoPlayer.start()
                playPauseOverlay.visibility = View.GONE
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
                    val currentPair = clipPairs.getOrNull(selectedIndex)
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

        loadClips(intent.getStringExtra("clip_path"))
    }

    override fun onResume() {
        super.onResume()
        progressHandler.post(updateProgressRunnable)
        if (!videoPlayer.isPlaying && videoPlayer.currentPosition > 0) {
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

    private class ClipDataHolder {
        var origFile: File? = null
        var origUri: Uri? = null
        var fixedFile: File? = null
        var fixedUri: Uri? = null
        var timestamp: Long = 0L
    }

    private fun loadClips(preferredPath: String? = null) {
        clipPairs.clear()
        val dirs = listOfNotNull(
            File(getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir, "clips"),
            externalCacheDir,
            cacheDir
        )

        val grouped = HashMap<String, ClipDataHolder>()

        // 1. Scan app-owned files (instant read access)
        for (d in dirs) {
            if (d.exists() && d.isDirectory) {
                d.listFiles { f -> f.extension.equals("mp4", ignoreCase = true) }?.forEach { f ->
                    if (f.length() > 0L) {
                        val name = f.nameWithoutExtension
                        val isFixed = name.endsWith("-fixed")
                        val base = if (isFixed) name.removeSuffix("-fixed") else name
                        val holder = grouped.getOrPut(base) { ClipDataHolder() }
                        if (isFixed) {
                            holder.fixedFile = f
                        } else {
                            holder.origFile = f
                            holder.timestamp = maxOf(holder.timestamp, f.lastModified())
                        }
                    }
                }
            }
        }

        // 2. Query MediaStore for exported clips in Movies/no-strobe
        try {
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DATE_ADDED
            )
            val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
            } else null
            val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf("%Movies/no-strobe%")
            } else null
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
                    val holder = grouped.getOrPut(base) { ClipDataHolder() }
                    if (isFixed) {
                        if (holder.fixedFile == null) holder.fixedUri = uri
                    } else {
                        if (holder.origFile == null) holder.origUri = uri
                        holder.timestamp = maxOf(holder.timestamp, addedSec * 1000L)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("nostrobe", "MediaStore query in gallery: ${e.message}")
        }

        val sdf = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        val displaySdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

        for ((base, holder) in grouped) {
            if (holder.origFile == null && holder.origUri == null) continue
            val dateStr = if (base.startsWith("nostrobe-")) {
                try {
                    val datePart = base.removePrefix("nostrobe-")
                    val parsed = sdf.parse(datePart)
                    if (parsed != null) displaySdf.format(parsed) else base
                } catch (_: Exception) { base }
            } else {
                displaySdf.format(Date(if (holder.timestamp > 0) holder.timestamp else System.currentTimeMillis()))
            }
            clipPairs.add(
                ClipPair(
                    baseName = base,
                    dateDisplay = dateStr,
                    originalFile = holder.origFile,
                    originalUri = holder.origUri,
                    fixedFile = holder.fixedFile,
                    fixedUri = holder.fixedUri,
                    timestamp = holder.timestamp
                )
            )
        }

        clipPairs.sortByDescending { it.timestamp }

        if (clipPairs.isEmpty()) {
            Toast.makeText(this, "Aucun clip trouvé dans la galerie", Toast.LENGTH_SHORT).show()
            galleryTitle.text = "Aucun clip"
            return
        }

        selectedIndex = 0
        if (!preferredPath.isNullOrBlank()) {
            val prefFile = File(preferredPath)
            val idx = clipPairs.indexOfFirst {
                it.originalFile?.absolutePath == prefFile.absolutePath ||
                it.fixedFile?.absolutePath == prefFile.absolutePath ||
                it.baseName == prefFile.nameWithoutExtension.removeSuffix("-fixed")
            }
            if (idx >= 0) selectedIndex = idx
        }

        val pair = clipPairs[selectedIndex]
        isShowingFixed = pair.isFixed
        refreshStrip()
        displayClip(pair, initial = true)
    }

    private fun refreshStrip() {
        clipsStrip.removeAllViews()
        val dp = resources.displayMetrics.density
        for ((idx, pair) in clipPairs.withIndex()) {
            val isSelected = idx == selectedIndex
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setBackgroundResource(if (isSelected) R.drawable.bg_tool else R.drawable.bg_chip)
                this.isSelected = isSelected
                setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
                setOnClickListener {
                    if (selectedIndex != idx) {
                        selectedIndex = idx
                        isShowingFixed = pair.isFixed
                        refreshStrip()
                        displayClip(pair, initial = false)
                    }
                }
            }

            val title = TextView(this).apply {
                text = pair.dateDisplay
                textSize = 12f
                setTextColor(if (isSelected) 0xFF006A60.toInt() else 0xFF707070.toInt())
                textStyle(isSelected)
            }
            item.addView(title)

            val badge = TextView(this).apply {
                text = if (pair.isFixed) "✓ Corrigé" else "Original"
                textSize = 10f
                setTextColor(if (pair.isFixed) 0xFF059669.toInt() else 0xFF888888.toInt())
            }
            item.addView(badge)

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * dp).toInt() }
            clipsStrip.addView(item, lp)
        }
    }

    private fun TextView.textStyle(bold: Boolean) {
        setTypeface(null, if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    private fun displayClip(pair: ClipPair, initial: Boolean) {
        galleryTitle.text = pair.dateDisplay
        if (pair.isFixed) {
            comparisonToggles.visibility = View.VISIBLE
            notFixedActions.visibility = View.GONE
        } else {
            comparisonToggles.visibility = View.GONE
            notFixedActions.visibility = View.VISIBLE
        }
        updateBadge()
        switchVideoSource(preservePosition = !initial)
    }

    private fun updateBadge() {
        val pair = clipPairs.getOrNull(selectedIndex) ?: return
        if (isShowingFixed && pair.isFixed) {
            modeBadge.text = "APRÈS (CORRIGÉ)"
            modeBadge.setBackgroundResource(R.drawable.bg_strip)
            btnAfter.setBackgroundResource(R.drawable.bg_strip)
            btnAfter.setTextColor(0xFFFFFFFF.toInt())
            btnBefore.setBackgroundResource(R.drawable.bg_tool)
            btnBefore.setTextColor(0xFF000000.toInt())
        } else {
            modeBadge.text = "AVANT (ORIGINAL)"
            modeBadge.setBackgroundResource(R.drawable.bg_lens_chip)
            btnBefore.setBackgroundResource(R.drawable.bg_strip)
            btnBefore.setTextColor(0xFFFFFFFF.toInt())
            btnAfter.setBackgroundResource(R.drawable.bg_tool)
            btnAfter.setTextColor(0xFF000000.toInt())
        }
    }

    private fun switchVideoSource(preservePosition: Boolean) {
        val pair = clipPairs.getOrNull(selectedIndex) ?: return
        val target = if (isShowingFixed && pair.isFixed) pair.getFixedTarget() else pair.getOriginalTarget()

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
        val pair = clipPairs.getOrNull(selectedIndex) ?: return
        val clipsDir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir, "clips").apply { mkdirs() }

        postProgressContainer.visibility = View.VISIBLE
        postProgressBar.progress = 0
        postProgressText.text = "Post-traitement en local: 0 %"
        btnProcessLocal.isEnabled = false

        Thread {
            try {
                // Ensure a local File source exists
                val srcFile: File = if (pair.originalFile?.canRead() == true) {
                    pair.originalFile
                } else if (pair.originalUri != null) {
                    val cacheClip = File(cacheDir, "${pair.baseName}.mp4")
                    contentResolver.openInputStream(pair.originalUri)?.use { input ->
                        cacheClip.outputStream().use { output -> input.copyTo(output) }
                    }
                    cacheClip
                } else {
                    throw Exception("Fichier source introuvable")
                }

                val dst = File(clipsDir, pair.baseName + "-fixed.mp4")
                val t0 = System.currentTimeMillis()
                OnDevicePost.fix(srcFile, dst, lineRateHz) { pct ->
                    runOnUiThread {
                        postProgressBar.progress = pct
                        postProgressText.text = "Post-traitement en local: $pct %"
                    }
                }
                saveToGallery(dst, dst.name)
                runOnUiThread {
                    postProgressContainer.visibility = View.GONE
                    btnProcessLocal.isEnabled = true
                    pair.fixedFile = dst
                    isShowingFixed = true
                    Toast.makeText(this, "Post-traitement terminé en ${(System.currentTimeMillis() - t0) / 1000}s", Toast.LENGTH_SHORT).show()
                    refreshStrip()
                    displayClip(pair, initial = false)
                }
            } catch (e: Exception) {
                android.util.Log.w("nostrobe", "Gallery post fix failed: ${e.message}")
                runOnUiThread {
                    postProgressContainer.visibility = View.GONE
                    btnProcessLocal.isEnabled = true
                    Toast.makeText(this, "Échec: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun startDesktopPostProcessing() {
        val prefs = getSharedPreferences("nostrobe", MODE_PRIVATE)
        val base = prefs.getString("post_url", "") ?: ""
        if (base.isBlank()) {
            Toast.makeText(this, "Configurez l'adresse du serveur desktop dans les options de la caméra", Toast.LENGTH_LONG).show()
            return
        }
        val pair = clipPairs.getOrNull(selectedIndex) ?: return
        val clipsDir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir, "clips").apply { mkdirs() }
        val token = prefs.getString("post_token", "") ?: ""
        val dst = File(clipsDir, pair.baseName + "-fixed.mp4")

        postProgressContainer.visibility = View.VISIBLE
        postProgressBar.progress = 0
        postProgressText.text = "Envoi vers le desktop: 0 %"
        btnProcessDesktop.isEnabled = false

        Thread {
            try {
                val srcFile: File = if (pair.originalFile?.canRead() == true) {
                    pair.originalFile
                } else if (pair.originalUri != null) {
                    val cacheClip = File(cacheDir, "${pair.baseName}.mp4")
                    contentResolver.openInputStream(pair.originalUri)?.use { input ->
                        cacheClip.outputStream().use { output -> input.copyTo(output) }
                    }
                    cacheClip
                } else {
                    throw Exception("Fichier source introuvable")
                }

                DesktopPost.send(srcFile, dst, base, token, lineRateHz) { pct ->
                    runOnUiThread {
                        postProgressBar.progress = pct
                        postProgressText.text = "Desktop: $pct %"
                    }
                }
                saveToGallery(dst, dst.name)
                runOnUiThread {
                    postProgressContainer.visibility = View.GONE
                    btnProcessDesktop.isEnabled = true
                    pair.fixedFile = dst
                    isShowingFixed = true
                    Toast.makeText(this, "Correction desktop terminée !", Toast.LENGTH_SHORT).show()
                    refreshStrip()
                    displayClip(pair, initial = false)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    postProgressContainer.visibility = View.GONE
                    btnProcessDesktop.isEnabled = true
                    Toast.makeText(this, "Erreur desktop: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun shareCurrentVideo() {
        val pair = clipPairs.getOrNull(selectedIndex) ?: return
        val target = if (isShowingFixed && pair.isFixed) pair.getFixedTarget() else pair.getOriginalTarget()

        val uri: Uri = when (target) {
            is File -> {
                try {
                    FileProvider.getUriForFile(this, "$packageName.fileprovider", target)
                } catch (_: Exception) {
                    Uri.fromFile(target)
                }
            }
            is Uri -> target
            else -> return
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Partager la vidéo"))
    }

    private fun confirmDeleteCurrentClip() {
        val pair = clipPairs.getOrNull(selectedIndex) ?: return
        AlertDialog.Builder(this)
            .setTitle("Supprimer ce clip ?")
            .setMessage("Voulez-vous supprimer ce clip vidéo (original et version corrigée) ?")
            .setPositiveButton("Supprimer") { _, _ ->
                try { pair.originalFile?.delete() } catch (_: Exception) {}
                try { pair.fixedFile?.delete() } catch (_: Exception) {}
                pair.originalUri?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) {} }
                pair.fixedUri?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) {} }
                Toast.makeText(this, "Clip supprimé", Toast.LENGTH_SHORT).show()
                loadClips()
            }
            .setNegativeButton("Annuler", null)
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
                val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
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
