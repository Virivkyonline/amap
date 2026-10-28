package online.virivky.amap.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import online.virivky.amap.data.VehicleModel

/** Ikona polohy zhora (smeruje hore): sedan, SUV, športové auto, motorka alebo šípka v zvolenej farbe. */
object Vehicles {
    private const val GLASS_TOP = 0xFF3A4A63.toInt()
    private const val GLASS = 0xFF151B26.toInt()
    private const val TYRE = 0xFF15181E.toInt()
    private const val TAIL = 0xFFE5333A.toInt()
    private const val LAMP = 0xFFFFF6D8.toInt()

    /** Nakreslí vozidlo so stredom v strede plátna; [beam] = svetlo pred vozidlom. */
    fun draw(c: Canvas, model: VehicleModel, color: Int, w: Float, h: Float, beam: Boolean = true) {
        val cx = w / 2
        val cy = h / 2
        if (beam && model != VehicleModel.ARROW) beam(c, cx, cy, if (model == VehicleModel.MOTO) 0.6f else 1f)
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx, cy + 2f, 24f, Color.argb(0x66, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        c.drawCircle(cx, cy + 2f, 24f, shadow)
        when (model) {
            VehicleModel.SEDAN -> sedan(c, cx, cy, color)
            VehicleModel.SUV -> suv(c, cx, cy, color)
            VehicleModel.SPORT -> sport(c, cx, cy, color)
            VehicleModel.MOTO -> moto(c, cx, cy, color)
            VehicleModel.ARROW -> arrow(c, cx, cy, color)
        }
    }

    /** Svetelný kužeľ pred vozidlom. */
    private fun beam(c: Canvas, cx: Float, cy: Float, k: Float) {
        val path = Path()
        path.moveTo(cx - 9f * k, cy - 14f)
        path.lineTo(cx - 20f * k, 2f)
        path.lineTo(cx + 20f * k, 2f)
        path.lineTo(cx + 9f * k, cy - 14f)
        path.close()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, cy - 14f, 0f, 2f, Color.argb(0xAA, 0xFF, 0xFF, 0xFF), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        c.drawPath(path, paint)
    }

    private fun sedan(c: Canvas, cx: Float, cy: Float, color: Int) {
        val body = RectF(cx - 11f, cy - 21f, cx + 11f, cy + 21f)
        mirrors(c, cx, cy - 5f, 15f, color)
        c.drawRoundRect(body, 9f, 9f, body(color, body))
        c.drawRoundRect(body, 9f, 9f, outline(color))
        // Strecha trochu svetlejšia, predné a zadné sklo.
        c.drawRoundRect(RectF(cx - 8f, cy - 2f, cx + 8f, cy + 10f), 3f, 3f, fill(mix(color, Color.WHITE, 0.12f)))
        val windshield = Path()
        windshield.moveTo(cx - 9f, cy - 8f)
        windshield.quadTo(cx, cy - 13f, cx + 9f, cy - 8f)
        windshield.lineTo(cx + 7.5f, cy - 1f)
        windshield.lineTo(cx - 7.5f, cy - 1f)
        windshield.close()
        c.drawPath(windshield, glass(cy - 12f, cy - 1f))
        c.drawRoundRect(RectF(cx - 7f, cy + 9f, cx + 7f, cy + 14f), 2f, 2f, glass(cy + 9f, cy + 14f))
        lamps(c, cx, cy - 20.2f, 7.5f, 2.2f)
        tails(c, cx, cy + 18f, 4f, 9f, 2.5f)
    }

    private fun suv(c: Canvas, cx: Float, cy: Float, color: Int) {
        val body = RectF(cx - 12f, cy - 22f, cx + 12f, cy + 22f)
        mirrors(c, cx, cy - 7f, 16.5f, color)
        c.drawRoundRect(body, 5f, 5f, body(color, body))
        c.drawRoundRect(body, 5f, 5f, outline(color))
        // Kapota s dvoma prelismi.
        val crease = stroke(mix(color, Color.BLACK, 0.18f), 0.8f)
        c.drawLine(cx - 5f, cy - 20.5f, cx - 6f, cy - 11f, crease)
        c.drawLine(cx + 5f, cy - 20.5f, cx + 6f, cy - 11f, crease)
        val windshield = Path()
        windshield.moveTo(cx - 10f, cy - 9.5f)
        windshield.quadTo(cx, cy - 12.5f, cx + 10f, cy - 9.5f)
        windshield.lineTo(cx + 9f, cy - 3f)
        windshield.lineTo(cx - 9f, cy - 3f)
        windshield.close()
        c.drawPath(windshield, glass(cy - 12f, cy - 3f))
        // Strecha s panoramatickým oknom a strešnými lištami.
        c.drawRoundRect(RectF(cx - 9f, cy - 3f, cx + 9f, cy + 15f), 2f, 2f, fill(mix(color, Color.WHITE, 0.1f)))
        c.drawRoundRect(RectF(cx - 5.5f, cy - 0.5f, cx + 5.5f, cy + 9f), 2f, 2f, glass(cy, cy + 9f))
        val rail = stroke(if (luminance(color) < 0.35f) 0xFFB9C0C9.toInt() else mix(color, Color.BLACK, 0.45f), 1.3f)
        rail.strokeCap = Paint.Cap.ROUND
        c.drawLine(cx - 7.6f, cy - 1.5f, cx - 7.6f, cy + 13.5f, rail)
        c.drawLine(cx + 7.6f, cy - 1.5f, cx + 7.6f, cy + 13.5f, rail)
        c.drawRoundRect(RectF(cx - 9f, cy + 15.5f, cx + 9f, cy + 19.5f), 1.5f, 1.5f, glass(cy + 15.5f, cy + 19.5f))
        // Úzke LED svetlá cez celý predok a zadok.
        val led = stroke(LAMP, 1.4f)
        led.strokeCap = Paint.Cap.ROUND
        c.drawLine(cx - 10.5f, cy - 20.8f, cx - 4.5f, cy - 21.2f, led)
        c.drawLine(cx + 4.5f, cy - 21.2f, cx + 10.5f, cy - 20.8f, led)
        val tail = stroke(TAIL, 1.4f)
        tail.strokeCap = Paint.Cap.ROUND
        c.drawLine(cx - 10.5f, cy + 21f, cx + 10.5f, cy + 21f, tail)
    }

    private fun sport(c: Canvas, cx: Float, cy: Float, color: Int) {
        // Nízke auto so širokými blatníkmi a úzkym pásom v strede.
        val shape = Path()
        shape.moveTo(cx, cy - 22.5f)
        shape.cubicTo(cx + 6f, cy - 22.5f, cx + 11.5f, cy - 20f, cx + 12.5f, cy - 13f)
        shape.cubicTo(cx + 13f, cy - 8f, cx + 10.5f, cy - 5f, cx + 10.5f, cy - 1f)
        shape.cubicTo(cx + 10.5f, cy + 4f, cx + 13.5f, cy + 6f, cx + 13.5f, cy + 12f)
        shape.cubicTo(cx + 13.5f, cy + 18f, cx + 11f, cy + 21.5f, cx, cy + 21.5f)
        shape.cubicTo(cx - 11f, cy + 21.5f, cx - 13.5f, cy + 18f, cx - 13.5f, cy + 12f)
        shape.cubicTo(cx - 13.5f, cy + 6f, cx - 10.5f, cy + 4f, cx - 10.5f, cy - 1f)
        shape.cubicTo(cx - 10.5f, cy - 5f, cx - 13f, cy - 8f, cx - 12.5f, cy - 13f)
        shape.cubicTo(cx - 11.5f, cy - 20f, cx - 6f, cy - 22.5f, cx, cy - 22.5f)
        shape.close()
        mirrors(c, cx, cy - 4f, 14f, color, 3.5f)
        c.drawPath(shape, body(color, RectF(cx - 13.5f, 0f, cx + 13.5f, 0f)))
        c.drawPath(shape, outline(color))
        // Pretekárske pruhy po celej dĺžke.
        val stripe = fill(if (luminance(color) > 0.7f) 0xFF1D2433.toInt() else Color.argb(0xE6, 0xFF, 0xFF, 0xFF))
        c.drawRect(cx - 3.4f, cy - 20.5f, cx - 1.3f, cy + 21.3f, stripe)
        c.drawRect(cx + 1.3f, cy - 20.5f, cx + 3.4f, cy + 21.3f, stripe)
        // Kabína posunutá dozadu.
        val windshield = Path()
        windshield.moveTo(cx - 8.5f, cy - 3f)
        windshield.quadTo(cx, cy - 7.5f, cx + 8.5f, cy - 3f)
        windshield.lineTo(cx + 7f, cy + 3.5f)
        windshield.lineTo(cx - 7f, cy + 3.5f)
        windshield.close()
        c.drawPath(windshield, glass(cy - 7f, cy + 3.5f))
        val rear = Path()
        rear.moveTo(cx - 6.5f, cy + 9f)
        rear.lineTo(cx + 6.5f, cy + 9f)
        rear.lineTo(cx + 5f, cy + 13f)
        rear.lineTo(cx - 5f, cy + 13f)
        rear.close()
        c.drawPath(rear, glass(cy + 9f, cy + 13f))
        // Šikmé svetlá vpredu, spojler a zadné svetlá.
        val lamp = stroke(LAMP, 1.5f)
        lamp.strokeCap = Paint.Cap.ROUND
        c.drawLine(cx - 10.8f, cy - 15f, cx - 6.5f, cy - 19.8f, lamp)
        c.drawLine(cx + 10.8f, cy - 15f, cx + 6.5f, cy - 19.8f, lamp)
        c.drawRoundRect(RectF(cx - 12.5f, cy + 17.2f, cx + 12.5f, cy + 19.4f), 1f, 1f, fill(0xFF20242C.toInt()))
        tails(c, cx, cy + 20f, 4f, 9.5f, 1.4f)
    }

    private fun moto(c: Canvas, cx: Float, cy: Float, color: Int) {
        val dark = fill(TYRE)
        // Kolesá, výfuk a zadná časť so sedadlom.
        c.drawRoundRect(RectF(cx - 2.8f, cy + 10f, cx + 2.8f, cy + 24f), 2.5f, 2.5f, dark)
        c.drawRoundRect(RectF(cx + 3f, cy + 7f, cx + 5.8f, cy + 19f), 1.4f, 1.4f, fill(0xFFB9C0C9.toInt()))
        c.drawRoundRect(RectF(cx - 2.2f, cy - 24f, cx + 2.2f, cy - 13f), 2f, 2f, dark)
        val tail = RectF(cx - 3.6f, cy + 3f, cx + 3.6f, cy + 17.5f)
        c.drawRoundRect(tail, 3f, 3f, body(color, tail))
        c.drawRoundRect(RectF(cx - 2.6f, cy + 4f, cx + 2.6f, cy + 12f), 2f, 2f, fill(0xFF23272F.toInt()))
        c.drawRoundRect(RectF(cx - 1.8f, cy + 16f, cx + 1.8f, cy + 17.6f), 0.8f, 0.8f, fill(TAIL))
        // Kapotáž s plexi a svetlom.
        val fairing = Path()
        fairing.moveTo(cx, cy - 21f)
        fairing.cubicTo(cx + 5f, cy - 21f, cx + 6.8f, cy - 14f, cx + 6.2f, cy - 9.5f)
        fairing.lineTo(cx - 6.2f, cy - 9.5f)
        fairing.cubicTo(cx - 6.8f, cy - 14f, cx - 5f, cy - 21f, cx, cy - 21f)
        fairing.close()
        c.drawPath(fairing, body(color, RectF(cx - 6.8f, 0f, cx + 6.8f, 0f)))
        c.drawPath(fairing, outline(color))
        c.drawOval(RectF(cx - 3.6f, cy - 17f, cx + 3.6f, cy - 11.5f), glass(cy - 17f, cy - 11.5f))
        c.drawOval(RectF(cx - 1.6f, cy - 21f, cx + 1.6f, cy - 19f), fill(LAMP))
        // Nádrž, riadidlá so zrkadlami.
        val tank = RectF(cx - 5f, cy - 10f, cx + 5f, cy + 3f)
        c.drawOval(tank, body(color, tank))
        c.drawOval(tank, outline(color))
        val bar = stroke(TYRE, 1.6f)
        bar.strokeCap = Paint.Cap.ROUND
        c.drawLine(cx - 9.5f, cy - 9.5f, cx + 9.5f, cy - 9.5f, bar)
        c.drawCircle(cx - 9.6f, cy - 11.6f, 1.6f, fill(mix(color, Color.BLACK, 0.2f)))
        c.drawCircle(cx + 9.6f, cy - 11.6f, 1.6f, fill(mix(color, Color.BLACK, 0.2f)))
        // Jazdec: ramená, ruky na riadidlách a prilba vo farbe motorky.
        val jacket = 0xFF464D5B.toInt()
        val arm = stroke(jacket, 2.6f)
        arm.strokeCap = Paint.Cap.ROUND
        c.drawLine(cx - 6f, cy - 1.5f, cx - 8.6f, cy - 8.8f, arm)
        c.drawLine(cx + 6f, cy - 1.5f, cx + 8.6f, cy - 8.8f, arm)
        c.drawOval(RectF(cx - 7.5f, cy - 4f, cx + 7.5f, cy + 6f), fill(jacket))
        c.drawOval(RectF(cx - 7.5f, cy - 4f, cx + 7.5f, cy + 6f), stroke(0xFF737B8C.toInt(), 0.7f))
        val helmet = RectF(cx - 4.2f, cy - 6.4f, cx + 4.2f, cy + 2f)
        c.drawOval(helmet, body(color, helmet))
        c.drawOval(helmet, outline(color))
        val visor = stroke(GLASS, 1.6f)
        visor.strokeCap = Paint.Cap.ROUND
        val arc = Path()
        arc.moveTo(cx - 3f, cy - 4.6f)
        arc.quadTo(cx, cy - 6.6f, cx + 3f, cy - 4.6f)
        c.drawPath(arc, visor)
    }

    /** Navigačná šípka: ľavá polovica svetlejšia, pravá tmavšia (plastický vzhľad). */
    private fun arrow(c: Canvas, cx: Float, cy: Float, color: Int) {
        val tip = cy - 20f
        val notch = cy + 8f
        val left = Path()
        left.moveTo(cx, tip)
        left.lineTo(cx - 14f, cy + 16f)
        left.lineTo(cx, notch)
        left.close()
        val right = Path()
        right.moveTo(cx, tip)
        right.lineTo(cx + 14f, cy + 16f)
        right.lineTo(cx, notch)
        right.close()
        c.drawPath(left, fill(mix(color, Color.WHITE, 0.18f)))
        c.drawPath(right, fill(mix(color, Color.BLACK, 0.18f)))
        val whole = Path()
        whole.moveTo(cx, tip)
        whole.lineTo(cx + 14f, cy + 16f)
        whole.lineTo(cx, notch)
        whole.lineTo(cx - 14f, cy + 16f)
        whole.close()
        val border = stroke(if (luminance(color) > 0.75f) 0xFF1F6FFF.toInt() else Color.WHITE, 2.2f)
        border.strokeJoin = Paint.Join.ROUND
        c.drawPath(whole, border)
    }

    private fun mirrors(c: Canvas, cx: Float, y: Float, reach: Float, color: Int, size: Float = 5f) {
        val p = fill(mix(color, Color.BLACK, 0.12f))
        c.drawOval(RectF(cx - reach, y - 2f, cx - reach + size, y + 2f), p)
        c.drawOval(RectF(cx + reach - size, y - 2f, cx + reach, y + 2f), p)
    }

    private fun lamps(c: Canvas, cx: Float, y: Float, dx: Float, size: Float) {
        val p = fill(LAMP)
        c.drawOval(RectF(cx - dx - size, y - size / 2, cx - dx + size, y + size / 2), p)
        c.drawOval(RectF(cx + dx - size, y - size / 2, cx + dx + size, y + size / 2), p)
    }

    private fun tails(c: Canvas, cx: Float, y: Float, inner: Float, outer: Float, height: Float) {
        val p = fill(TAIL)
        c.drawRoundRect(RectF(cx - outer, y, cx - inner, y + height), 1f, 1f, p)
        c.drawRoundRect(RectF(cx + inner, y, cx + outer, y + height), 1f, 1f, p)
    }

    /** Karoséria: po stranách tmavšia, v strede lesk. */
    private fun body(color: Int, r: RectF) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val edge = mix(color, Color.BLACK, 0.16f)
        shader = LinearGradient(
            r.left, 0f, r.right, 0f,
            intArrayOf(edge, mix(color, Color.WHITE, 0.3f), color, edge),
            floatArrayOf(0f, 0.4f, 0.65f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    /** Obrys, aby bolo vozidlo vidieť na tmavej aj svetlej mape. */
    private fun outline(color: Int) =
        stroke(if (luminance(color) < 0.25f) mix(color, Color.WHITE, 0.4f) else mix(color, Color.BLACK, 0.35f), 0.8f)

    private fun glass(top: Float, bottom: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, top, 0f, bottom, GLASS_TOP, GLASS, Shader.TileMode.CLAMP)
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt()
        return Color.argb(0xFF, ch(16), ch(8), ch(0))
    }

    private fun luminance(color: Int): Float =
        (0.2126f * ((color shr 16) and 0xFF) + 0.7152f * ((color shr 8) and 0xFF) + 0.0722f * (color and 0xFF)) / 255f
}
