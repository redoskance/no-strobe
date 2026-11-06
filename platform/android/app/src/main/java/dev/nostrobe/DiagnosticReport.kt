package dev.nostrobe

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.view.HapticFeedbackConstants
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic report generator and hidden debug menu helper for user issue reporting.
 */
object DiagnosticReport {
    private val recentLogs = ArrayDeque<String>()
    private const val MAX_LOGS = 40

    @Synchronized
    fun log(tag: String, message: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        recentLogs.addLast("[$time] $tag: $message")
        while (recentLogs.size > MAX_LOGS) {
            recentLogs.removeFirst()
        }
    }

    @Synchronized
    fun getLogs(): List<String> = recentLogs.toList()

    fun generateReport(
        context: Context,
        camera: NoStrobeCamera?,
        lastCmd: NsJni.NsCommand?,
        previewSize: android.util.Size?
    ): String {
        val sb = StringBuilder()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

        sb.appendLine("## No-Strobe Diagnostic & Device Report")
        sb.appendLine("*Généré le / Generated at: $time*")
        sb.appendLine()

        sb.appendLine("### Appareil & Système / Device & OS")
        sb.appendLine("- **Fabricant / Manufacturer**: ${Build.MANUFACTURER}")
        sb.appendLine("- **Modèle / Model**: ${Build.MODEL} (${Build.PRODUCT} / ${Build.DEVICE})")
        sb.appendLine("- **Version Android**: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("- **Build ID**: ${Build.DISPLAY}")
        sb.appendLine("- **Fingerprint**: `${Build.FINGERPRINT}`")
        sb.appendLine("- **ABIs supportés**: ${Build.SUPPORTED_ABIS.joinToString(", ")}")

        val config = context.resources.configuration
        val isTablet = config.smallestScreenWidthDp >= 600
        val orientation = if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) "Paysage / Landscape" else "Portrait"
        sb.appendLine("- **Fenêtre / Screen**: ${config.screenWidthDp}x${config.screenHeightDp} dp ($orientation, sw=${config.smallestScreenWidthDp}dp ${if (isTablet) "[Unfolded/Tablet]" else "[Folded/Phone]"})")
        sb.appendLine()

        sb.appendLine("### Caractéristiques Caméra / Camera Characteristics")
        val camId = camera?.currentCameraId ?: "0"
        val physId = camera?.currentPhysicalId
        sb.appendLine("- **Caméra active**: ID $camId ${if (physId != null) "(Objectif physique épinglé: $physId)" else "(Caméra logique)"}")

        val chars = manager?.let { runCatching { it.getCameraCharacteristics(physId ?: camId) }.getOrNull() }
        if (chars != null) {
            val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_FRONT -> "Avant (Front)"
                CameraCharacteristics.LENS_FACING_BACK -> "Arrière (Back)"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "Externe"
                else -> "Inconnu"
            }
            sb.appendLine("- **Orientation capteur / Lens Facing**: $facing")

            val pipeDepth = chars.get(CameraCharacteristics.REQUEST_PIPELINE_MAX_DEPTH) ?: 2
            sb.appendLine("- **Profondeur pipeline matériel / Pipeline Depth**: $pipeDepth frames")

            val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.joinToString(", ") { "[${it.lower}, ${it.upper}]" } ?: "Défaut"
            sb.appendLine("- **Plages de cadence AE disponibles**: $fpsRanges")

            val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            sb.appendLine("- **Limites temps de pose**: ${expRange?.lower?.div(1000.0) ?: "?"} µs .. ${expRange?.upper?.div(1000.0) ?: "?"} µs")

            val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            sb.appendLine("- **Limites sensibilité ISO**: ${isoRange?.lower ?: "?"} .. ${isoRange?.upper ?: "?"}")
        }

        val sustainUs = camera?.currentSustainUs ?: 0.0
        val lineRate = camera?.currentLineRateHz ?: 0.0
        sb.appendLine("- **Rolling Shutter Skew**: ${"%.2f".format(Locale.US, sustainUs / 1000.0)} ms (Cadence lecture lignes: ${"%.0f".format(Locale.US, lineRate)} Hz)")
        sb.appendLine("- **Durée d'image respectée**: ${if (camera?.isTimingIgnored == true) "NON (capteur ignore SENSOR_FRAME_DURATION)" else "OUI"}")
        if (previewSize != null) {
            sb.appendLine("- **Géométrie du viseur**: ${previewSize.width}x${previewSize.height} (16:9)")
        }
        sb.appendLine()

