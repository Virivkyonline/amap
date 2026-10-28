package online.virivky.amap.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import online.virivky.amap.geo.GeoPoint
import online.virivky.amap.geo.Mercator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Kreslí mapu z OSM dlaždíc, trasu a značky na ľubovoľný Canvas.
 * Používa ho obrazovka v aute (Surface z Android Auto) aj mapa v telefóne.
 */
class MapRenderer {
    var center = GeoPoint(48.1486, 17.1077)
    var zoom = 15.0
        set(value) {
            field = value.coerceIn(MIN_ZOOM, MAX_ZOOM)
        }

    /** Svetová strana, ktorá smeruje na obrazovke hore (0 = sever). */
    var bearing = 0f

    /** Koľko pixelov obrazovky pripadá na jeden pixel dlaždice. */
    var pixelScale = 1f
    var darkMode = false

    /** Časť plochy, ktorú nezakrývajú ovládacie prvky. Prázdna = celá plocha. */
    val viewport = Rect()

    /** Kde v rámci viewportu leží stred mapy (0.5 = v strede, 0.7 = nižšie pri navigácii). */
    var anchorYFraction = 0.5f

    var route: List<GeoPoint>? = null
    var routeStartIndex = 0
    var routeHead: GeoPoint? = null
    var destination: GeoPoint? = null
    var myLocation: GeoPoint? = null
    var myHeading: Float? = null
    var myAccuracy = 0f

