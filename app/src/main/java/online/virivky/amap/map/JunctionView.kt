package online.virivky.amap.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import online.virivky.amap.nav.Instructions
import online.virivky.amap.nav.RouteStep
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Zväčšený obraz križovatky (ako v AMap): nakreslený pohľad na cestu v perspektíve
 * s odbočkou, pruhmi, tabuľou a zelenou šípkou, kam ísť. Pre zjazdy, rozdvojenia
 * a kruhové objazdy.
 */
object JunctionView {
    private const val LANE = 3.5f
    private val cache = LinkedHashMap<String, Bitmap>()

    /** Ukázať obraz križovatky pre manéver vo vzdialenosti [distance] metrov? */
    fun wanted(step: RouteStep, distance: Double): Boolean = when {
        distance < 15 -> false
        scene(step) == Scene.ROUNDABOUT -> distance < 400
        scene(step) != null -> distance < 1000
        else -> false
    }

    private enum class Scene { EXIT, FORK, ROUNDABOUT }

    private fun scene(step: RouteStep): Scene? = when (step.type) {
        "off ramp" -> Scene.EXIT
        "fork" -> if (step.modifier?.contains("left") == true || step.modifier?.contains("right") == true) Scene.FORK else null
        "roundabout", "rotary" -> Scene.ROUNDABOUT
        else -> null
    }

    fun bitmap(step: RouteStep, width: Int, height: Int, dark: Boolean): Bitmap? {
        val scene = scene(step) ?: return null
        val key = "${System.identityHashCode(step)}|$width|$height|$dark"
        cache[key]?.let { return it }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        // Na kruhový objazd sa pozeráme viac zhora, na zjazd ako z auta (trochu vyššie).
        val cam = if (scene == Scene.ROUNDABOUT) Camera(width.toFloat(), height.toFloat(), eye = 20f, horizonShare = 0.2f)
        else Camera(width.toFloat(), height.toFloat(), eye = 8f, horizonShare = 0.3f)
        val left = step.modifier?.contains("left") == true
        background(c, cam, dark)
        when (scene) {
            Scene.EXIT -> split(c, cam, step, dark, mirror = left, fork = false)
            Scene.FORK -> split(c, cam, step, dark, mirror = left, fork = true)
            Scene.ROUNDABOUT -> roundabout(c, cam, step, dark)
        }
        if (cache.size > 6) cache.remove(cache.keys.first())
        cache[key] = bitmap
        return bitmap
    }

    /** Jednoduchá perspektíva: kamera [eye] m nad cestou, pohľad dopredu. */
    private class Camera(val w: Float, val h: Float, val eye: Float, horizonShare: Float) {
        /** Bočná poloha kamery (nad naším pruhom). */
        var camX = 0f
        val horizon = h * horizonShare
        val f = w * 0.55f
        val zMin = eye * f / (h - horizon)

        fun x(gx: Float, z: Float) = w / 2 + (gx - camX) * f / z
        fun y(z: Float, above: Float = 0f) = horizon + (eye - above) * f / z
    }

