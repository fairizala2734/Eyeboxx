package com.example.eyebox

import android.content.Context
import android.graphics.RectF
import android.os.SystemClock
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker.FaceLandmarkerOptions
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

class FaceLandmarkerHelper(
    private val context: Context,
    private val maxFaces: Int = 1,
    private val minFaceDetectionConfidence: Float = 0.5f,
    private val minFaceTrackingConfidence: Float = 0.5f,
    private val minFacePresenceConfidence: Float = 0.5f,
    private val preferGpu: Boolean = false
) {
    private var landmarker: FaceLandmarker? = null

    fun setup(modelAssetPath: String = "face_landmarker.task") {
        if (landmarker != null) return

        val base = BaseOptions.builder()
            .setModelAssetPath(modelAssetPath)
            .setDelegate(if (preferGpu) Delegate.GPU else Delegate.CPU)
            .build()

        val options = FaceLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setNumFaces(maxFaces)
            .setMinFaceDetectionConfidence(minFaceDetectionConfidence)
            .setMinFacePresenceConfidence(minFacePresenceConfidence)
            .setMinTrackingConfidence(minFaceTrackingConfidence)
            .setOutputFaceBlendshapes(false)
            .setRunningMode(RunningMode.VIDEO)
            .build()

        landmarker = FaceLandmarker.createFromOptions(context.applicationContext, options)
    }

    fun close() {
        landmarker?.close()
        landmarker = null
    }

    fun detectForVideo(mpImage: MPImage, rotationDegrees: Int, timestampMs: Long): List<Pair<RectF, RectF>> {
        val lm = landmarker ?: return emptyList()

        val imgOpts = ImageProcessingOptions.builder()
            .setRotationDegrees(rotationDegrees)
            .build()

        val result: FaceLandmarkerResult = lm.detectForVideo(mpImage, imgOpts, timestampMs)
        val out = ArrayList<Pair<RectF, RectF>>(1)

        for (face in result.faceLandmarks()) {
            val left = rectFromIndices(face, LEFT_EYE_INDICES)
            val right = rectFromIndices(face, RIGHT_EYE_INDICES)
            if (left != null && right != null) out.add(left to right)
        }
        return out
    }

    fun detectForVideo(mpImage: MPImage, rotationDegrees: Int): List<Pair<RectF, RectF>> =
        detectForVideo(mpImage, rotationDegrees, SystemClock.uptimeMillis())

    private fun rectFromIndices(
        landmarks: List<NormalizedLandmark>,
        indices: IntArray
    ): RectF? {
        if (indices.isEmpty()) return null
        var minX = 1f; var minY = 1f; var maxX = 0f; var maxY = 0f
        for (i in indices) {
            if (i !in landmarks.indices) continue
            val p = landmarks[i]
            if (p.x() < minX) minX = p.x()
            if (p.y() < minY) minY = p.y()
            if (p.x() > maxX) maxX = p.x()
            if (p.y() > maxY) maxY = p.y()
        }
        if (maxX <= minX || maxY <= minY) return null
        return RectF(minX, minY, maxX, maxY)
    }

    companion object {
        val LEFT_EYE_INDICES = intArrayOf(33, 7, 163, 144, 145, 153, 154, 155, 133)
        val RIGHT_EYE_INDICES = intArrayOf(263, 249, 390, 373, 374, 380, 381, 382, 362)
    }
}