package com.example.eyebox

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class EyeOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.GREEN
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        textSize = 14f * resources.displayMetrics.scaledDensity
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.GREEN
    }

    private val leftEye = RectF()
    private val rightEye = RectF()
    private var haveData = false

    private var frameW = 0
    private var frameH = 0
    private var mirrorX = false
    private var rotationDeg = 0 // 0/90/180/270

    private val density = resources.displayMetrics.density
    private var expandXRel = 0.35f
    private var expandYTopRel = 1.10f
    private var expandYBottomRel = 0.50f
    private var minLeftPadPx = 8f * density
    private var minRightPadPx = 8f * density
    private var minTopPadPx = 20f * density
    private var minBottomPadPx = 8f * density

    private val tmpViewRectL = RectF()
    private val tmpViewRectR = RectF()
    private val tmpBadgeRect = RectF()

    private var isClosedLeft: Boolean? = null
    private var isClosedRight: Boolean? = null

    fun setBoxExpansionAsym(
        hExpand: Float = expandXRel,
        vTop: Float = expandYTopRel,
        vBottom: Float = expandYBottomRel,
        minHorizPx: Float = 8f * density,
        minTopPx: Float = 20f * density,
        minBottomPx: Float = 8f * density
    ) {
        expandXRel = hExpand
        expandYTopRel = vTop
        expandYBottomRel = vBottom
        minLeftPadPx = minHorizPx
        minRightPadPx = minHorizPx
        minTopPadPx = minTopPx
        minBottomPadPx = minBottomPx
        invalidate()
    }

    fun setStateClosedPerEye(left: Boolean?, right: Boolean?) {
        isClosedLeft = left
        isClosedRight = right
        postInvalidateOnAnimation()
    }

    fun updateEyes(
        left: RectF?,
        right: RectF?,
        frameW: Int,
        frameH: Int,
        mirrorX: Boolean,
        rotationDeg: Int
    ) {
        this.frameW = frameW
        this.frameH = frameH
        this.mirrorX = mirrorX
        this.rotationDeg = ((rotationDeg % 360) + 360) % 360

        if (left == null || right == null) {
            haveData = false
        } else {
            leftEye.set(left)
            rightEye.set(right)
            haveData = true
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!haveData || frameW == 0 || frameH == 0) return

        val vw = width.toFloat()
        val vh = height.toFloat()

        val imgW = if (rotationDeg == 90 || rotationDeg == 270) frameH else frameW
        val imgH = if (rotationDeg == 90 || rotationDeg == 270) frameW else frameH
        val imgAR = imgW.toFloat() / imgH.toFloat()
        val viewAR = vw / vh

        val drawW: Float
        val drawH: Float
        val offX: Float
        val offY: Float
        if (viewAR > imgAR) {
            drawH = vh
            drawW = imgAR * drawH
            offX = (vw - drawW) / 2f
            offY = 0f
        } else {
            drawW = vw
            drawH = drawW / imgAR
            offX = 0f
            offY = (vh - drawH) / 2f
        }

        fun mapRectAndExpand(norm: RectF, out: RectF) {
            var minX = 1f; var minY = 1f; var maxX = 0f; var maxY = 0f
            fun acc(nx: Float, ny: Float) {
                var rx: Float; var ry: Float
                when (rotationDeg) {
                    90  -> { rx = ny;        ry = 1f - nx }
                    180 -> { rx = 1f - nx;   ry = 1f - ny }
                    270 -> { rx = 1f - ny;   ry = nx }
                    else -> { rx = nx;       ry = ny }
                }
                if (mirrorX) rx = 1f - rx
                if (rx < minX) minX = rx
                if (rx > maxX) maxX = rx
                if (ry < minY) minY = ry
                if (ry > maxY) maxY = ry
            }
            acc(norm.left,  norm.top)
            acc(norm.right, norm.top)
            acc(norm.left,  norm.bottom)
            acc(norm.right, norm.bottom)

            out.left   = offX + minX * drawW
            out.top    = offY + minY * drawH
            out.right  = offX + maxX * drawW
            out.bottom = offY + maxY * drawH

            val w0 = out.width()
            val h0 = out.height()
            val dxHalf = max(w0 * (expandXRel / 2f), 0f)
            val dxLeft  = max(dxHalf, minLeftPadPx)
            val dxRight = max(dxHalf, minRightPadPx)
            val dyTop    = max(h0 * expandYTopRel,    minTopPadPx)
            val dyBottom = max(h0 * expandYBottomRel, minBottomPadPx)

            out.left  -= dxLeft
            out.right += dxRight
            out.top   -= dyTop
            out.bottom += dyBottom

            val leftBound = offX
            val topBound = offY
            val rightBound = offX + drawW
            val bottomBound = offY + drawH
            if (out.left   < leftBound)   out.left   = leftBound
            if (out.top    < topBound)    out.top    = topBound
            if (out.right  > rightBound)  out.right  = rightBound
            if (out.bottom > bottomBound) out.bottom = bottomBound
        }

        mapRectAndExpand(leftEye, tmpViewRectL)
        when (isClosedLeft) {
            true  -> { boxPaint.color = Color.RED;  badgePaint.color = Color.RED }
            false -> { boxPaint.color = Color.GREEN;badgePaint.color = Color.GREEN }
            null  -> { boxPaint.color = Color.GRAY; badgePaint.color = Color.GRAY }
        }
        canvas.drawRect(tmpViewRectL, boxPaint)
        drawBadgeAbove(canvas, tmpViewRectL, if (isClosedLeft == true) "bahaya" else "aman")

        mapRectAndExpand(rightEye, tmpViewRectR)
        when (isClosedRight) {
            true  -> { boxPaint.color = Color.RED;  badgePaint.color = Color.RED }
            false -> { boxPaint.color = Color.GREEN;badgePaint.color = Color.GREEN }
            null  -> { boxPaint.color = Color.GRAY; badgePaint.color = Color.GRAY }
        }
        canvas.drawRect(tmpViewRectR, boxPaint)
        drawBadgeAbove(canvas, tmpViewRectR, if (isClosedRight == true) "bahaya" else "aman")
    }

    private fun drawBadgeAbove(canvas: Canvas, rect: RectF, label: String) {
        val padH = 6f * density
        val padV = 4f * density
        val radius = 6f * density
        val gap = 8f * density

        val textWidth = textPaint.measureText(label)
        val fm = textPaint.fontMetrics
        val textHeight = (fm.descent - fm.ascent)

        val badgeW = textWidth + 2 * padH
        val badgeH = textHeight + 2 * padV

        val cx = (rect.left + rect.right) / 2f
        var left = cx - badgeW / 2f
        var top = rect.top - gap - badgeH
        if (top < 0f) top = 0f
        if (left < 0f) left = 0f
        var right = left + badgeW
        var bottom = top + badgeH
        if (right > width) { right = width.toFloat(); left = right - badgeW }
        if (bottom > height) { bottom = height.toFloat(); top = bottom - badgeH }

        tmpBadgeRect.set(left, top, right, bottom)
        canvas.drawRoundRect(tmpBadgeRect, radius, radius, badgePaint)
        val tx = left + padH
        val ty = top + padV - fm.ascent
        canvas.drawText(label, tx, ty, textPaint)
    }
}