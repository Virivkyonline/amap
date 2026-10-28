package online.virivky.amap.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import online.virivky.amap.data.PoiCategory

/** Ikony služieb: biely symbol vo farebnom kruhu (mapa, zoznamy, Android Auto). */
object PoiIcons {
    private val cache = HashMap<String, Bitmap>()

    /** Okrúhla ikona kategórie s veľkosťou [size] px. */
    fun badge(category: PoiCategory, size: Int): Bitmap = cache.getOrPut("badge-${category.key}-$size") {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        val r = size / 2f
        c.drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = category.color })
        glyph(c, category, r, r, size * 0.56f, category.color)
        bitmap
    }

    /** Značka na mapu (v dp): kruh s bielym okrajom a malým hrotom dole. */
    fun marker(c: Canvas, category: PoiCategory, w: Float, h: Float) {
        val cx = w / 2
        val r = w / 2 - 1.5f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val tip = Path()
        tip.moveTo(cx - r * 0.45f, r * 1.7f)
        tip.lineTo(cx, h - 0.5f)
        tip.lineTo(cx + r * 0.45f, r * 1.7f)
        tip.close()
        c.drawPath(tip, fill)
        c.drawCircle(cx, r + 1.5f, r + 1.5f, fill)
        fill.color = category.color
        c.drawCircle(cx, r + 1.5f, r - 0.5f, fill)
        glyph(c, category, cx, r + 1.5f, r * 1.15f, category.color)
    }

    /** Biely symbol kategórie so stredom [cx], [cy] vo štvorci veľkosti [size]. */
    fun glyph(c: Canvas, category: PoiCategory, cx: Float, cy: Float, size: Float, background: Int) {
        c.save()
        c.translate(cx, cy)
        c.scale(size / 2, size / 2)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 0.14f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        when (category) {
            PoiCategory.EV -> {
                // Blesk.
                val p = Path()
                p.moveTo(0.18f, -0.95f)
                p.lineTo(-0.55f, 0.12f)
                p.lineTo(-0.04f, 0.12f)
                p.lineTo(-0.2f, 0.95f)
                p.lineTo(0.55f, -0.16f)
                p.lineTo(0.04f, -0.16f)
                p.close()
                c.drawPath(p, fill)
            }
            PoiCategory.FUEL -> {
                // Stojan s hadicou.
                c.drawRoundRect(RectF(-0.7f, -0.8f, 0.2f, 0.85f), 0.12f, 0.12f, fill)
                fill.color = background
                c.drawRect(-0.54f, -0.62f, 0.04f, -0.2f, fill)
                val hose = Path()
                hose.moveTo(0.2f, -0.05f)
                hose.lineTo(0.45f, -0.05f)
                hose.lineTo(0.45f, 0.5f)
                hose.quadTo(0.45f, 0.66f, 0.6f, 0.66f)
                hose.quadTo(0.75f, 0.66f, 0.75f, 0.5f)
                hose.lineTo(0.75f, -0.42f)
                hose.lineTo(0.52f, -0.7f)
                c.drawPath(hose, stroke)
            }
            PoiCategory.PARKING -> text(c, "P", 1.75f)
            PoiCategory.REST -> {
                // Strom.
                val p = Path()
                p.moveTo(0f, -0.95f)
                p.lineTo(-0.5f, -0.15f)
                p.lineTo(0.5f, -0.15f)
                p.close()
                p.moveTo(0f, -0.5f)
                p.lineTo(-0.68f, 0.45f)
                p.lineTo(0.68f, 0.45f)
                p.close()
                c.drawPath(p, fill)
                c.drawRect(-0.13f, 0.4f, 0.13f, 0.9f, fill)
            }
            PoiCategory.FOOD -> {
                // Vidlička a nôž.
                stroke.strokeWidth = 0.11f
                for (x in floatArrayOf(-0.68f, -0.46f, -0.24f)) c.drawLine(x, -0.85f, x, -0.32f, stroke)
                c.drawRoundRect(RectF(-0.74f, -0.4f, -0.18f, -0.12f), 0.14f, 0.14f, fill)
                stroke.strokeWidth = 0.17f
                c.drawLine(-0.46f, -0.2f, -0.46f, 0.85f, stroke)
                val blade = Path()
                blade.moveTo(0.3f, -0.88f)
                blade.quadTo(0.78f, -0.55f, 0.7f, 0.1f)
                blade.lineTo(0.3f, 0.1f)
                blade.close()
                c.drawPath(blade, fill)
                c.drawLine(0.42f, 0.05f, 0.42f, 0.85f, stroke)
            }
            PoiCategory.CAFE -> {
                // Šálka s parou.
                val cup = Path()
                cup.moveTo(-0.7f, -0.25f)
                cup.lineTo(0.38f, -0.25f)
                cup.lineTo(0.3f, 0.3f)
                cup.quadTo(0.24f, 0.62f, -0.16f, 0.62f)
                cup.quadTo(-0.56f, 0.62f, -0.62f, 0.3f)
                cup.close()
                c.drawPath(cup, fill)
                c.drawCircle(0.48f, 0.08f, 0.2f, stroke)
                c.drawLine(-0.8f, 0.82f, 0.5f, 0.82f, stroke)
                stroke.strokeWidth = 0.1f
                c.drawLine(-0.38f, -0.88f, -0.38f, -0.5f, stroke)
                c.drawLine(0.05f, -0.88f, 0.05f, -0.5f, stroke)
            }
            PoiCategory.SHOP -> {
                // Nákupný vozík.
                val basket = Path()
                basket.moveTo(-0.58f, -0.45f)
                basket.lineTo(0.82f, -0.45f)
                basket.lineTo(0.6f, 0.22f)
                basket.lineTo(-0.4f, 0.22f)
                basket.close()
                c.drawPath(basket, fill)
                val handle = Path()
                handle.moveTo(-0.92f, -0.72f)
                handle.lineTo(-0.68f, -0.72f)
                handle.lineTo(-0.42f, 0.42f)
                handle.lineTo(0.62f, 0.42f)
                c.drawPath(handle, stroke)
                c.drawCircle(-0.3f, 0.72f, 0.15f, fill)
                c.drawCircle(0.5f, 0.72f, 0.15f, fill)
            }
            PoiCategory.HOTEL -> {
                // Posteľ.
                c.drawRect(-0.9f, -0.6f, -0.68f, 0.72f, fill)
                c.drawRect(-0.9f, 0.05f, 0.9f, 0.38f, fill)
                c.drawRect(0.68f, 0.05f, 0.9f, 0.72f, fill)
                c.drawCircle(-0.38f, -0.2f, 0.2f, fill)
                c.drawRoundRect(RectF(-0.1f, -0.42f, 0.9f, 0.05f), 0.14f, 0.14f, fill)
            }
            PoiCategory.PHARMACY -> {
                // Kríž.
                c.drawRoundRect(RectF(-0.22f, -0.78f, 0.22f, 0.78f), 0.06f, 0.06f, fill)
                c.drawRoundRect(RectF(-0.78f, -0.22f, 0.78f, 0.22f), 0.06f, 0.06f, fill)
            }
            PoiCategory.ATM -> {
                // Bankovka.
                c.drawRoundRect(RectF(-0.86f, -0.52f, 0.86f, 0.52f), 0.12f, 0.12f, stroke)
                c.drawCircle(0f, 0f, 0.24f, fill)
                c.drawCircle(-0.56f, 0f, 0.07f, fill)
                c.drawCircle(0.56f, 0f, 0.07f, fill)
            }
        }
        c.restore()
    }

    private fun text(c: Canvas, text: String, size: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val bounds = android.graphics.Rect()
        // Rozmery textu pri malom písme sú nepresné, preto merame pri väčšom a prepočítame.
        p.textSize = 100f
        p.getTextBounds(text, 0, text.length, bounds)
        p.textSize = size
        val k = size / 100f
        c.drawText(text, 0f, -(bounds.top + bounds.bottom) / 2f * k, p)
    }
}
