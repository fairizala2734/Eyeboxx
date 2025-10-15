package com.example.eyebox

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

class StrokeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var strokeColor: Int = 0xFF000000.toInt()
    private var strokeWidthPx: Float = 0f

    init {
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.StrokeTextView)
            strokeColor = a.getColor(R.styleable.StrokeTextView_strokeColor, strokeColor)
            strokeWidthPx = a.getDimension(R.styleable.StrokeTextView_strokeWidth, 0f)
            a.recycle()
        }
        paint.isAntiAlias = true
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeMiter = 10f
    }

    override fun onDraw(canvas: Canvas) {
        if (strokeWidthPx > 0f) {
            val originalColor = currentTextColor

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidthPx
            setTextColor(strokeColor)
            super.onDraw(canvas)

            paint.style = Paint.Style.FILL
            setTextColor(originalColor)
            super.onDraw(canvas)
        } else {
            super.onDraw(canvas)
        }
    }
}