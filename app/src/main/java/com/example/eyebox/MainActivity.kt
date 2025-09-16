package com.example.eyebox

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import java.nio.ByteBuffer

class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: EyeOverlay
    private var faceHelper: FaceLandmarkerHelper? = null
    private var eyeClassifier: EyeClassifier? = null

    private var preview: Preview? = null
    private var analyzer: ImageAnalysis? = null

    // Cache per frame
    private var cachedBitmap: Bitmap? = null
    private var cachedArgb: IntArray? = null
    private var cachedRowBuffer: ByteArray? = null
    private var cachedW = -1
    private var cachedH = -1

    // Throttle inferensi
    private var lastInferMs = 0L
    private val MIN_INFER_INTERVAL_MS = 100L // ~10 fps

    // ====== Sticky + hysteresis PER MATA ======
    private var lastClosedLeft: Boolean? = null
    private var lastClosedRight: Boolean? = null
    private val THRESH_CLOSE = 0.70f
    private val THRESH_OPEN  = 0.30f

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, "Izin kamera ditolak", Toast.LENGTH_SHORT).show()
        }
    }

    private var orientationListener: OrientationEventListener? = null
    private var lastSurfaceRotation: Int = Surface.ROTATION_0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.eyeOverlay)
        previewView.keepScreenOn = true

        MaterialAlertDialogBuilder(this)
            .setTitle("EyeBox")
            .setMessage("Aplikasi akan menggunakan kamera untuk mendeteksi posisi mata.")
            .setPositiveButton("OK") { _, _ -> ensureCameraPermission() }
            .setCancelable(false)
            .show()

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                val newRotation = when (orientation) {
                    in 45..134  -> Surface.ROTATION_270
                    in 225..314 -> Surface.ROTATION_90
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

    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        orientationListener?.enable()
    }

    override fun onPause() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        orientationListener?.disable()
        super.onPause()
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

            val resSelectorPreview = ResolutionSelector.Builder()
                .setAspectRatioStrategy(
                    AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO)
                )
                .build()

            val resSelectorAnalysis = ResolutionSelector.Builder()
                .setAspectRatioStrategy(
                    AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO)
                )
                .setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()

            preview = Preview.Builder()
                .setResolutionSelector(resSelectorPreview)
                .setTargetRotation(initialRotation)
                .build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            analyzer = ImageAnalysis.Builder()
                .setResolutionSelector(resSelectorAnalysis)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(initialRotation)
                .build().also { ia ->
                    ia.setAnalyzer(Dispatchers.Default.asExecutor()) { image -> analyze(image) }
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, analyzer)

                if (faceHelper == null) {
                    faceHelper = FaceLandmarkerHelper(applicationContext).apply {
                        setup("face_landmarker.task")
                    }
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

            // Normalisasi rotasi → upright
            val rotation = image.imageInfo.rotationDegrees
            val uprightBitmap = rotateBitmap(rawBitmap, rotation)
            val uprightW = uprightBitmap.width
            val uprightH = uprightBitmap.height

            // Deteksi landmark pada upright (rotation=0)
            val mpImage: MPImage = BitmapImageBuilder(uprightBitmap).build()
            val eyeBoxes = helper.detect(mpImage, 0)
            val first = eyeBoxes.firstOrNull()

            var pL = Float.NaN
            var pR = Float.NaN

            if (first != null && eyeClassifier != null) {
                val now = SystemClock.uptimeMillis()
                if (now - lastInferMs >= MIN_INFER_INTERVAL_MS) {
                    lastInferMs = now
                    try {
                        val result = eyeClassifier!!.classifyPerEye(
                            frameBitmap = uprightBitmap,
                            leftEyeNorm  = first.first,
                            rightEyeNorm = first.second,
                            frameW = uprightW,
                            frameH = uprightH,
                            rotationDeg = 0 // sudah upright
                        )
                        pL = result.probsLeft[0]   // [closed, open]
                        pR = result.probsRight[0]

                        // Hysteresis PER MATA
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

                        Log.d("EyeBox", "rot=$rotation, pClosedL=${"%.2f".format(pL)} pClosedR=${"%.2f".format(pR)} stateL=$lastClosedLeft stateR=$lastClosedRight")
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }
                }
            } else {
                // jika hilang wajah, jangan reset state supaya tetap stabil
            }

            runOnUiThread {
                overlay.setStateClosedPerEye(lastClosedLeft, lastClosedRight)
                overlay.setDebugScores(pL, pR) // tampilkan skor kecil di badge
                // Overlay juga di upright space
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
            t.printStackTrace()
        } finally {
            image.close()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        faceHelper?.close(); faceHelper = null
        eyeClassifier?.close(); eyeClassifier = null
        cachedBitmap = null; cachedArgb = null; cachedRowBuffer = null
    }
}