    private var width = 0
    private var height = 0

    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val tileRect = RectF()
    private val srcRect = Rect()
    private val routePath = Path()
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.rgb(0x1A, 0x73, 0xE8)
    }
    private val routeCasingPaint = Paint(routePaint).apply { color = Color.rgb(0x0B, 0x47, 0xA8) }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val markerPath = Path()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setSize(width: Int, height: Int) {
        this.width = width
        this.height = height
    }

    private fun anchorX(): Float = if (viewport.isEmpty) width / 2f else viewport.exactCenterX()

    private fun anchorY(): Float =
        if (viewport.isEmpty) height * anchorYFraction
        else viewport.top + viewport.height() * anchorYFraction

    /** Veľkosť sveta v pixeloch obrazovky pre aktuálne priblíženie. */
    private fun worldPx() = Mercator.TILE * 2.0.pow(zoom) * pixelScale

    fun draw(canvas: Canvas, width: Int, height: Int) {
        setSize(width, height)
        canvas.drawColor(if (darkMode) BG_DARK else BG_LIGHT)
        val ax = anchorX()
        val ay = anchorY()

        val tz = floor(zoom).toInt().coerceIn(0, MAX_TILE_ZOOM)
        val tileWorld = Mercator.TILE * (1 shl tz)
        val scale = 2.0.pow(zoom - tz) * pixelScale
        val cx = Mercator.x(center.lon, tileWorld)
        val cy = Mercator.y(center.lat, tileWorld)

        canvas.save()
        canvas.rotate(-bearing, ax, ay)

        // Pri otočení mapy treba pokryť celý kruh okolo stredu.
        val reach = hypot(max(ax, width - ax).toDouble(), max(ay, height - ay).toDouble()) / scale
        val n = 1 shl tz
        val minTx = floor((cx - reach) / Mercator.TILE).toInt()
        val maxTx = floor((cx + reach) / Mercator.TILE).toInt()
        val minTy = max(0, floor((cy - reach) / Mercator.TILE).toInt())
        val maxTy = min(n - 1, floor((cy + reach) / Mercator.TILE).toInt())
        val tileSize = (Mercator.TILE * scale).toFloat()
        tilePaint.colorFilter = if (darkMode) DARK_FILTER else null

        for (ty in minTy..maxTy) {
            for (tx in minTx..maxTx) {
                val left = (ax + (tx * Mercator.TILE - cx) * scale).toFloat()
                val top = (ay + (ty * Mercator.TILE - cy) * scale).toFloat()
                tileRect.set(left, top, left + tileSize + 0.5f, top + tileSize + 0.5f)
                drawTile(canvas, tz, ((tx % n) + n) % n, ty)
            }
        }

        val dp = pixelScale
        drawRoute(canvas, ax, ay, cx, cy, tileWorld, scale, dp)
        destination?.let { drawPin(canvas, toScreen(it, ax, ay, cx, cy, tileWorld, scale), dp) }
        myLocation?.let { drawMe(canvas, toScreen(it, ax, ay, cx, cy, tileWorld, scale), dp) }
        canvas.restore()

        drawAttribution(canvas, dp)
    }

    private fun drawTile(canvas: Canvas, z: Int, x: Int, y: Int) {
        val bitmap = TileCache.get(z, x, y)
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, null, tileRect, tilePaint)
            return
        }
        // Kým sa dlaždica stiahne, zväčšíme kúsok z menšieho priblíženia.
        for (d in 1..min(z, 6)) {
            val parent: Bitmap = TileCache.peek(z - d, x shr d, y shr d) ?: continue
            val sub = parent.width shr d
            if (sub <= 0) break
            val ox = (x - ((x shr d) shl d)) * sub
            val oy = (y - ((y shr d) shl d)) * sub
            srcRect.set(ox, oy, ox + sub, oy + sub)
            canvas.drawBitmap(parent, srcRect, tileRect, tilePaint)
            return
        }
    }

    private fun toScreen(
        p: GeoPoint, ax: Float, ay: Float, cx: Double, cy: Double, world: Double, scale: Double,
    ): FloatArray {
        val x = ax + (Mercator.x(p.lon, world) - cx) * scale
        val y = ay + (Mercator.y(p.lat, world) - cy) * scale
        return floatArrayOf(x.toFloat(), y.toFloat())
    }

    private fun drawRoute(
        canvas: Canvas, ax: Float, ay: Float, cx: Double, cy: Double, world: Double,
        scale: Double, dp: Float,
    ) {
        val points = route ?: return
        if (points.size < 2) return
        routePath.reset()
        var lastX = Float.NaN
        var lastY = Float.NaN
        val head = routeHead
        val start = routeStartIndex.coerceIn(0, points.size - 1)
        if (head != null) {
            val s = toScreen(head, ax, ay, cx, cy, world, scale)
            routePath.moveTo(s[0], s[1])
            lastX = s[0]
            lastY = s[1]
        }
        for (i in (if (head != null) start + 1 else start) until points.size) {
            val p = points[i]
            val x = (ax + (Mercator.x(p.lon, world) - cx) * scale).toFloat()
            val y = (ay + (Mercator.y(p.lat, world) - cy) * scale).toFloat()
            if (lastX.isNaN()) {
                routePath.moveTo(x, y)
            } else if (abs(x - lastX) + abs(y - lastY) >= 1.5f || i == points.size - 1) {
                routePath.lineTo(x, y)
            } else {
                continue
            }
            lastX = x
            lastY = y
        }
        routeCasingPaint.strokeWidth = 9f * dp
        routePaint.strokeWidth = 6f * dp
        canvas.drawPath(routePath, routeCasingPaint)
        canvas.drawPath(routePath, routePaint)
    }

    private fun drawPin(canvas: Canvas, s: FloatArray, dp: Float) {
        val x = s[0]
        val y = s[1]
        canvas.save()
        canvas.rotate(bearing, x, y)
        val r = 11f * dp
        markerPath.reset()
        markerPath.addCircle(x, y - 2.4f * r, r, Path.Direction.CW)
        markerPath.moveTo(x - r * 0.75f, y - 1.8f * r)
        markerPath.lineTo(x, y)
        markerPath.lineTo(x + r * 0.75f, y - 1.8f * r)
        markerPath.close()
        fillPaint.color = Color.rgb(0xD9, 0x30, 0x25)
        canvas.drawPath(markerPath, fillPaint)
        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = 2f * dp
        canvas.drawCircle(x, y - 2.4f * r, r, strokePaint)
        fillPaint.color = Color.WHITE
        canvas.drawCircle(x, y - 2.4f * r, r * 0.38f, fillPaint)
        canvas.restore()
    }

    private fun drawMe(canvas: Canvas, s: FloatArray, dp: Float) {
        val x = s[0]
        val y = s[1]
        val accuracyPx = (myAccuracy / metersPerPixel()).toFloat()
        if (accuracyPx > 12f * dp) {
            fillPaint.color = Color.argb(0x26, 0x1A, 0x73, 0xE8)
            canvas.drawCircle(x, y, accuracyPx, fillPaint)
        }
        val heading = myHeading
        if (heading == null) {
            fillPaint.color = Color.WHITE
            canvas.drawCircle(x, y, 10f * dp, fillPaint)
            fillPaint.color = Color.rgb(0x1A, 0x73, 0xE8)
            canvas.drawCircle(x, y, 7f * dp, fillPaint)
            return
        }
        // Šípka v smere jazdy; plátno je už otočené podľa mapy.
        canvas.save()
        canvas.rotate(heading, x, y)
        val r = 14f * dp
        markerPath.reset()
        markerPath.moveTo(x, y - r)
        markerPath.lineTo(x + r * 0.78f, y + r * 0.8f)
        markerPath.lineTo(x, y + r * 0.38f)
        markerPath.lineTo(x - r * 0.78f, y + r * 0.8f)
        markerPath.close()
        fillPaint.color = Color.rgb(0x1A, 0x73, 0xE8)
        canvas.drawPath(markerPath, fillPaint)
        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = 2.5f * dp
        canvas.drawPath(markerPath, strokePaint)
        canvas.restore()
    }

    private fun drawAttribution(canvas: Canvas, dp: Float) {
        val text = "© OpenStreetMap"
        textPaint.textSize = 10f * dp
        val padding = 4f * dp
        val textWidth = textPaint.measureText(text)
        val right = (if (viewport.isEmpty) width else viewport.right).toFloat() - padding
        val bottom = (if (viewport.isEmpty) height else viewport.bottom).toFloat() - padding
        fillPaint.color = if (darkMode) Color.argb(0x99, 0, 0, 0) else Color.argb(0xB3, 0xFF, 0xFF, 0xFF)
        canvas.drawRect(right - textWidth - 2 * padding, bottom - textPaint.textSize - padding, right, bottom, fillPaint)
        textPaint.color = if (darkMode) Color.LTGRAY else Color.DKGRAY
        canvas.drawText(text, right - textWidth - padding, bottom - padding * 0.9f, textPaint)
    }

    fun metersPerPixel(): Double =
        cos(Math.toRadians(center.lat)) * 2 * PI * 6378137.0 / worldPx()

    /** Prevedie otočený posun na obrazovke na posun vo „svete“ (sever hore). */
    private fun unrotate(dx: Double, dy: Double): DoubleArray {
        val rad = Math.toRadians(bearing.toDouble())
        return doubleArrayOf(dx * cos(rad) - dy * sin(rad), dx * sin(rad) + dy * cos(rad))
    }

    fun screenToGeo(sx: Float, sy: Float): GeoPoint {
        val world = worldPx()
        val d = unrotate((sx - anchorX()).toDouble(), (sy - anchorY()).toDouble())
        val wx = Mercator.x(center.lon, world) + d[0]
        val wy = Mercator.y(center.lat, world) + d[1]
        return GeoPoint(Mercator.lat(wy, world), Mercator.lon(wx, world))
    }

    /** Posun mapy o vzdialenosť, o ktorú prešiel prst (kladné dx = pohyb doľava). */
    fun panBy(dx: Float, dy: Float) {
        val world = worldPx()
        val d = unrotate(dx.toDouble(), dy.toDouble())
        val wx = Mercator.x(center.lon, world) + d[0]
        val wy = Mercator.y(center.lat, world) + d[1]
        center = GeoPoint(Mercator.lat(wy, world), Mercator.lon(wx, world))
    }

    /** Zmena priblíženia tak, aby bod pod prstom zostal na mieste. */
    fun zoomBy(delta: Double, focusX: Float? = null, focusY: Float? = null) {
        if (focusX == null || focusY == null || width == 0) {
            zoom += delta
            return
        }
        val focus = screenToGeo(focusX, focusY)
        zoom += delta
        val world = worldPx()
        val d = unrotate((focusX - anchorX()).toDouble(), (focusY - anchorY()).toDouble())
        val wx = Mercator.x(focus.lon, world) - d[0]
        val wy = Mercator.y(focus.lat, world) - d[1]
        center = GeoPoint(Mercator.lat(wy, world), Mercator.lon(wx, world))
    }

    /** Nastaví stred a priblíženie tak, aby boli vidieť všetky body. */
    fun fit(points: List<GeoPoint>, paddingPx: Float): Boolean {
        if (points.isEmpty() || width == 0) return false
        bearing = 0f
        anchorYFraction = 0.5f
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (p in points) {
            val x = Mercator.x(p.lon, Mercator.TILE)
            val y = Mercator.y(p.lat, Mercator.TILE)
            minX = min(minX, x); maxX = max(maxX, x)
            minY = min(minY, y); maxY = max(maxY, y)
        }
        val w = (if (viewport.isEmpty) width else viewport.width()) - 2 * paddingPx
        val h = (if (viewport.isEmpty) height else viewport.height()) - 2 * paddingPx
        val spanX = max(maxX - minX, 1e-9) * pixelScale
        val spanY = max(maxY - minY, 1e-9) * pixelScale
        zoom = min(ln(max(w, 1f) / spanX) / ln(2.0), ln(max(h, 1f) / spanY) / ln(2.0)).coerceAtMost(17.0)
        center = GeoPoint(
            Mercator.lat((minY + maxY) / 2, Mercator.TILE),
            Mercator.lon((minX + maxX) / 2, Mercator.TILE),
        )
        return true
    }

    companion object {
        const val MIN_ZOOM = 3.0
        const val MAX_ZOOM = 19.5
        const val MAX_TILE_ZOOM = 19
        private val BG_LIGHT = Color.rgb(0xF2, 0xEF, 0xE9)
        private val BG_DARK = Color.rgb(0x1E, 0x22, 0x28)

        /** Nočný režim: invertovanie jasu a otočenie odtieňa späť (cesty ostanú farebné). */
        private val DARK_FILTER = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f,
                )
            ).apply {
                postConcat(
                    ColorMatrix(
                        floatArrayOf(
                            -0.574f, 1.430f, 0.144f, 0f, 0f,
                            0.426f, 0.430f, 0.144f, 0f, 0f,
                            0.426f, 1.430f, -0.856f, 0f, 0f,
                            0f, 0f, 0f, 1f, 0f,
                        )
                    )
                )
                postConcat(ColorMatrix().apply { setScale(0.85f, 0.85f, 0.9f, 1f) })
            }
        )
    }
}
