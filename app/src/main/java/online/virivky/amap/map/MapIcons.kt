package online.virivky.amap.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import online.virivky.amap.data.PoiCategory

/** Obrázky pre mapu (kreslené v kóde, veľkosti v dp). */
object MapIcons {
    private const val BLUE = 0xFF3F80FF.toInt()
    private const val ARROW_BLUE = 0xFF1F6FFF.toInt()

    fun all(context: Context): Map<String, Bitmap> {
        val d = context.resources.displayMetrics
        val density = d.density
        fun canvas(wDp: Float, hDp: Float, draw: Canvas.(Float, Float) -> Unit): Bitmap {
            val bitmap = Bitmap.createBitmap((wDp * density).toInt(), (hDp * density).toInt(), Bitmap.Config.ARGB_8888)
            bitmap.density = d.densityDpi
            val c = Canvas(bitmap)
            c.scale(density, density)
            c.draw(wDp, hDp)
            return bitmap
        }
        return mapOf(
            "chevron" to canvas(14f, 14f) { w, h -> chevron(this, w, h) },
            "arrowhead" to canvas(48f, 48f) { w, h -> arrowhead(this, w, h) },
            "car" to canvas(90f, 90f) { w, h -> car(this, w, h) },
            "dot" to canvas(28f, 28f) { w, h -> dot(this, w, h) },
            "compass" to canvas(130f, 130f) { w, h -> compass(this, w, h) },
            "pin" to canvas(34f, 44f) { w, h -> pin(this, w, h, 0xFFE5333A.toInt()) },
            "pin-via" to canvas(28f, 36f) { w, h -> pin(this, w, h, 0xFFF08A00.toInt()) },
            "camera" to canvas(26f, 26f) { w, h -> camera(this, w, h) },
            "signal" to canvas(16f, 34f) { w, h -> signal(this, w, h) },
        ) + PoiCategory.entries.associate { category ->
            category.icon to canvas(30f, 38f) { w, h -> PoiIcons.marker(this, category, w, h) }
        } + IncidentIcons.names.associateWith { name ->
            canvas(30f, 30f) { w, h -> IncidentIcons.draw(this, name, w / 2, h / 2, w - 4f) }
        }
    }

    /** Šípka „>“ – pri umiestnení na čiare ukazuje v smere trasy. */
    private fun chevron(c: Canvas, w: Float, h: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2.2f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path()
        path.moveTo(w * 0.35f, h * 0.2f)
        path.lineTo(w * 0.68f, h * 0.5f)
        path.lineTo(w * 0.35f, h * 0.8f)
        c.drawPath(path, p)
    }