        sb.appendLine("### Moteur Anti-scintillement & Suivi / Flicker Engine")
        if (lastCmd != null) {
            val modeStr = when (lastCmd.mode) {
                NsJni.MODE_PWM -> "PWM (Rolling Shutter De-banding)"
                NsJni.MODE_INTEGRATE -> "Strobe (Integrate)"
                NsJni.MODE_GAP -> "Strobe (Gap Lock)"
                NsJni.MODE_FLASH -> "Strobe (Flash Sync)"
                NsJni.MODE_AUTO -> "Auto / Calibration"
                else -> "Mode ${lastCmd.mode}"
            }
            val stateStr = when (lastCmd.state) {
                NsJni.STATE_LOCKED -> "VERROUILLÉ / LOCKED"
                NsJni.STATE_SEARCH -> "RECHERCHE / SEARCHING"
                else -> "État ${lastCmd.state}"
            }
            sb.appendLine("- **Mode**: $modeStr")
            sb.appendLine("- **État contrôleur**: $stateStr")
            val f = if (lastCmd.flickerHz > 0) lastCmd.flickerHz else (camera?.currentFlickerHz ?: 0.0)
            sb.appendLine("- **Fréquence détectée**: ${if (f > 0) "%.1f Hz".format(Locale.US, f) else "Aucune / Recherche en cours"}")
            sb.appendLine("- **Pose appliquée**: ${"%.0f".format(Locale.US, lastCmd.exposureUs)} µs (${"%.2f".format(Locale.US, lastCmd.exposureUs / 1000.0)} ms)")
            sb.appendLine("- **Cadence cible**: ${"%.0f".format(Locale.US, lastCmd.fps)} fps")
            sb.appendLine("- **Sensibilité ISO**: ${lastCmd.iso.toInt()}")
            sb.appendLine("- **Indice de confiance**: ${"%.2f".format(Locale.US, lastCmd.confidence)}")
        } else {
            sb.appendLine("- *Moteur en attente d'initialisation*")
        }
        sb.appendLine()

        sb.appendLine("### Performances & Runtime")
        val runtime = Runtime.getRuntime()
        val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMem = runtime.maxMemory() / (1024 * 1024)
        sb.appendLine("- **Mémoire Java Heap**: $usedMem Mo / $maxMem Mo")
        val procUs = camera?.processingTimeUs ?: 0L
        if (procUs > 0) {
            sb.appendLine("- **Latence traitement natif par trame**: $procUs µs (${"%.2f".format(Locale.US, procUs / 1000.0)} ms)")
        }
        sb.appendLine("- **SIMD & Accélération**: ARMv8/ARMv9 NEON actif (`libns_ffi.so` chargé)")
        sb.appendLine("- **Pipeline graphique**: Vulkan Skia")
        sb.appendLine()

        sb.appendLine("### Événements récents / Event Log")
        val logs = getLogs()
        if (logs.isNotEmpty()) {
            sb.appendLine("```")
            for (line in logs) {
                sb.appendLine(line)
            }
            sb.appendLine("```")
        } else {
            sb.appendLine("*Aucun événement consigné pour le moment*")
        }

        return sb.toString()
    }

    fun showDialog(activity: Activity, camera: NoStrobeCamera?, lastCmd: NsJni.NsCommand?, previewSize: android.util.Size?) {
        activity.window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val reportMarkdown = generateReport(activity, camera, lastCmd, previewSize)

        val scrollView = ScrollView(activity).apply {
            setPadding(42, 28, 42, 14)
        }
        val textView = TextView(activity).apply {
            text = reportMarkdown
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextColor(activity.getColor(R.color.m3_on_surface))
        }
        scrollView.addView(textView)

        MaterialAlertDialogBuilder(activity)
            .setTitle("Diagnostic & Rapport GitHub")
            .setView(scrollView)
            .setPositiveButton("Issue GitHub") { _, _ ->
                val title = URLEncoder.encode("[Rapport] ${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}", "UTF-8")
                val body = URLEncoder.encode(
                    "## Description du problème / Problem Description\n<!-- Décrivez ce que vous observez / Describe what happened -->\n\n" +
                    "## Diagnostic Report\n<details><summary>Détails système / System Details</summary>\n\n$reportMarkdown\n\n</details>",
                    "UTF-8"
                )
                val url = "https://github.com/redoskance/no-strobe/issues/new?title=$title&body=$body"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                activity.startActivity(intent)
            }
            .setNeutralButton("Copier") { _, _ ->
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("No-Strobe Diagnostic", reportMarkdown))
                Toast.makeText(activity, "Rapport copié dans le presse-papier !", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Partager") { _, _ ->
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Rapport No-Strobe - ${Build.MODEL}")
                    putExtra(Intent.EXTRA_TEXT, reportMarkdown)
                }
                activity.startActivity(Intent.createChooser(shareIntent, "Partager le rapport de diagnostic"))
            }
            .show()
    }
}
