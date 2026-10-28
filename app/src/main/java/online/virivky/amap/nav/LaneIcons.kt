package online.virivky.amap.nav

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

/** Obrázok jazdných pruhov: vhodné pruhy biele, ostatné sivé. */
object LaneIcons {
    private val cache = HashMap<String, Bitmap>()
    private const val ACTIVE = Color.WHITE
    private val INACTIVE = Color.argb(0x70, 0xFF, 0xFF, 0xFF)

    private fun side(indication: String) = when {
        indication.contains("left") -> -1
        indication.contains("right") -> 1
        else -> 0
    }

    /** Šípka v pruhu, ktorá zodpovedá manévru (alebo null, ak pruh nie je vhodný). */
    fun recommended(lane: LaneInfo, modifier: String?): String? {
        if (!lane.valid) return null
        val indications = lane.indications.map { if (it == "none") "straight" else it }.ifEmpty { listOf("straight") }
        if (indications.size == 1) return indications[0]
        val target = modifier ?: "straight"
        return indications.firstOrNull { it == target }
            ?: indications.firstOrNull { side(it) == side(target) }
            ?: indications[0]
    }

    /** Má zmysel pruhy ukazovať? (aspoň dva a aspoň jeden nevhodný) */
    fun useful(lanes: List<LaneInfo>) = lanes.size >= 2 && lanes.any { !it.valid }

    fun bitmap(lanes: List<LaneInfo>, modifier: String?, laneWidth: Int = 72, height: Int = 96): Bitmap {
        val key = lanes.joinToString("|") { it.indications.joinToString(",") + ":" + it.valid } + "#$modifier#$laneWidth#$height"
        return cache.getOrPut(key) { draw(lanes, modifier, laneWidth, height) }
    }

    private fun draw(lanes: List<LaneInfo>, modifier: String?, laneWidth: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(laneWidth * lanes.size, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val h = height.toFloat()
        val w = laneWidth.toFloat()
        val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INACTIVE
            strokeWidth = w * 0.04f
            pathEffect = DashPathEffect(floatArrayOf(h * 0.12f, h * 0.1f), 0f)
        }
        for (i in 1 until lanes.size) {
            canvas.drawLine(i * w, h * 0.08f, i * w, h * 0.92f, divider)
        }
        lanes.forEachIndexed { i, lane ->
            val best = recommended(lane, modifier)
            val indications = lane.indications.map { if (it == "none") "straight" else it }.ifEmpty { listOf("straight") }
            // Nevhodné šípky najprv, vhodnú navrch.
            indications.sortedBy { it == best }.forEach { indication ->
                drawArrow(canvas, i * w + w / 2, h, w, indication, if (indication == best) ACTIVE else INACTIVE)
            }
        }
        return bitmap
    }

    private fun drawArrow(canvas: Canvas, cx: Float, h: Float, w: Float, indication: String, color: Int) {
        val s = minOf(w, h)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = s * 0.1f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val bottom = h * 0.88f
        val headSize = s * 0.8f
        if (indication == "uturn") {
            val path = Path()
            val r = s * 0.16f
            path.moveTo(cx + r, bottom)
            path.lineTo(cx + r, h * 0.42f)
            path.arcTo(RectF(cx - r, h * 0.42f - r, cx + r, h * 0.42f + r), 0f, -180f)
            path.lineTo(cx - r, h * 0.55f)
            canvas.drawPath(path, stroke)
            ManeuverIcons.arrowHead(canvas, fill, cx - r, h * 0.62f, 180f, headSize)
            return
        }
        val angle = ManeuverIcons.angle(indication)
        val joint = if (angle == 0f) h * 0.22f else h * 0.5f
        canvas.drawLine(cx, bottom, cx, joint, stroke)
        if (angle == 0f) {
            ManeuverIcons.arrowHead(canvas, fill, cx, joint, 0f, headSize)
            return
        }
        val rad = Math.toRadians(angle.toDouble())
        val len = s * 0.3f
        val ex = cx + (len * sin(rad)).toFloat()
        val ey = joint - (len * cos(rad)).toFloat()
        canvas.drawLine(cx, joint, ex, ey, stroke)
        ManeuverIcons.arrowHead(canvas, fill, ex, ey, angle, headSize)
    }
}
