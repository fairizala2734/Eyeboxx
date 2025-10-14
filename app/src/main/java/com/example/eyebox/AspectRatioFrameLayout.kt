package com.example.eyebox

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import kotlin.math.min

class AspectRatioFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private var ratioW = 3
    private var ratioH = 4

    init {
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.AspectRatioFrameLayout)
            ratioW = a.getInt(R.styleable.AspectRatioFrameLayout_ratioWidth, 3)
            ratioH = a.getInt(R.styleable.AspectRatioFrameLayout_ratioHeight, 4)
            a.recycle()
        }
        // biar child bisa clip kalau mau rounded bg di parent
        clipToPadding = false
        clipChildren = true
    }

    fun setRatio(w: Int, h: Int) {
        ratioW = w; ratioH = h
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec)
        val maxH = MeasureSpec.getSize(heightMeasureSpec)

        if (ratioW <= 0 || ratioH <= 0 || maxW == 0 || maxH == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        // hitung ukuran terbesar yang muat dengan rasio w:h
        val targetWbyH = maxH * ratioW / ratioH
        val targetHbyW = maxW * ratioH / ratioW

        val finalW: Int
        val finalH: Int
        if (targetWbyH <= maxW) {
            // batasi oleh tinggi
            finalW = targetWbyH
            finalH = maxH
        } else {
            // batasi oleh lebar
            finalW = maxW
            finalH = targetHbyW
        }

        val childWSpec = MeasureSpec.makeMeasureSpec(finalW, MeasureSpec.EXACTLY)
        val childHSpec = MeasureSpec.makeMeasureSpec(finalH, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) {
            getChildAt(i).measure(childWSpec, childHSpec)
        }
        setMeasuredDimension(finalW, finalH)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = right - left
        val h = bottom - top
        for (i in 0 until childCount) {
            getChildAt(i).layout(0, 0, w, h)
        }
    }
}