    /** Hrot šípky manévru; základňa je v strede obrázka, hrot smeruje hore. */
    private fun arrowhead(c: Canvas, w: Float, h: Float) {
        val path = Path()
        path.moveTo(w / 2, h * 0.06f)
        path.lineTo(w * 0.96f, h / 2)
        path.lineTo(w * 0.04f, h / 2)
        path.close()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ARROW_BLUE
            style = Paint.Style.STROKE
            strokeWidth = 3f
            strokeJoin = Paint.Join.ROUND
        }
        c.drawPath(path, fill)
        c.drawPath(path, stroke)
    }

    /** Biele auto zhora so svetlom pred sebou (smeruje hore). */
    private fun car(c: Canvas, w: Float, h: Float) {
        val cx = w / 2
        val cy = h / 2
        // Svetelný kužeľ pred autom.
        val beam = Path()
        beam.moveTo(cx - 9f, cy - 14f)
        beam.lineTo(cx - 20f, 2f)
        beam.lineTo(cx + 20f, 2f)
        beam.lineTo(cx + 9f, cy - 14f)
        beam.close()
        val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, cy - 14f, 0f, 2f, Color.argb(0xAA, 0xFF, 0xFF, 0xFF), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        c.drawPath(beam, beamPaint)

        // Tieň a karoséria.
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx, cy + 2f, 24f, Color.argb(0x66, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        c.drawCircle(cx, cy + 2f, 24f, shadow)
        val body = RectF(cx - 11f, cy - 21f, cx + 11f, cy + 21f)
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(body.left, 0f, body.right, 0f,
                intArrayOf(0xFFD9DDE3.toInt(), Color.WHITE, 0xFFD9DDE3.toInt()), null, Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(body, 9f, 9f, bodyPaint)
        val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1D2433.toInt() }
        // Predné sklo, strecha, zadné sklo.
        val windshield = Path()
        windshield.moveTo(cx - 9f, cy - 8f)
        windshield.quadTo(cx, cy - 13f, cx + 9f, cy - 8f)
        windshield.lineTo(cx + 7.5f, cy - 1f)
        windshield.lineTo(cx - 7.5f, cy - 1f)
        windshield.close()
        c.drawPath(windshield, dark)
        c.drawRoundRect(RectF(cx - 7f, cy + 9f, cx + 7f, cy + 14f), 2f, 2f, dark)
        // Spätné zrkadlá a svetlá.
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE9ECF0.toInt() }
        c.drawOval(RectF(cx - 15f, cy - 7f, cx - 10f, cy - 3f), white)
        c.drawOval(RectF(cx + 10f, cy - 7f, cx + 15f, cy - 3f), white)
        val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE5333A.toInt() }
        c.drawRoundRect(RectF(cx - 9f, cy + 18f, cx - 4f, cy + 20.5f), 1f, 1f, red)
        c.drawRoundRect(RectF(cx + 4f, cy + 18f, cx + 9f, cy + 20.5f), 1f, 1f, red)
    }

    /** Modrá bodka pre polohu bez známeho smeru. */
    private fun dot(c: Canvas, w: Float, h: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.argb(0x40, 0x3F, 0x80, 0xFF)
        c.drawCircle(w / 2, h / 2, w / 2, p)
        p.color = Color.WHITE
        c.drawCircle(w / 2, h / 2, w * 0.3f, p)
        p.color = BLUE
        c.drawCircle(w / 2, h / 2, w * 0.22f, p)
    }

    /** Kruh kompasu okolo auta so svetovými stranami (otáča sa s mapou). */
    private fun compass(c: Canvas, w: Float, h: Float) {
        val cx = w / 2
        val cy = h / 2
        val r = w * 0.36f
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx, cy, r, Color.argb(0x00, 0x3F, 0x80, 0xFF), Color.argb(0x55, 0x3F, 0x80, 0xFF), Shader.TileMode.CLAMP)
        }
        c.drawCircle(cx, cy, r, glow)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(0xCC, 0x4C, 0x8D, 0xFF)
            style = Paint.Style.STROKE
            strokeWidth = 1.4f
        }
        c.drawCircle(cx, cy, r, ring)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val labels = listOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f)
        for ((label, angle) in labels) {
            c.save()
            c.rotate(angle, cx, cy)
            text.color = if (label == "N") 0xFFE5333A.toInt() else Color.WHITE
            text.setShadowLayer(2f, 0f, 0f, Color.BLACK)
            val y = cy - r + 4.5f
            c.save()
            c.rotate(-angle, cx, y)
            c.drawText(label, cx, y + 4.5f, text)
            c.restore()
            c.restore()
        }
    }

    /** Špendlík cieľa či zastávky (hrot dole v strede). */
    private fun pin(c: Canvas, w: Float, h: Float, pinColor: Int) {
        val cx = w / 2
        val r = w * 0.42f
        val path = Path()
        path.addCircle(cx, r + 1f, r, Path.Direction.CW)
        path.moveTo(cx - r * 0.7f, r + 1f + r * 0.7f)
        path.lineTo(cx, h - 1f)
        path.lineTo(cx + r * 0.7f, r + 1f + r * 0.7f)
        path.close()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pinColor }
        c.drawPath(path, fill)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        c.drawCircle(cx, r + 1f, r, stroke)
        fill.color = Color.WHITE
        c.drawCircle(cx, r + 1f, r * 0.38f, fill)
    }

    /** Semafor (červená, oranžová, zelená) na stĺpiku. */
    private fun signal(c: Canvas, w: Float, h: Float) {
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF15181E.toInt() }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFDDE1E7.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1.4f
        }
        val box = RectF(1f, 1f, w - 1f, h - 7f)
        c.drawRoundRect(box, w / 2, w / 2, body)
        c.drawRoundRect(box, w / 2, w / 2, border)
        c.drawRect(w / 2 - 1f, h - 7f, w / 2 + 1f, h, border.apply { style = Paint.Style.FILL })
        val lamp = Paint(Paint.ANTI_ALIAS_FLAG)
        val colors = intArrayOf(0xFFFF3B30.toInt(), 0xFFFFC400.toInt(), 0xFF2EE06F.toInt())
        val step = (box.height() - 4f) / 3f
        colors.forEachIndexed { i, color ->
            lamp.color = color
            c.drawCircle(w / 2, box.top + 2f + step * (i + 0.5f), w * 0.27f, lamp)
        }
    }

    /** Oranžová značka radaru. */
    private fun camera(c: Canvas, w: Float, h: Float) {
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF57C00.toInt() }
        c.drawRoundRect(RectF(1f, 1f, w - 1f, h - 1f), 6f, 6f, bg)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        c.drawRoundRect(RectF(1f, 1f, w - 1f, h - 1f), 6f, 6f, border)
        cameraGlyph(c, w / 2, h / 2, w * 0.3f, 0xFFF57C00.toInt())
    }

    /** Ikonka fotoaparátu (aj pre panel s upozornením). */
    fun cameraGlyph(c: Canvas, x: Float, y: Float, r: Float, background: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        c.drawRoundRect(RectF(x - r, y - r * 0.6f, x + r, y + r * 0.7f), r * 0.2f, r * 0.2f, p)
        c.drawRect(x - r * 0.35f, y - r * 0.85f, x + r * 0.35f, y - r * 0.5f, p)
        p.color = background
        c.drawCircle(x, y + r * 0.05f, r * 0.42f, p)
    }
}
