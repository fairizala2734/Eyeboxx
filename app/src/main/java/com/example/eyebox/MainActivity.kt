package com.example.eyebox

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    companion object {
        private const val PREFS_NAME = "eyebox_prefs"
        private const val KEY_INTRO_OK = "intro_ok"
        private const val KEY_LAST_SESSION_MICROSLEEP = "last_session_microsleep"

        private const val THRESH_MICROSLEEP_MS = 2000L
        private const val THRESH_CLOSE = 0.95f
        private const val THRESH_OPEN  = 0.05f
    }

    private lateinit var previewView: PreviewView
    private lateinit var overlay: EyeOverlay
    private lateinit var tvPerf: TextView

    private var faceHelper: FaceLandmarkerHelper? = null
    private var eyeClassifier: EyeClassifier? = null

    private var preview: Preview? = null
    private var analyzer: ImageAnalysis? = null

    private var cachedBitmap: Bitmap? = null
    private var cachedArgb: IntArray? = null
    private var cachedRowBuffer: ByteArray? = null
    private var cachedW = -1
    private var cachedH = -1

    private var lastClosedLeft: Boolean? = null
    private var lastClosedRight: Boolean? = null

    private var orientationListener: OrientationEventListener? = null
    private var lastSurfaceRotation: Int = Surface.ROTATION_0

    private lateinit var prefs: SharedPreferences

    private var closedStartMs: Long = 0L
    private var warningShown = false

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var analyzingEnabled = false

    private var fpsFrames = 0
    private var fpsWindowStartMs = 0L
    private var lastUiPerfUpdateMs = 0L
    private var lastFps = 0.0
    private var emaLatencyMs = -1.0

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, "Izin kamera ditolak", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        setContentView(R.layout.activity_main)
        enterImmersiveMode()

        val root = findViewById<View>(R.id.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, _ -> WindowInsetsCompat.CONSUMED }

        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.eyeOverlay)
        tvPerf = findViewById(R.id.tvPerf)
        previewView.keepScreenOn = true

        val introAccepted = prefs.getBoolean(KEY_INTRO_OK, false)
        if (!introAccepted) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Microsleep Detector")
                .setMessage("Aplikasi akan menggunakan kamera untuk mendeteksi posisi mata.")
                .setPositiveButton("OK") { _, _ ->
                    prefs.edit().putBoolean(KEY_INTRO_OK, true).apply()
                    ensureCameraPermission()
                }
                .setCancelable(false)
                .show()
        } else {
            ensureCameraPermission()
        }

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                val newRotation = when (orientation) {
                    in 45..134  -> Surface.ROTATION_270
                    in 225..314 -> Surface.ROTATION_90
                    in 0..44, in 315..359 -> Surface.ROTATION_0
                    in 135..224 -> Surface.ROTATION_180
                    else -> return
                }
                if (newRotation != lastSurfaceRotation) {
                    lastSurfaceRotation = newRotation
                    preview?.targetRotation = newRotation
                    analyzer?.targetRotation = newRotation
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val rot = previewView.display?.rotation ?: Surface.ROTATION_0
        if (rot != lastSurfaceRotation) {
            lastSurfaceRotation = rot
            preview?.targetRotation = rot
            analyzer?.targetRotation = rot
        }
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        orientationListener?.enable()
        startCamera()
        analyzingEnabled = true
    }

    override fun onPause() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        orientationListener?.disable()
        super.onPause()
        analyzingEnabled = false
        analyzer?.clearAnalyzer()
        ProcessCameraProvider.getInstance(this).get().unbindAll()
    }

    private fun ensureCameraPermission() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) startCamera() else requestPermission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val initialRotation = previewView.display?.rotation ?: Surface.ROTATION_0
            lastSurfaceRotation = initialRotation

            val resSelector4x3 = ResolutionSelector.Builder()
                .setAspectRatioStrategy(
                    AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO)
                )
                .build()

            preview = Preview.Builder()
                .setResolutionSelector(resSelector4x3)
                .setTargetRotation(initialRotation)
                .build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            analyzer = ImageAnalysis.Builder()
                .setResolutionSelector(resSelector4x3)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(initialRotation)
                .build().also { ia ->
                    ia.setAnalyzer(analysisExecutor) { image ->
                        if (analyzingEnabled) analyze(image) else image.close()
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, analyzer)

                if (faceHelper == null) {
                    faceHelper = FaceLandmarkerHelper(
                        applicationContext,
                        preferGpu = false
                    ).apply { setup("face_landmarker.task") }
                }
                if (eyeClassifier == null) {
                    eyeClassifier = EyeClassifier(
                        applicationContext,
                        modelAssetName = "mnv3s_eyes_infer_fp16.tflite"
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Camera init error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun ensureCaches(w: Int, h: Int) {
        if (w != cachedW || h != cachedH || cachedBitmap == null || cachedArgb == null) {
            cachedW = w
            cachedH = h
            cachedArgb = IntArray(w * h)
            cachedBitmap = createBitmap(w, h)
        }
    }

    private fun rotateBitmap(src: Bitmap, rotationDeg: Int): Bitmap {
        val rot = ((rotationDeg % 360) + 360) % 360
        if (rot == 0) return src
        val m = android.graphics.Matrix().apply { setRotate(rot.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun analyze(image: ImageProxy) {
        val frameStartMs = SystemClock.uptimeMillis()
        try {
            val helper = faceHelper ?: return

            val w = image.width
            val h = image.height
            ensureCaches(w, h)

            val plane = image.planes[0] // RGBA interleaved
            val buffer: ByteBuffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            buffer.rewind()
            val needed = buffer.remaining()
            var src = cachedRowBuffer
            if (src == null || src.size < needed) {
                src = ByteArray(needed)
                cachedRowBuffer = src
            }
            buffer.get(src, 0, needed)

            val argb = cachedArgb!!
            var dstIndex = 0
            var srcRowStart = 0
            repeat(h) {
                var srcIdx = srcRowStart
                var col = 0
                while (col < w) {
                    val r = src[srcIdx].toInt() and 0xFF
                    val g = src[srcIdx + 1].toInt() and 0xFF
                    val b = src[srcIdx + 2].toInt() and 0xFF
                    val a = src[srcIdx + 3].toInt() and 0xFF
                    argb[dstIndex] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    dstIndex += 1
                    srcIdx += pixelStride
                    col += 1
                }
                srcRowStart += rowStride
            }

            val rawBitmap = cachedBitmap!!
            rawBitmap.setPixels(argb, 0, w, 0, 0, w, h)

            val rotation = image.imageInfo.rotationDegrees
            val uprightBitmap = rotateBitmap(rawBitmap, rotation)
            val uprightW = uprightBitmap.width
            val uprightH = uprightBitmap.height

            val mpImage: MPImage = BitmapImageBuilder(uprightBitmap).build()

            val tsNs = image.imageInfo.timestamp
            val tsMs = if (tsNs != 0L) TimeUnit.NANOSECONDS.toMillis(tsNs) else SystemClock.uptimeMillis()

            val eyeBoxes = helper.detectForVideo(mpImage, 0, tsMs)
            val first = eyeBoxes.firstOrNull()

            if (first != null && eyeClassifier != null) {
                try {
                    val result = eyeClassifier!!.classifyPerEye(
                        frameBitmap = uprightBitmap,
                        leftEyeNorm  = first.first,
                        rightEyeNorm = first.second,
                        frameW = uprightW,
                        frameH = uprightH,
                        rotationDeg = 0
                    )
                    val pL = result.probsLeft[0]   // [closed, open]
                    val pR = result.probsRight[0]

                    lastClosedLeft = when (lastClosedLeft) {
                        null -> when {
                            pL >= THRESH_CLOSE -> true
                            pL <= THRESH_OPEN  -> false
                            else -> lastClosedLeft
                        }
                        true -> if (pL <= THRESH_OPEN) false else true
                        false -> if (pL >= THRESH_CLOSE) true else false
                    }
                    lastClosedRight = when (lastClosedRight) {
                        null -> when {
                            pR >= THRESH_CLOSE -> true
                            pR <= THRESH_OPEN  -> false
                            else -> lastClosedRight
                        }
                        true -> if (pR <= THRESH_OPEN) false else true
                        false -> if (pR >= THRESH_CLOSE) true else false
                    }

                    val bothClosed = (lastClosedLeft == true && lastClosedRight == true)
                    val nowMs = SystemClock.uptimeMillis()

                    if (bothClosed) {
                        if (closedStartMs == 0L) {
                            closedStartMs = nowMs
                            Log.i("EyeBox", "start")
                        }
                        val dur = nowMs - closedStartMs

                        if (!warningShown && dur >= THRESH_MICROSLEEP_MS) {
                            warningShown = true
                            Log.i("EyeBox", "alarm: ${dur}ms")

                            AlarmPlayer.play(applicationContext)
                            prefs.edit().putBoolean(KEY_LAST_SESSION_MICROSLEEP, true).apply()
                            runOnUiThread { startActivity(Intent(this, WarningActivity::class.java)) }
                        }
                    } else {
                        closedStartMs = 0L
                        warningShown = false
                    }
                } catch (e: Throwable) {
                    Log.w("EyeBox", "infer error: ${e.message}")
                }
            }

            runOnUiThread {
                overlay.setStateClosedPerEye(lastClosedLeft, lastClosedRight)
                overlay.updateEyes(
                    first?.first,
                    first?.second,
                    frameW = uprightW,
                    frameH = uprightH,
                    mirrorX = true,
                    rotationDeg = 0
                )
            }
        } catch (t: Throwable) {
            Log.e("EyeBox", "analyze err", t)
        } finally {
            val now = SystemClock.uptimeMillis()
            val latency = (now - frameStartMs).toDouble()
            emaLatencyMs = if (emaLatencyMs < 0) latency else (0.8 * emaLatencyMs + 0.2 * latency)
            if (fpsWindowStartMs == 0L) fpsWindowStartMs = now
            fpsFrames++
            val windowDur = (now - fpsWindowStartMs)
            if (windowDur >= 1000L) {
                lastFps = fpsFrames * 1000.0 / windowDur.toDouble()
                fpsFrames = 0
                fpsWindowStartMs = now
            }
            if (now - lastUiPerfUpdateMs >= 250L) {
                lastUiPerfUpdateMs = now
                val text = String.format("FPS %.1f  |  Lat %.1f ms", lastFps, emaLatencyMs)
                runOnUiThread { tvPerf.text = text }
            }
            image.close()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analyzingEnabled = false
        analyzer?.clearAnalyzer()
        ProcessCameraProvider.getInstance(this).get().unbindAll()

        analysisExecutor.shutdown()
        try { analysisExecutor.awaitTermination(200, TimeUnit.MILLISECONDS) } catch (_: Throwable) {}

        faceHelper?.close(); faceHelper = null
        eyeClassifier?.close(); eyeClassifier = null

        cachedBitmap = null; cachedArgb = null; cachedRowBuffer = null
    }
}