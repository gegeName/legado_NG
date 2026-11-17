package io.legado.app.ui.design.components.compose

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import androidx.core.graphics.ColorUtils
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

/** Drawing shared by the loading composable and native rendering checks. */
internal class NgBookLoadingPainter(private val accent: Int, private val background: Int) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = shade(.15f)
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val bookPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.4f
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val bounds = RectF()
    private val shaderMatrix = Matrix()

    // Sample the design's continuous curves, instead of interpolating a few flat color stops.
    // The determinate curve is periodic: no visible seam when the highlight crosses twelve.
    private val positions = FloatArray(241) { it / 240f }
    private val unknownSweep = SweepGradient(0f, 0f, IntArray(241) { index ->
        val angle = positions[index] * 360f
        // Keep the shader seam outside the arc and its round caps. Otherwise the tail's
        // cap samples the bright final color across zero degrees, producing a stray dot.
        val fraction = ((angle - 30f) / 270f).coerceIn(0f, 1f)
        val brightness = if (angle > 330f) .05f + .95f * (360f - angle) / 30f
            else .05f + .95f * fraction.pow(1.25f)
        shade(brightness)
    }, positions)
    private val progressSweep = SweepGradient(0f, 0f, IntArray(241) { index ->
        val angle = positions[index] * 360f
        val highlight = (.5 + .5 * cos((angle - 306f) * PI / 180)).pow(12).toFloat()
        shade(.6f + .4f * highlight)
    }, positions)
    private val book = Path().apply {
        moveTo(-23f, -16f)
        cubicTo(-12f, -20f, -6f, -15f, 0f, -11f)
        cubicTo(6f, -15f, 12f, -20f, 23f, -16f)
        lineTo(23f, 16f)
        cubicTo(13f, 12f, 6f, 15f, 0f, 20f)
        cubicTo(-6f, 15f, -13f, 12f, -23f, 16f)
        close()
        moveTo(0f, -11f)
        lineTo(0f, 19f)
        moveTo(-16f, -6f)
        lineTo(-7f, -3f)
        moveTo(7f, -3f)
        lineTo(16f, -6f)
    }

    fun draw(
        canvas: Canvas,
        width: Float,
        height: Float,
        stroke: Float,
        progress: Float?,
        phase: Float,
        error: Boolean = false,
    ) {
        val size = minOf(width, height)
        val radius = size / 2f - stroke
        if (radius <= 0f) return
        val saved = canvas.save()
        try {
            canvas.translate(width / 2f, height / 2f)
            track.strokeWidth = stroke
            ring.strokeWidth = stroke
            bounds.set(-radius, -radius, radius, radius)
            canvas.drawCircle(0f, 0f, radius, track)
            if (error) {
                ring.shader = null
                ring.color = accent
                val unit = size / 80f
                canvas.drawLine(0f, -12f * unit, 0f, 6f * unit, ring)
                canvas.drawCircle(0f, 14f * unit, stroke / 2f, dot)
                return
            }

            val shader = if (progress == null) unknownSweep else progressSweep
            shaderMatrix.setRotate(if (progress == null) phase - 120f else phase)
            shader.setLocalMatrix(shaderMatrix)
            ring.shader = shader
            if (progress == null) {
                // A long, gently fading tail follows the bright leading edge clockwise.
                canvas.drawArc(bounds, phase - 90f, 270f, false, ring)
            } else {
                val fraction = progress.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
                // Rotate only the shader. Actual progress always begins at twelve o'clock.
                if (fraction >= 1f) canvas.drawCircle(0f, 0f, radius, ring)
                else if (fraction > 0f) canvas.drawArc(bounds, -90f, fraction * 360f, false, ring)
            }
            val bookScale = size / 80f * .7f
            canvas.scale(bookScale, bookScale)
            canvas.drawPath(book, bookPaint)
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    // Blend against the surface, not over the track: stacked alpha washes out the faint tail.
    private fun shade(brightness: Float): Int = ColorUtils.blendARGB(background, accent, brightness)
}
