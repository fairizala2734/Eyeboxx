package com.example.eyebox

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.atan2

class EyeClassifier(
    private val context: Context,
    private val modelAssetName: String = "mnv3s_eyes_infer_fp16.tflite",
    private val inputSize: Int = 128,
    numThreads: Int = 4,
    useNNAPI: Boolean = false
) {
    private var interpreter: Interpreter? = null
    private var nnapi: NnApiDelegate? = null
    private val tfliteLock = Any()
    @Volatile private var isOpen = true

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
        synchronized(tfliteLock) {
            isOpen = false
            interpreter?.close(); interpreter = null
            nnapi?.close(); nnapi = null
        }
    }

    private fun loadModelFile(assetName: String): MappedByteBuffer {
        val afd = context.assets.openFd(assetName)
        return try {
            FileInputStream(afd.fileDescriptor).use { fis ->
                fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.length)
            }
        } finally {
            try { afd.close() } catch (_: Throwable) {}
        }
    }

    private fun runOnBitmap(square: Bitmap): FloatArray {
        if (!isOpen) return floatArrayOf(0.5f, 0.5f)

        val byteCount = 4 * inputSize * inputSize * 3
        val input = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputSize * inputSize)
        square.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        for (c in pixels) {
            input.putFloat(((c ushr 16) and 0xFF).toFloat()) // R
            input.putFloat(((c ushr 8) and 0xFF).toFloat())  // G
            input.putFloat((c and 0xFF).toFloat())           // B
        }

        val output = Array(1) { FloatArray(2) }
        synchronized(tfliteLock) {
            if (!isOpen) return floatArrayOf(0.5f, 0.5f)
            interpreter?.run(input.rewind(), output)
        }
        return output[0]
    }

    private fun normRectToPixels(norm: RectF, w: Int, h: Int): Rect {
        val l = (norm.left   * w).coerceIn(0f, (w - 1).toFloat()).roundToInt()
        val r = (norm.right  * w).coerceIn(1f, w.toFloat()).roundToInt()
        val t = (norm.top    * h).coerceIn(0f, (h - 1).toFloat()).roundToInt()
        val b = (norm.bottom * h).coerceIn(1f, h.toFloat()).roundToInt()
        return Rect(l, t, r, b)
    }

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

    private fun rotateBitmap(src: Bitmap, angleDeg: Float): Bitmap {
        if (abs(angleDeg) < 1e-3) return src
        val m = Matrix().apply { setRotate(angleDeg, src.width / 2f, src.height / 2f) }
        return try { Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true) } catch (_: Throwable) { src }
    }

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

    fun classifyPerEye(
        frameBitmap: Bitmap,
        leftEyeNorm: RectF,
        rightEyeNorm: RectF,
        frameW: Int,
        frameH: Int,
        rotationDeg: Int, // unused (upright already)
        margin: Float = 0.35f,
        doDeskew: Boolean = true
    ): PerEyeResult {
        val rl = normRectToPixels(leftEyeNorm,  frameW, frameH)
        val rr = normRectToPixels(rightEyeNorm, frameW, frameH)

        val cLx = (rl.left + rl.right) / 2f
        val cLy = (rl.top  + rl.bottom) / 2f
        val cRx = (rr.left + rr.right) / 2f
        val cRy = (rr.top  + rr.bottom) / 2f
        val rollDeg = Math.toDegrees(atan2((cRy - cLy), (cRx - cLx)).toDouble()).toFloat()

        val sqL0 = cropSquareWithMargin(frameBitmap, rl, frameW, frameH, margin)
        val sqR0 = cropSquareWithMargin(frameBitmap, rr, frameW, frameH, margin)

        val sqL1 = sqL0?.let { if (doDeskew) rotateBitmap(it, -rollDeg) else it }
        val sqR1 = sqR0?.let { if (doDeskew) rotateBitmap(it, -rollDeg) else it }

        val sqL = sqL1?.let { centerCropSquare(it) }
        val sqR = sqR1?.let { centerCropSquare(it) }

        val resizedL = sqL?.let { Bitmap.createScaledBitmap(it, inputSize, inputSize, true) }
        val resizedR = sqR?.let { Bitmap.createScaledBitmap(it, inputSize, inputSize, true) }

        val probsL = resizedL?.let { runOnBitmap(it) } ?: floatArrayOf(0.5f, 0.5f)
        val probsR = resizedR?.let { runOnBitmap(it) } ?: floatArrayOf(0.5f, 0.5f)

        return PerEyeResult(probsL, probsR)
    }
}