    private fun background(c: Canvas, cam: Camera, dark: Boolean) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val skyTop = if (dark) 0xFF0A1530.toInt() else 0xFF5AA7F2.toInt()
        val skyBottom = if (dark) 0xFF213A5E.toInt() else 0xFFD3E9FB.toInt()
        p.shader = LinearGradient(0f, 0f, 0f, cam.horizon, skyTop, skyBottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, cam.w, cam.horizon + 1, p)
        // Vzdialené kopce na obzore.
        p.shader = null
        p.color = if (dark) 0xFF17261C.toInt() else 0xFF8DB98A.toInt()
        val hills = Path()
        hills.moveTo(0f, cam.horizon)
        var x = 0f
        while (x <= cam.w) {
            hills.lineTo(x, cam.horizon - cam.h * (0.025f + 0.02f * sin(x / cam.w * 9f) + 0.012f * sin(x / cam.w * 23f)))
            x += cam.w / 40
        }
        hills.lineTo(cam.w, cam.horizon)
        hills.close()
        c.drawPath(hills, p)
        val grassFar = if (dark) 0xFF1C2A20.toInt() else 0xFF8FC27A.toInt()
        val grassNear = if (dark) 0xFF0F1712.toInt() else 0xFF5E9B4C.toInt()
        p.shader = LinearGradient(0f, cam.horizon, 0f, cam.h, grassFar, grassNear, Shader.TileMode.CLAMP)
        c.drawRect(0f, cam.horizon, cam.w, cam.h, p)
        p.shader = null
    }

    /** Body cesty v teréne: stred [cx] a polovičná šírka [hw] podľa vzdialenosti z. */
    private fun roadPath(cam: Camera, z0: Float, z1: Float, cx: (Float) -> Float, hw: (Float) -> Float): Path {
        val zs = zSamples(cam, z0, z1)
        val path = Path()
        zs.forEachIndexed { i, z ->
            val sx = cam.x(cx(z) - hw(z), z)
            if (i == 0) path.moveTo(sx, cam.y(z)) else path.lineTo(sx, cam.y(z))
        }
        for (z in zs.reversed()) path.lineTo(cam.x(cx(z) + hw(z), z), cam.y(z))
        path.close()
        return path
    }

    /** Vzdialenosti hustejšie blízko (perspektíva). */
    private fun zSamples(cam: Camera, z0: Float, z1: Float): List<Float> {
        val start = max(z0, cam.zMin * 0.98f)
        val n = 48
        return (0..n).map { start * Math.pow((z1 / start).toDouble(), it / n.toDouble()).toFloat() }
    }

    private fun line(c: Canvas, cam: Camera, z0: Float, z1: Float, gx: (Float) -> Float, paint: Paint, dash: Boolean) {
        val zs = zSamples(cam, z0, z1)
        if (!dash) {
            val path = Path()
            zs.forEachIndexed { i, z -> if (i == 0) path.moveTo(cam.x(gx(z), z), cam.y(z)) else path.lineTo(cam.x(gx(z), z), cam.y(z)) }
            c.drawPath(path, paint)
            return
        }
        // Prerušovaná: 6 m čiara, 9 m medzera (v teréne, takže sa do diaľky zmenšuje).
        var z = max(z0, cam.zMin)
        while (z < z1) {
            val end = min(z + 6f, z1)
            val path = Path()
            path.moveTo(cam.x(gx(z), z), cam.y(z))
            var t = z
            while (t < end) {
                t = min(t + 1f, end)
                path.lineTo(cam.x(gx(t), t), cam.y(t))
            }
            val w = paint.strokeWidth
            paint.strokeWidth = w * min(2f, 12f / z)
            c.drawPath(path, paint)
            paint.strokeWidth = w
            z += 15f
        }
    }

    /**
     * Zjazd alebo rozdvojenie: hlavná cesta rovno, vetva odbočuje vpravo
     * (pri [mirror] vľavo); šípka vedie do vetvy.
     */
    private fun split(c: Canvas, cam: Camera, step: RouteStep, dark: Boolean, mirror: Boolean, fork: Boolean) {
        val s = if (mirror) -1f else 1f
        val lanes = step.lanes.size.coerceIn(2, 5)
        val half = lanes * LANE / 2
        val zSplit = 26f
        // Kamera nad pruhom, z ktorého odbočujeme (pri rozdvojení nad stredom).
        cam.camX = if (fork) 0f else s * (half - LANE / 2)
        // Vetva sa od hlavnej cesty vzďaľuje pod miernym, postupne rastúcim uhlom.
        fun away(z: Float) = if (z <= zSplit) 0f else (z - zSplit).let { 0.25f * it + 0.004f * it * it }
        val asphalt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) 0xFF2E333B.toInt() else 0xFF5B6068.toInt() }
        val mainCx: (Float) -> Float
        val mainHw: (Float) -> Float
        val branchCx: (Float) -> Float
        val branchHw = { _: Float -> LANE * (if (fork) lanes / 2f else 1f) / 2 + 0.5f }
        if (fork) {
            // Rozdvojenie: dve rovnocenné vetvy, každá s polovicou pruhov.
            mainCx = { z -> -s * (half / 2 + away(z)) }
            mainHw = { _ -> half / 2 + 0.5f }
            branchCx = { z -> s * (half / 2 + away(z)) }
        } else {
            mainCx = { _ -> 0f }
            mainHw = { _ -> half + 0.6f }
            branchCx = { z -> s * (half + LANE / 2 + away(z)) }
        }
        // Spoločný úsek pred rozdelením.
        c.drawPath(roadPath(cam, cam.zMin, zSplit + 2f, { 0f }, { half + 0.6f }), asphalt)
        if (!fork) {
            // Pripájací pruh zjazdu sa rozširuje pred rozdelením.
            c.drawPath(roadPath(cam, zSplit - 18f, zSplit + 2f, { z -> s * (half + LANE / 2) }, { z -> (LANE / 2 + 0.4f) * ((z - (zSplit - 18f)) / 18f).coerceIn(0f, 1f) }), asphalt)
        }
        c.drawPath(roadPath(cam, zSplit, 420f, mainCx, mainHw), asphalt)
        c.drawPath(roadPath(cam, zSplit, 200f, branchCx, branchHw), asphalt)

        // Vodorovné značenie.
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) 0xFFD5D9E0.toInt() else Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = cam.w * 0.006f
            strokeCap = Paint.Cap.ROUND
        }
        line(c, cam, cam.zMin, zSplit, { -half }, white, dash = false)
        line(c, cam, cam.zMin, zSplit, { half }, white, dash = false)
        for (k in 1 until lanes) line(c, cam, cam.zMin, zSplit, { -half + k * LANE }, white, dash = true)
        line(c, cam, zSplit, 420f, { z -> mainCx(z) - mainHw(z) + 0.5f }, white, dash = false)
        line(c, cam, zSplit, 420f, { z -> mainCx(z) + mainHw(z) - 0.5f }, white, dash = false)
        line(c, cam, zSplit, 200f, { z -> branchCx(z) - branchHw(z) + 0.5f }, white, dash = false)
        line(c, cam, zSplit, 200f, { z -> branchCx(z) + branchHw(z) - 0.5f }, white, dash = false)

        // Tabuľa nad vetvou s cieľmi a číslom výjazdu.
        sign(c, cam, branchCx(40f), 40f, step, dark)

        // Šípka: z pravého (ľavého) pruhu do vetvy.
        val startX = if (fork) s * (half / 2) else s * (half - LANE / 2)
        val pts = ArrayList<Pair<Float, Float>>()
        var z = cam.zMin + 0.5f
        val shiftFrom = cam.zMin + 2f
        while (z < 80f) {
            // Preradenie do vetvy hneď vpredu, kde je posun zreteľný.
            val t = ((z - shiftFrom) / (zSplit - shiftFrom)).coerceIn(0f, 1f)
            val gx = startX + (branchCx(z) - startX) * (t * t * (3 - 2 * t))
            pts.add(gx to z)
            z += if (z < 30f) 1f else 2.5f
        }
        arrow(c, cam, pts)
    }

    /** Kruhový objazd: vjazd zdola, jazda proti smeru hodinových ručičiek, výjazd podľa čísla. */
    private fun roundabout(c: Canvas, cam: Camera, step: RouteStep, dark: Boolean) {
        val asphalt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) 0xFF2E333B.toInt() else 0xFF5B6068.toInt() }
        val island = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) 0xFF24402C.toInt() else 0xFF6FAE5A.toInt() }
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) 0xFFD5D9E0.toInt() else Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = cam.w * 0.005f
        }
        val zc = 44f
        val rOut = 15f
        val rIn = 7.5f
        val exitNumber = (step.exit ?: 1).coerceIn(1, 6)
        val exits = max(3, exitNumber)
        val angles = (1..exits).map { -PI / 2 + it * (2 * PI / (exits + 1)) }
        fun ground(r: Float, a: Double) = (r * cos(a)).toFloat() to (zc + r * sin(a)).toFloat()
        fun ring(r: Float): Path {
            val path = Path()
            for (i in 0..72) {
                val (gx, z) = ground(r, i * 2 * PI / 72)
                if (i == 0) path.moveTo(cam.x(gx, z), cam.y(z)) else path.lineTo(cam.x(gx, z), cam.y(z))
            }
            path.close()
            return path
        }
        // Prípojné cesty: vjazd zdola a výjazdy po obvode.
        c.drawPath(roadPath(cam, cam.zMin, zc - rOut + 2f, { 0f }, { LANE + 0.5f }), asphalt)
        for (a in angles) {
            val pts = (0..20).map { i -> ground(rOut - 1f + i * 3f, a) }
            val path = Path()
            val nx = (-sin(a)).toFloat()
            val nz = cos(a).toFloat()
            pts.forEachIndexed { i, (gx, z) ->
                val x = cam.x(gx - nx * (LANE + 0.5f), max(z - nz * (LANE + 0.5f), 1f))
                val y = cam.y(max(z - nz * (LANE + 0.5f), 1f))
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            for ((gx, z) in pts.reversed()) path.lineTo(cam.x(gx + nx * (LANE + 0.5f), max(z + nz * (LANE + 0.5f), 1f)), cam.y(max(z + nz * (LANE + 0.5f), 1f)))
            path.close()
            c.drawPath(path, asphalt)
        }
        c.drawPath(ring(rOut), asphalt)
        c.drawPath(ring(rIn), island)
        c.drawPath(ring(rIn), white)
        c.drawPath(ring(rOut - 0.4f), white.apply { alpha = 120 })

        // Šípka: vjazd, okolo stredu a von zvoleným výjazdom.
        val rc = (rOut + rIn) / 2
        val target = angles[exitNumber - 1]
        val pts = ArrayList<Pair<Float, Float>>()
        var z = cam.zMin + 0.5f
        while (z < zc - rc) {
            pts.add(LANE / 2 to z)
            z += 1f
        }
        var a = -PI / 2 + 0.35
        while (a < target) {
            pts.add(ground(rc, a))
            a += 0.08
        }
        for (i in 0..8) pts.add(ground(rc + i * 2.5f, target))
        arrow(c, cam, pts)
    }

    /** Zelená šípka s bielym okrajom pozdĺž bodov v teréne (x, z). */
    private fun arrow(c: Canvas, cam: Camera, pts: List<Pair<Float, Float>>) {
        if (pts.size < 3) return
        val width = 1.3f
        val body = pts.dropLast(2)
        val left = ArrayList<Pair<Float, Float>>()
        val right = ArrayList<Pair<Float, Float>>()
        for (i in body.indices) {
            val (x0, z0) = body[max(0, i - 1)]
            val (x1, z1) = body[min(body.size - 1, i + 1)]
            val len = hypot(x1 - x0, z1 - z0).coerceAtLeast(0.001f)
            val nx = (z1 - z0) / len
            val nz = -(x1 - x0) / len
            val (x, z) = body[i]
            left.add((x - nx * width) to (z - nz * width))
            right.add((x + nx * width) to (z + nz * width))
        }
        val path = Path()
        fun pt(p: Pair<Float, Float>) = cam.x(p.first, max(p.second, 0.5f)) to cam.y(max(p.second, 0.5f))
        left.forEachIndexed { i, p -> val (x, y) = pt(p); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        // Hrot šípky.
        val (bx, bz) = body.last()
        val (ex, ez) = pts.last()
        val dir = atan2((ez - bz).toDouble(), (ex - bx).toDouble())
        val tipLen = 5f
        val tip = (bx + (cos(dir) * tipLen).toFloat()) to (bz + (sin(dir) * tipLen).toFloat())
        val nx = (-sin(dir)).toFloat()
        val nz = cos(dir).toFloat()
        val wingL = (bx + nx * width * 2.4f) to (bz + nz * width * 2.4f)
        val wingR = (bx - nx * width * 2.4f) to (bz - nz * width * 2.4f)
        // Poradie krídiel podľa strany ľavého okraja.
        val leftFirst = hypot(left.last().first - wingL.first, left.last().second - wingL.second) <
            hypot(left.last().first - wingR.first, left.last().second - wingR.second)
        val (w1, w2) = if (leftFirst) wingL to wingR else wingR to wingL
        listOf(w1, tip, w2).forEach { val (x, y) = pt(it); path.lineTo(x, y) }
        right.reversed().forEach { val (x, y) = pt(it); path.lineTo(x, y) }
        path.close()
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = cam.w * 0.012f
            strokeJoin = Paint.Join.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, cam.h, 0f, cam.horizon, 0xFF16B57A.toInt(), 0xFF5BEA9E.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawPath(path, outline)
        c.drawPath(path, fill)
    }

    /** Zelená diaľničná tabuľa nad vetvou: číslo výjazdu a ciele. */
    private fun sign(c: Canvas, cam: Camera, gx: Float, z: Float, step: RouteStep, dark: Boolean) {
        val label = Instructions.exitLabel(step)
        val text = (Instructions.signpost(step) ?: step.destinations).ifBlank { null } ?: label ?: return
        val scale = cam.f / z
        val w = max(11f * scale, cam.w * 0.3f)
        val h = 3.6f * scale
        val cx = cam.x(gx, z)
        val bottom = cam.y(z, above = 6.2f)
        val rect = RectF(cx - w / 2, bottom - h, cx + w / 2, bottom)
        // Stĺpy portálu.
        val post = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) 0xFF7A808A.toInt() else 0xFF9AA1AB.toInt() }
        c.drawRect(rect.left + w * 0.08f, bottom, rect.left + w * 0.08f + scale * 0.25f, cam.y(z), post)
        c.drawRect(rect.right - w * 0.08f - scale * 0.25f, bottom, rect.right - w * 0.08f, cam.y(z), post)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1C8A57.toInt() }
        c.drawRoundRect(rect, h * 0.12f, h * 0.12f, p)
        p.color = Color.WHITE
        p.style = Paint.Style.STROKE
        p.strokeWidth = h * 0.05f
        c.drawRoundRect(RectF(rect.left + h * 0.07f, rect.top + h * 0.07f, rect.right - h * 0.07f, rect.bottom - h * 0.07f), h * 0.1f, h * 0.1f, p)
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val lines = listOfNotNull(label?.takeIf { it != text }, text).take(2)
        t.textSize = h * (if (lines.size == 2) 0.32f else 0.42f)
        lines.forEachIndexed { i, line ->
            var s = line
            while (s.length > 3 && t.measureText(s) > w * 0.9f) s = s.dropLast(2) + "…"
            val y = rect.top + h * (if (lines.size == 2) 0.42f + i * 0.38f else 0.64f)
            c.drawText(s, cx, y, t)
        }
    }
}
