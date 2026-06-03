/*
 * GeoMys-Android — application Android de saisie naturaliste pour GeoNature.
 * Copyright (C) 2026 ANA - CEN Ariège
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package fr.ariegenature.geomys.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class CompassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var azimuth = 0f
    private var actif = false

    private val paintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    // Anneau bleu pour indiquer que la boussole pilote la rotation de la carte.
    private val paintRingActif = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1976D2.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
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

    /** Indique si la boussole pilote la rotation de la carte — dessine un anneau bleu. */
    fun setActif(actif: Boolean) {
        if (this.actif == actif) return
        this.actif = actif
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) - 2f

        canvas.drawCircle(cx, cy, r, paintBg)
        if (actif) canvas.drawCircle(cx, cy, r - paintRingActif.strokeWidth / 2f, paintRingActif)

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
