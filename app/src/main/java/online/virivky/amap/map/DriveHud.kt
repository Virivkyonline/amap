package online.virivky.amap.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import online.virivky.amap.data.Settings
import online.virivky.amap.location.LocationTracker
import online.virivky.amap.nav.Instructions
import online.virivky.amap.nav.NavigationController
import online.virivky.amap.nav.NavigationController.Phase
import online.virivky.amap.nav.Route
import online.virivky.amap.net.Incident
import kotlin.math.roundToInt

/**
 * Prvky nad mapou počas jazdy: rýchlosť, limit, radar, priebeh trasy a názov cesty.
 * Kreslí sa v telefóne aj na obrazovke auta.
 */
class DriveHud @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    /** Voľná plocha, do ktorej sa prvky umiestnia (prázdna = celé zobrazenie). */
    val viewport = Rect()

    /** Text o zdroji mapy (v aute nie je tlačidlo s informáciami). */
    var showAttribution = false

    private val dp = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val bold = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val rect = RectF()

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        val area = if (viewport.isEmpty) Rect(0, 0, width, height) else viewport
        val nav = NavigationController
        val navigating = nav.phase == Phase.NAVIGATING
        val margin = 12 * dp

        var y = area.top + margin
        val x = area.left + margin
        val speed = if (navigating) nav.speedKmh else LocationTracker.last
            ?.takeIf { it.hasSpeed() && it.speed * 3.6f >= 5f }
            ?.let { (it.speed * 3.6f).roundToInt() }
        if (speed != null || navigating) {
            y += drawSpeed(canvas, x, y, speed, navigating && nav.overSpeed) + 10 * dp
        }
        nav.cameraDistance?.takeIf { navigating }?.let {
            drawCamera(canvas, x, y, it)
            y += 48 * dp
        }
        val incident = nav.incidentAhead
        val incidentDistance = nav.incidentDistance
        if (navigating && incident != null && incidentDistance != null) {
            drawIncident(canvas, x, y, incident, incidentDistance)
        }

        if (navigating) {
            drawProgress(canvas, area, nav.progressFraction, nav.route)
            val road = nav.currentRoad
            val limit = nav.speedLimit
            if (road != null || limit != null) drawRoadName(canvas, area, road, limit)
        }
        if (showAttribution) drawAttribution(canvas, area)
    }

    /** Tmavý kruh s aktuálnou rýchlosťou (vráti výšku). */
    private fun drawSpeed(canvas: Canvas, x: Float, y: Float, speed: Int?, over: Boolean): Float {
        val r = 38 * dp
        val cx = x + r
        val cy = y + r
        fill.color = Color.argb(0xF0, 0x10, 0x17, 0x27)
        canvas.drawCircle(cx, cy, r, fill)
        if (over) {
            stroke.color = Color.rgb(0xE5, 0x33, 0x3A)
            stroke.strokeWidth = 3 * dp
            canvas.drawCircle(cx, cy, r - 1.5f * dp, stroke)
        }
        text.typeface = bold
        text.textSize = 34 * dp
        text.color = if (over) Color.rgb(0xFF, 0x5A, 0x5F) else Color.WHITE
        canvas.drawText(speed?.toString() ?: "–", cx, cy + 8 * dp, text)
        text.typeface = regular
        text.textSize = 12 * dp
        text.color = Color.argb(0xDD, 0xFF, 0xFF, 0xFF)
        canvas.drawText("km/h", cx, cy + 25 * dp, text)
        return 2 * r
    }

    /** Dopravná značka s rýchlostným limitom so stredom v (cx, cy). */
    private fun drawLimit(canvas: Canvas, cx: Float, cy: Float, r: Float, limit: Int) {
        fill.color = Color.WHITE
        canvas.drawCircle(cx, cy, r, fill)
        stroke.color = Color.rgb(0xE5, 0x33, 0x3A)
        stroke.strokeWidth = r * 0.2f
        canvas.drawCircle(cx, cy, r * 0.88f, stroke)
        text.typeface = bold
        text.textSize = if (limit >= 100) r * 0.78f else r * 0.95f
        text.color = Color.BLACK
        canvas.drawText(limit.toString(), cx, cy + text.textSize * 0.36f, text)
    }

    /** Oranžové upozornenie na radar so vzdialenosťou. */
    private fun drawCamera(canvas: Canvas, x: Float, y: Float, distance: Double) {
        val label = Instructions.displayDistance(distance)
        text.typeface = bold
        text.textSize = 17 * dp
        val w = 40 * dp + text.measureText(label)
        val h = 40 * dp
        rect.set(x, y, x + w, y + h)
        fill.color = Color.rgb(0xF5, 0x7C, 0x00)
        canvas.drawRoundRect(rect, h / 2, h / 2, fill)
        MapIcons.cameraGlyph(canvas, x + 20 * dp, y + h / 2, 9 * dp, Color.rgb(0xF5, 0x7C, 0x00))
        text.color = Color.WHITE
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(label, x + 34 * dp, y + h / 2 + 6 * dp, text)
        text.textAlign = Paint.Align.CENTER
    }

    /** Upozornenie na dopravnú udalosť (značka, druh a vzdialenosť). */
    private fun drawIncident(canvas: Canvas, x: Float, y: Float, incident: Incident, distance: Double) {
        val label = "${incident.label} · ${Instructions.displayDistance(distance)}"
        text.typeface = bold
        text.textSize = 17 * dp
        val h = 40 * dp
        val w = 46 * dp + text.measureText(label)
        rect.set(x, y, x + w, y + h)
        fill.color = Color.argb(0xF2, 0x10, 0x17, 0x27)
        canvas.drawRoundRect(rect, h / 2, h / 2, fill)
        IncidentIcons.draw(canvas, incident.icon, x + 22 * dp, y + h / 2, 26 * dp)
        text.color = Color.WHITE
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(label, x + 40 * dp, y + h / 2 + 6 * dp, text)
        text.textAlign = Paint.Align.CENTER
    }

    /** Zvislý pruh priebehu trasy vpravo (dole štart, hore cieľ), zafarbený podľa premávky. */
    private fun drawProgress(canvas: Canvas, area: Rect, fraction: Float, route: Route?) {
        val x = area.right - 14 * dp
        val top = area.top + 24 * dp
        val bottom = area.bottom - 70 * dp
        if (bottom - top < 80 * dp) return
        val w = 6 * dp
        val at = bottom - (bottom - top) * fraction
        rect.set(x - w / 2, top, x + w / 2, bottom)
        fill.color = Color.rgb(0x22, 0xD3, 0x9A)
        canvas.drawRoundRect(rect, w / 2, w / 2, fill)
        if (route != null && route.distance > 0) {
            for (span in route.traffic) {
                val from = (route.alongAtIndex(span.startIndex) / route.distance).toFloat()
                val to = (route.alongAtIndex(span.endIndex) / route.distance).toFloat()
                fill.color = when (span.level) {
                    1 -> Color.rgb(0xF5, 0xC5, 0x18)
                    2 -> Color.rgb(0xF2, 0x45, 0x3D)
                    else -> Color.rgb(0x9B, 0x16, 0x22)
                }
                val y1 = bottom - (bottom - top) * to
                val y2 = bottom - (bottom - top) * from
                canvas.drawRect(x - w / 2, y1, x + w / 2, maxOf(y2, y1 + 2 * dp), fill)
            }
        }
        rect.set(x - w / 2, at, x + w / 2, bottom)
        fill.color = Color.argb(0xE6, 0xA9, 0xB6, 0xCB)
        canvas.drawRoundRect(rect, w / 2, w / 2, fill)
        fill.color = Color.WHITE
        canvas.drawCircle(x, at, 10 * dp, fill)
        fill.color = Color.rgb(0x3F, 0x80, 0xFF)
        val head = android.graphics.Path()
        head.moveTo(x, at - 6 * dp)
        head.lineTo(x + 5 * dp, at + 5 * dp)
        head.lineTo(x, at + 2.5f * dp)
        head.lineTo(x - 5 * dp, at + 5 * dp)
        head.close()
        canvas.drawPath(head, fill)
    }

    /** Čierna „pilulka“ dole v strede: rýchlostný limit a názov aktuálnej cesty. */
    private fun drawRoadName(canvas: Canvas, area: Rect, name: String?, limit: Int?) {
        text.typeface = bold
        text.textSize = 19 * dp
        val h = 40 * dp
        val maxText = area.width() * 0.7f
        var label = name.orEmpty()
        while (label.length > 3 && text.measureText(label) > maxText) label = label.dropLast(2) + "…"
        val textW = if (label.isEmpty()) 0f else text.measureText(label)
        val signW = if (limit != null) h - 4 * dp else 0f
        val w = textW + signW + (if (label.isEmpty()) 4 * dp else 30 * dp)
        val cx = area.exactCenterX()
        val bottom = area.bottom - 10 * dp
        val left = cx - w / 2
        rect.set(left, bottom - h, left + w, bottom)
        fill.color = Color.argb(0xF2, 0x05, 0x06, 0x08)
        canvas.drawRoundRect(rect, h / 2, h / 2, fill)
        if (limit != null) drawLimit(canvas, left + 2 * dp + signW / 2, bottom - h / 2, signW / 2, limit)
        if (label.isNotEmpty()) {
            text.typeface = bold
            text.textSize = 19 * dp
            text.color = Color.WHITE
            text.textAlign = Paint.Align.LEFT
            canvas.drawText(label, left + signW + 15 * dp, bottom - h / 2 + 7 * dp, text)
            text.textAlign = Paint.Align.CENTER
        }
    }

    private fun drawAttribution(canvas: Canvas, area: Rect) {
        text.typeface = regular
        text.textSize = 10 * dp
        text.color = Color.argb(0xAA, 0xFF, 0xFF, 0xFF)
        text.textAlign = Paint.Align.LEFT
        val label = "© OpenStreetMap · OpenFreeMap" + if (Settings.tomtomKey != null) " · © TomTom" else ""
        canvas.drawText(label, area.left + 8 * dp, area.bottom - 6 * dp, text)
        text.textAlign = Paint.Align.CENTER
    }
}
