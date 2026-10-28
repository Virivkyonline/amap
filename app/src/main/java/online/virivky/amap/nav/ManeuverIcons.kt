package online.virivky.amap.nav

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

/** Kreslí ikonky šípok pre manévre (pre auto aj telefón). */
object ManeuverIcons {
    private val cache = HashMap<String, Bitmap>()

    private fun angle(modifier: String?): Float = when (modifier) {
        "sharp right" -> 135f
        "right" -> 90f
        "slight right" -> 45f
        "sharp left" -> -135f
        "left" -> -90f
        "slight left" -> -45f
        else -> 0f
    }

    fun bitmap(step: RouteStep, size: Int = 128, color: Int = Color.WHITE): Bitmap {
        val kind = when (step.type) {
            "arrive" -> "arrive"
            "roundabout", "rotary", "roundabout turn" -> "round"
            else -> if (step.modifier == "uturn") "uturn" else "turn"
        }
        val key = "$kind|${step.modifier}|$size|$color"
        return cache.getOrPut(key) { draw(kind, angle(step.modifier), size, color) }
    }

    private fun draw(kind: String, angle: Float, size: Int, color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val s = size.toFloat()
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = s * 0.11f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

        when (kind) {
            "arrive" -> {
                val path = Path()
                val r = s * 0.22f
                path.addCircle(s / 2, s * 0.38f, r, Path.Direction.CW)
                path.moveTo(s / 2 - r * 0.8f, s * 0.38f + r * 0.6f)
                path.lineTo(s / 2, s * 0.9f)
                path.lineTo(s / 2 + r * 0.8f, s * 0.38f + r * 0.6f)
                path.close()
                canvas.drawPath(path, fill)
                fill.color = Color.TRANSPARENT
                fill.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
                canvas.drawCircle(s / 2, s * 0.38f, r * 0.42f, fill)
            }
            "uturn" -> {
                val path = Path()
                path.moveTo(s * 0.66f, s * 0.9f)
                path.lineTo(s * 0.66f, s * 0.4f)
                path.arcTo(RectF(s * 0.34f, s * 0.24f, s * 0.66f, s * 0.56f), 0f, -180f)
                path.lineTo(s * 0.34f, s * 0.66f)
                canvas.drawPath(path, stroke)
                arrowHead(canvas, fill, s * 0.34f, s * 0.72f, 180f, s)
            }
            "round" -> {
                val cx = s / 2
                val cy = s * 0.46f
                val r = s * 0.17f
                canvas.drawLine(cx, s * 0.92f, cx, cy + r, stroke)
                canvas.drawCircle(cx, cy, r, stroke)
                val exit = if (angle == 0f) 0f else angle
                val rad = Math.toRadians(exit.toDouble())
                val sx = cx + (r * sin(rad)).toFloat()
                val sy = cy - (r * cos(rad)).toFloat()
                val ex = cx + (s * 0.3f * sin(rad)).toFloat()
                val ey = cy - (s * 0.3f * cos(rad)).toFloat()
                canvas.drawLine(sx, sy, ex, ey, stroke)
                arrowHead(canvas, fill, ex, ey, exit, s)
            }
            else -> {
                val bx = s / 2
                val by = s * 0.92f
                val jx = s / 2
                val jy = if (angle == 0f) s * 0.3f else s * 0.55f
                val rad = Math.toRadians(angle.toDouble())
                val len = s * 0.3f
                val ex = jx + (len * sin(rad)).toFloat()
                val ey = jy - (len * cos(rad)).toFloat()
                val path = Path()
                path.moveTo(bx, by)
                path.lineTo(jx, jy)
                if (angle != 0f) path.lineTo(ex, ey)
                canvas.drawPath(path, stroke)
                if (angle == 0f) arrowHead(canvas, fill, jx, jy, 0f, s) else arrowHead(canvas, fill, ex, ey, angle, s)
            }
        }
        return bitmap
    }

    /** Hrot šípky na súradniciach (x, y) smerujúci pod uhlom [angle] (0 = hore). */
    private fun arrowHead(canvas: Canvas, paint: Paint, x: Float, y: Float, angle: Float, s: Float) {
        canvas.save()
        canvas.rotate(angle, x, y)
        val w = s * 0.17f
        val h = s * 0.2f
        val path = Path()
        path.moveTo(x, y - h * 0.75f)
        path.lineTo(x + w, y + h * 0.35f)
        path.lineTo(x - w, y + h * 0.35f)
        path.close()
        canvas.drawPath(path, paint)
        canvas.restore()
    }
}
