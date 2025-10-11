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
    private fun runOnBitmap(square: Bitmap): FloatArray {
        val byteCount: Int = 4 * inputSize * inputSize * 3
        val input = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputSize * inputSize)
        square.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
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

    /** Buat crop SQUARE di sekitar rect dengan margin relatif. Selalu 1:1. */
    private fun cropSquareWithMargin(
        src: Bitmap,
        rectPx: Rect,
        frameW: Int,
        frameH: Int,
        margin: Float
    ): Bitmap? {
        val cx = (rectPx.left + rectPx.right) / 2f
        val cy = (rectPx.top + rectPx.bottom) / 2f

        val baseSize = max(rectPx.width(), rectPx.height()).toFloat()
        var halfSize = 0.5f * baseSize * (1f + margin)

        // Clamp halfSize supaya kotak tetap di dalam frame
        val maxHalf = min(min(cx, frameW - 1f - cx), min(cy, frameH - 1f - cy))
        if (maxHalf <= 1f) return null
        halfSize = min(halfSize, maxHalf)

        val l = round(cx - halfSize).toInt().coerceAtLeast(0)
        val t = round(cy - halfSize).toInt().coerceAtLeast(0)
        var size = round(halfSize * 2f).toInt()
        if (l + size > frameW) size = frameW - l
        if (t + size > frameH) size = frameH - t
        if (size < 2) return null

        return Bitmap.createBitmap(src, l, t, size, size)
    }

    /** Rotasi kecil (deskew). */
    private fun rotateBitmap(src: Bitmap, angleDeg: Float): Bitmap {
        if (abs(angleDeg) < 1e-3) return src
        val m = Matrix().apply { setRotate(angleDeg, src.width / 2f, src.height / 2f) }
        return try { Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true) } catch (_: Throwable) { src }
    }

    /** Setelah rotasi, potong tengah ke square lagi agar tidak distorsi saat resize ke 128x128. */
    private fun centerCropSquare(src: Bitmap): Bitmap {
        val s = min(src.width, src.height)
        if (src.width == src.height) return src
        val cx = src.width / 2
        val cy = src.height / 2
        val left = (cx - s / 2).coerceAtLeast(0)
        val top  = (cy - s / 2).coerceAtLeast(0)
        val w = if (left + s > src.width) src.width - left else s
        val h = if (top + s > src.height) src.height - top else s
        return Bitmap.createBitmap(src, left, top, min(w, h), min(w, h))
    }

    data class PerEyeResult(
        val probsLeft: FloatArray,   // [closed, open]
        val probsRight: FloatArray   // [closed, open]
    )

    /**
     * Klasifikasi **per mata** dengan crop 1:1 (square) + deskew + center-crop square lagi.
     * - frameBitmap SUDAH upright.
     */
    fun classifyPerEye(
        frameBitmap: Bitmap,
        leftEyeNorm: RectF,
        rightEyeNorm: RectF,
        frameW: Int,
        frameH: Int,
        rotationDeg: Int,   // diabaikan (sudah upright)
        margin: Float = 0.35f,   // naikan ke 0.5f jika bagian atas mata masih kepotong
        doDeskew: Boolean = true
    ): PerEyeResult {

        val rl = normRectToPixels(leftEyeNorm,  frameW, frameH)
        val rr = normRectToPixels(rightEyeNorm, frameW, frameH)

        // Sudut roll antar pusat mata
        val cLx = (rl.left + rl.right) / 2f
        val cLy = (rl.top  + rl.bottom) / 2f
        val cRx = (rr.left + rr.right) / 2f
        val cRy = (rr.top  + rr.bottom) / 2f
        val rollDeg = Math.toDegrees(atan2((cRy - cLy), (cRx - cLx)).toDouble()).toFloat()

        // 1) crop square + margin
        val sqL0 = cropSquareWithMargin(frameBitmap, rl, frameW, frameH, margin)
        val sqR0 = cropSquareWithMargin(frameBitmap, rr, frameW, frameH, margin)

        // 2) deskew kecil (opsional)
        val sqL1 = sqL0?.let { if (doDeskew) rotateBitmap(it, -rollDeg) else it }
        val sqR1 = sqR0?.let { if (doDeskew) rotateBitmap(it, -rollDeg) else it }

        // 3) center-crop square lagi (supaya tetap 1:1 setelah rotasi)
        val sqL = sqL1?.let { centerCropSquare(it) }
        val sqR = sqR1?.let { centerCropSquare(it) }

        // 4) resize ke 128×128 dan infer
        val resizedL = sqL?.let { Bitmap.createScaledBitmap(it, inputSize, inputSize, true) }
        val resizedR = sqR?.let { Bitmap.createScaledBitmap(it, inputSize, inputSize, true) }

        val probsL = resizedL?.let { runOnBitmap(it) } ?: floatArrayOf(0.5f, 0.5f)
        val probsR = resizedR?.let { runOnBitmap(it) } ?: floatArrayOf(0.5f, 0.5f)

        return PerEyeResult(probsL, probsR)
    }
}
