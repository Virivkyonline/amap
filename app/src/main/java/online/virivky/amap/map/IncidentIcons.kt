package online.virivky.amap.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Značky dopravných udalostí v štýle dopravných značiek. */
object IncidentIcons {
    val names = listOf("inc-accident", "inc-jam", "inc-closed", "inc-works", "inc-danger")

    private const val RED = 0xFFE5333A.toInt()
    private const val AMBER = 0xFFFFB300.toInt()

    /** Nakreslí značku [name] do plochy w × h so stredom v (cx, cy). */
    fun draw(c: Canvas, name: String, cx: Float, cy: Float, size: Float) {
        if (name == "inc-closed") {
            closed(c, cx, cy, size)
            return
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val triangle = Path()
        val h = size * 0.88f
        val top = cy - h / 2
        triangle.moveTo(cx, top)
        triangle.lineTo(cx + size / 2, top + h)
        triangle.lineTo(cx - size / 2, top + h)
        triangle.close()
        // Biely okraj, aby značka bola vidieť na tmavej aj svetlej mape.
        fill.color = Color.WHITE
        fill.style = Paint.Style.STROKE
        fill.strokeWidth = size * 0.16f
        fill.strokeJoin = Paint.Join.ROUND
        c.drawPath(triangle, fill)
        fill.style = Paint.Style.FILL
        fill.color = RED
        c.drawPath(triangle, fill)
        val inner = Path()
        val k = 0.62f
        val innerTop = top + h * (1 - k) * 0.72f
        inner.moveTo(cx, innerTop)
        inner.lineTo(cx + size / 2 * k, innerTop + h * k)
        inner.lineTo(cx - size / 2 * k, innerTop + h * k)
        inner.close()
        fill.color = if (name == "inc-works" || name == "inc-danger") AMBER else Color.WHITE
        c.drawPath(inner, fill)
        fill.color = Color.BLACK
        val gy = innerTop + h * k * 0.62f
        when (name) {
            "inc-jam" -> {
                // Rad áut v kolóne.
                val w = size * 0.1f
                for (i in -1..1) c.drawRoundRect(RectF(cx + i * w * 1.5f - w / 2, gy - w * 0.6f, cx + i * w * 1.5f + w / 2, gy + w * 0.8f), w * 0.2f, w * 0.2f, fill)
            }
            "inc-works" -> {
                // Zábrana s pruhmi.
                val w = size * 0.3f
                val hh = size * 0.1f
                c.drawRect(cx - w / 2, gy - hh / 2, cx + w / 2, gy + hh / 2, fill)
                fill.color = Color.WHITE
                for (i in 0..1) c.drawRect(cx - w / 2 + w * (0.2f + i * 0.4f), gy - hh / 2, cx - w / 2 + w * (0.4f + i * 0.4f), gy + hh / 2, fill)
            }
            else -> {
                // Výkričník.
                val w = size * 0.075f
                c.drawRoundRect(RectF(cx - w / 2, gy - size * 0.17f, cx + w / 2, gy + size * 0.04f), w / 2, w / 2, fill)
                c.drawCircle(cx, gy + size * 0.11f, w * 0.6f, fill)
            }
        }
    }

    /** Zákaz vjazdu: červený kruh s bielym pruhom. */
    private fun closed(c: Canvas, cx: Float, cy: Float, size: Float) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = size / 2
        fill.color = Color.WHITE
        c.drawCircle(cx, cy, r, fill)
        fill.color = RED
        c.drawCircle(cx, cy, r * 0.86f, fill)
        fill.color = Color.WHITE
        c.drawRect(cx - r * 0.55f, cy - r * 0.16f, cx + r * 0.55f, cy + r * 0.16f, fill)
    }
}
