package com.example.eyebox

import android.content.Context
import android.graphics.*
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.*

class EyeClassifier(
    private val context: Context,
    private val modelAssetName: String = "mnv3s_eyes_infer_fp16.tflite",
    private val inputSize: Int = 128,
    numThreads: Int = 4,
    useNNAPI: Boolean = false
) {
    private var interpreter: Interpreter? = null
    private var nnapi: NnApiDelegate? = null

    init {
        val opts = Interpreter.Options().apply {
            setNumThreads(numThreads)
            setUseXNNPACK(true)
            if (useNNAPI) {
                nnapi = NnApiDelegate()
                addDelegate(nnapi)
            }
        }
        interpreter = Interpreter(loadModelFile(modelAssetName), opts)
    }

    fun close() {
        interpreter?.close(); interpreter = null
        nnapi?.close(); nnapi = null
    }

    private fun loadModelFile(assetName: String): MappedByteBuffer {
        val afd = context.assets.openFd(assetName)
        FileInputStream(afd.fileDescriptor).use { fis ->
            return fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.length)
        }
    }

    // include_preprocessing=True -> input float32 [0..255], RGB
    private fun runOnBitmap(resized: Bitmap): FloatArray {
        val byteCount: Int = 4 * inputSize * inputSize * 3
        val input = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputSize * inputSize)
        resized.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        var i = 0
        while (i < pixels.size) {
            val c = pixels[i]
            input.putFloat(((c shr 16) and 0xFF).toFloat()) // R
            input.putFloat(((c shr 8) and 0xFF).toFloat())  // G
            input.putFloat((c and 0xFF).toFloat())          // B
            i++
        }
        val output = Array(1) { FloatArray(2) } // [closed, open]
        interpreter?.run(input.rewind(), output)
        return output[0]
    }

    private fun normRectToPixels(norm: RectF, w: Int, h: Int): Rect {
        val l = (norm.left   * w).coerceIn(0f, (w - 1).toFloat()).roundToInt()
        val r = (norm.right  * w).coerceIn(1f, w.toFloat()).roundToInt()
        val t = (norm.top    * h).coerceIn(0f, (h - 1).toFloat()).roundToInt()
        val b = (norm.bottom * h).coerceIn(1f, h.toFloat()).roundToInt()
        return Rect(l, t, r, b)
    }

    private fun cropWithMargin(src: Bitmap, rectPx: Rect, frameW: Int, frameH: Int, margin: Float): Bitmap? {
        val cx = (rectPx.left + rectPx.right) / 2f
        val cy = (rectPx.top + rectPx.bottom) / 2f
        val hw = rectPx.width()  / 2f * (1f + margin)
        val hh = rectPx.height() / 2f * (1f + margin)
        val l = max(0f, cx - hw).roundToInt()
        val r = min(frameW - 1f, cx + hw).roundToInt()
        val t = max(0f, cy - hh).roundToInt()
        val b = min(frameH - 1f, cy + hh).roundToInt()
        if (r - l < 2 || b - t < 2) return null
        return Bitmap.createBitmap(src, l, t, r - l, b - t)
    }

    private fun rotateBitmap(src: Bitmap, angleDeg: Float): Bitmap {
        if (abs(angleDeg) < 1e-3) return src
        val m = Matrix().apply { setRotate(angleDeg, src.width / 2f, src.height / 2f) }
        return try { Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true) } catch (_: Throwable) { src }
    }

    data class PerEyeResult(
        val probsLeft: FloatArray,   // [closed, open]
        val probsRight: FloatArray   // [closed, open]
    )

    /**
     * Klasifikasi **per mata** (tanpa penggabungan).
     * - frameBitmap SUDAH upright (rotasi=0 di deteksi).
     */
    fun classifyPerEye(
        frameBitmap: Bitmap,
        leftEyeNorm: RectF,
        rightEyeNorm: RectF,
        frameW: Int,
        frameH: Int,
        rotationDeg: Int,   // diabaikan (sudah upright)
        margin: Float = 0.18f,
        doDeskew: Boolean = true
    ): PerEyeResult {

        val rl = normRectToPixels(leftEyeNorm,  frameW, frameH)
        val rr = normRectToPixels(rightEyeNorm, frameW, frameH)

        // Sudut roll antar pusat mata → untuk deskew kecil
        val cLx = (rl.left + rl.right) / 2f
        val cLy = (rl.top  + rl.bottom) / 2f
        val cRx = (rr.left + rr.right) / 2f
        val cRy = (rr.top  + rr.bottom) / 2f
        val rollDeg = Math.toDegrees(atan2((cRy - cLy), (cRx - cLx)).toDouble()).toFloat()

        // Crop per mata + (opsional) deskew
        val cropL = cropWithMargin(frameBitmap, rl, frameW, frameH, margin)
        val cropR = cropWithMargin(frameBitmap, rr, frameW, frameH, margin)

        val eyeL = cropL?.let { if (doDeskew) rotateBitmap(it, -rollDeg) else it }
        val eyeR = cropR?.let { if (doDeskew) rotateBitmap(it, -rollDeg) else it }

        val resizedL = eyeL?.let { Bitmap.createScaledBitmap(it, inputSize, inputSize, true) }
        val resizedR = eyeR?.let { Bitmap.createScaledBitmap(it, inputSize, inputSize, true) }

        val probsL = resizedL?.let { runOnBitmap(it) } ?: floatArrayOf(0.5f, 0.5f)
        val probsR = resizedR?.let { runOnBitmap(it) } ?: floatArrayOf(0.5f, 0.5f)

        return PerEyeResult(probsL, probsR)
    }
}