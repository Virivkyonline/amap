package online.virivky.amap.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.cos
import kotlin.math.sin

/** Značenie na pruhoch pri pohľade na pruhy: biele šípky na vozovke a červené X. */
object LaneMarks {
    /** Názov ikony → smer šípky v stupňoch (0 = rovno, záporné = vľavo). */
    val arrows = mapOf(
        "lane-straight" to 0f,
        "lane-slight-left" to -45f,
        "lane-slight-right" to 45f,
        "lane-left" to -90f,
        "lane-right" to 90f,
        "lane-uturn" to 180f,
    )

    /** Biela šípka na vozovke (pri ikone 24 × 56 dp), hore = smer jazdy. */
    fun arrow(c: Canvas, angle: Float, w: Float, h: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(0xEE, 0xFF, 0xFF, 0xFF)
            style = Paint.Style.STROKE
            strokeWidth = w * 0.18f
            strokeJoin = Paint.Join.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = paint.color }
        val cx = w / 2
        val bottom = h - 1f
        val path = Path()
        val head: Pair<Float, Float>
        val dir: Double
        when (angle) {
            0f -> {
                path.moveTo(cx, bottom)
                path.lineTo(cx, h * 0.32f)
                head = cx to h * 0.04f
                dir = 0.0
            }
            180f -> {
                // Otočenie: hore a oblúkom späť dolu vľavo.
                path.moveTo(cx + w * 0.18f, bottom)
                path.lineTo(cx + w * 0.18f, h * 0.3f)
                path.quadTo(cx + w * 0.18f, h * 0.08f, cx - w * 0.12f, h * 0.08f)
                path.quadTo(cx - w * 0.36f, h * 0.08f, cx - w * 0.36f, h * 0.36f)
                head = (cx - w * 0.36f) to h * 0.62f
                dir = 180.0
            }
            else -> {
                val a = Math.toRadians(angle.toDouble())
                val bend = h * 0.5f
                path.moveTo(cx, bottom)
                path.lineTo(cx, bend)
                val len = h * 0.22f
                val ex = cx + (sin(a) * len).toFloat() * 0.8f
                val ey = bend - (cos(a) * len).toFloat()
                path.quadTo(cx, bend - len * 0.5f, ex, ey)
                head = (ex + (sin(a) * h * 0.14f).toFloat() * 0.8f) to (ey - (cos(a) * h * 0.14f).toFloat())
                dir = angle.toDouble()
            }
        }
        c.drawPath(path, paint)
        // Hrot v smere [dir] so špičkou v bode [head].
        val r = Math.toRadians(dir)
        val (hx, hy) = head
        val back = h * 0.2f
        val side = w * 0.4f
        val bx = hx - (sin(r) * back).toFloat()
        val by = hy + (cos(r) * back).toFloat()
        val tri = Path()
        tri.moveTo(hx, hy)
        tri.lineTo(bx + (cos(r) * side).toFloat(), by + (sin(r) * side).toFloat())
        tri.lineTo(bx - (cos(r) * side).toFloat(), by - (sin(r) * side).toFloat())
        tri.close()
        c.drawPath(tri, fill)
    }

    /** Červené X na pruhu, ktorým netreba ísť. */
    fun cross(c: Canvas, w: Float, h: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(0xD0, 0xE5, 0x33, 0x3A)
            style = Paint.Style.STROKE
            strokeWidth = w * 0.2f
            strokeCap = Paint.Cap.ROUND
        }
        val m = w * 0.18f
        c.drawLine(m, m, w - m, h - m, p)
        c.drawLine(w - m, m, m, h - m, p)
    }
}
