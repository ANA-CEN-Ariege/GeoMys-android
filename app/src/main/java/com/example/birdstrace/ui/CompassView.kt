package com.example.birdstrace.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class CompassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var azimuth = 0f

    private val paintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val paintNord = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE53935.toInt()
        style = Paint.Style.FILL
    }
    private val paintSud = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFBDBDBD.toInt()
        style = Paint.Style.FILL
    }
    private val paintCenter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF424242.toInt()
        style = Paint.Style.FILL
    }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    fun setAzimuth(degrees: Float) {
        azimuth = degrees
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) - 2f

        canvas.drawCircle(cx, cy, r, paintBg)

        canvas.save()
        canvas.rotate(azimuth, cx, cy)

        val needleW = r * 0.26f
        val needleH = r * 0.70f
        val gap = r * 0.14f

        val pathNord = Path().apply {
            moveTo(cx, cy - needleH)
            lineTo(cx - needleW, cy - gap)
            lineTo(cx + needleW, cy - gap)
            close()
        }
        canvas.drawPath(pathNord, paintNord)

        val pathSud = Path().apply {
            moveTo(cx, cy + needleH)
            lineTo(cx - needleW, cy + gap)
            lineTo(cx + needleW, cy + gap)
            close()
        }
        canvas.drawPath(pathSud, paintSud)

        paintText.textSize = r * 0.36f
        val textY = cy - needleH * 0.42f + paintText.textSize * 0.36f
        canvas.drawText("N", cx, textY, paintText)

        canvas.restore()

        canvas.drawCircle(cx, cy, r * 0.10f, paintCenter)
    }
}
