package online.virivky.amap.map

import online.virivky.amap.geo.Geo
import online.virivky.amap.geo.GeoPoint
import online.virivky.amap.nav.Instructions
import online.virivky.amap.nav.Route
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Úsek cesty z vykreslenej mapy: koncové body, smer a typ cesty. */
class RoadPiece(val a: GeoPoint, val b: GeoPoint, val road: RoadShape) {
    val bearing = Geo.bearing(a, b)
}

/**
 * Trasa v pravom pruhu (ako v AMap): na obojsmernej ceste v pravej polovici, na jednosmernej (diaľnica,
 * smerovo rozdelená cesta, kruhový objazd) v pravom pruhu, a pod ňou podfarbený celý náš smer jazdy.
 * Typ a šírka cesty sa zisťujú z vykreslenej mapy pozdĺž trasy ([set]); kým nie sú známe, platí odhad podľa
 * trasy. Posuny sú v metroch doprava od osi trasy a menia sa plynulo.
 */
class RoadSide(val route: Route) {
    private val count = (route.distance / STEP).toInt() + 2
    /** Cesta v každom úseku po [STEP] m; null = ešte nezistená (platí odhad). */
    private val roads = arrayOfNulls<RoadShape>(count)
    private val tried = BooleanArray(count)
    private var laneOffsets: DoubleArray? = null
    private var tintOffsets: DoubleArray? = null
    private var tintWidths: DoubleArray? = null

    /** Zvýši sa pri každej zmene (treba prekresliť trasu). */
    var version = 0
        private set

    private fun index(along: Double) = (along / STEP).toInt().coerceIn(0, count - 1)

    /** Či sa cesta v mieste [along] už zisťovala z mapy. */
    fun known(along: Double) = tried[index(along)]

    /** Cesta v mieste [along] podľa mapy; null = na mape nie je (tunel, chodník) – ostane odhad. */
    fun set(along: Double, road: RoadShape?) {
        val k = index(along)
        if (tried[k] && roads[k] == road) return
        tried[k] = true
        roads[k] = road
        laneOffsets = null
        version++
    }

    /** Posun trasy doprava (do stredu pravého pruhu) v mieste [along]. */
    fun offsetAt(along: Double): Double = interpolate(compute().first, along)

    /** Stred podfarbenia nášho smeru jazdy v mieste [along] (posun doprava od osi). */
    fun tintAt(along: Double): Double = interpolate(compute().second, along)

    /**
     * Podfarbenie nášho smeru jazdy medzi [from] a [to]: úseky s rovnakou šírkou (v metroch)
     * a ich čiary v strede nášho smeru.
     */
    fun tint(from: Double, to: Double): List<Pair<List<GeoPoint>, Double>> {
        val widths = compute().third
        val result = ArrayList<Pair<List<GeoPoint>, Double>>()
        var start = max(0.0, from)
        val end = min(route.distance, to)
        while (start < end) {
            val width = widths[index(start)]
            var stop = (index(start) + 1) * STEP
            while (stop < end && widths[index(stop)] == width) stop += STEP
            stop = min(stop, end)
            val line = path(start, stop, ::tintAt)
            if (line.size >= 2) result.add(line to width)
            start = stop
        }
        return result
    }

    private fun interpolate(values: DoubleArray, along: Double): Double {
        val x = (along / STEP).coerceIn(0.0, (count - 1).toDouble())
        val i = min(x.toInt(), count - 2)
        return values[i] + (values[i + 1] - values[i]) * (x - i)
    }

    /** Bod trasy v mieste [along] posunutý o [right] metrov doprava. */
    fun pointAt(along: Double, right: Double): GeoPoint {
        val p = route.pointAt(along)
        if (abs(right) < 0.01) return p
        val a = route.pointAt(along - 4)
        val b = route.pointAt(along + 4)
        return if (Geo.distance(a, b) > 0.5) Geo.offset(p, Geo.bearing(a, b), right) else p
    }

    /** Trasa medzi [from] a [to] posunutá podľa [offset] (metre doprava v mieste along). */
    fun path(from: Double, to: Double, offset: (Double) -> Double = ::offsetAt): List<GeoPoint> {
        val start = from.coerceIn(0.0, route.distance)
        val end = to.coerceIn(start, route.distance)
        val points = ArrayList<GeoPoint>()
        val alongs = ArrayList<Double>()
        fun add(p: GeoPoint, a: Double) {
            if (points.isNotEmpty() && Geo.distance(points.last(), p) < 0.3) return
            points.add(p)
            alongs.add(a)
        }
        add(route.pointAt(start), start)
        var lo = 0
        var hi = route.points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (route.alongAtIndex(mid) <= start) lo = mid else hi = mid
        }
        for (i in lo until route.points.size) {
            val a = route.alongAtIndex(i)
            if (a >= end) break
            if (a > start) add(route.points[i], a)
        }
        if (points.size < 2 || Geo.distance(points.last(), route.pointAt(end)) >= 0.3) {
            points.add(route.pointAt(end))
            alongs.add(end)
        }
        return shift(points, DoubleArray(alongs.size) { alongs[it] }, offset)
    }

    /** Odhad, kým cestu nepoznáme z mapy: diaľnica, rýchlostná cesta či rampa, inak obojsmerná miestna cesta. */
    private fun guessFor(stepIndex: Int): RoadShape {
        val step = route.steps.getOrNull(stepIndex) ?: return MINOR
        val oneWay = Instructions.isHighway(step) || step.type in setOf("fork", "merge", "on ramp", "off ramp") ||
            step.ref.startsWith("D") || step.ref.startsWith("R")
        return if (oneWay) MOTORWAY else MINOR
    }

    /** Posuny trasy, stredy a šírky podfarbenia pre všetky úseky (prepočíta sa len po zmene). */
    private fun compute(): Triple<DoubleArray, DoubleArray, DoubleArray> {
        laneOffsets?.let { return Triple(it, tintOffsets!!, tintWidths!!) }
        val lane = DoubleArray(count)
        val tint = DoubleArray(count)
        val widths = DoubleArray(count)
        // Kroky trasy idú po poradí, odhad stačí jedným prechodom.
        var step = 0
        for (k in 0 until count) {
            val along = k * STEP
            while (step + 1 < route.steps.size && route.stepAlong(step + 1) <= along) step++
            val road = roads[k] ?: guessFor(step)
            // Pravý okraj nášho smeru je vždy pol šírky cesty od osi; náš smer je celá jednosmerná cesta
            // alebo pravá polovica obojsmernej. Trasa ide stredom pravého pruhu (najviac 3,5 m).
            val edge = road.width / 2
            val carriageway = if (road.oneWay) road.width else road.width / 2
            lane[k] = edge - min(LANE, carriageway) / 2
            tint[k] = edge - carriageway / 2
            widths[k] = carriageway
        }
        fun smooth(values: DoubleArray) = DoubleArray(count) { k ->
            var sum = 0.0
            var n = 0
            for (j in max(0, k - SMOOTH)..min(count - 1, k + SMOOTH)) {
                sum += values[j]
                n++
            }
            sum / n
        }
        laneOffsets = smooth(lane)
        tintOffsets = smooth(tint)
        tintWidths = widths
        return Triple(laneOffsets!!, tintOffsets!!, widths)
    }

    companion object {
        /** Krok, po ktorom sa zisťuje cesta pod trasou. */
        const val STEP = 8.0
        private const val SMOOTH = 2
        /** Šírka jazdného pruhu. */
        private const val LANE = 3.5
        /** Odhady cesty: obojsmerná miestna a diaľnica (jeden smer), šírky ako na mape. */
        private val MINOR = RoadShape(6.5, false)
        private val MOTORWAY = RoadShape(11.0, true)

        /**
         * Čiara posunutá do strany: každý bod o [offset] svojej vzdialenosti od začiatku trasy, v rohoch
         * po osi uhla (s obmedzením, aby ostré zákruty nerobili hroty).
         */
        fun shift(points: List<GeoPoint>, alongs: DoubleArray, offset: (Double) -> Double): List<GeoPoint> {
            if (points.size < 2) return points
            val lat0 = points[0].lat
            val lon0 = points[0].lon
            val kx = 111_320.0 * cos(Math.toRadians(lat0))
            val ky = 111_320.0
            val x = DoubleArray(points.size) { (points[it].lon - lon0) * kx }
            val y = DoubleArray(points.size) { (points[it].lat - lat0) * ky }
            // Pravá normála každého úseku.
            val nx = DoubleArray(points.size - 1)
            val ny = DoubleArray(points.size - 1)
            for (i in 0 until points.size - 1) {
                val dx = x[i + 1] - x[i]
                val dy = y[i + 1] - y[i]
                val len = sqrt(dx * dx + dy * dy)
                if (len > 1e-6) {
                    nx[i] = dy / len
                    ny[i] = -dx / len
                } else if (i > 0) {
                    nx[i] = nx[i - 1]
                    ny[i] = ny[i - 1]
                }
            }
            return points.indices.map { i ->
                val o = offset(alongs[i])
                if (abs(o) < 0.01) return@map points[i]
                val a = max(0, i - 1).coerceAtMost(points.size - 2)
                val b = min(i, points.size - 2)
                var mx = nx[a] + nx[b]
                var my = ny[a] + ny[b]
                val len = sqrt(mx * mx + my * my)
                if (len < 1e-6) {
                    mx = nx[b]
                    my = ny[b]
                } else {
                    mx /= len
                    my /= len
                }
                // Predĺženie v rohu (1 / cos polovice uhla), najviac dvojnásobok.
                val dot = mx * nx[b] + my * ny[b]
                val scale = if (dot > 0.5) 1 / dot else 2.0
                GeoPoint(lat0 + (y[i] + my * o * scale) / ky, lon0 + (x[i] + mx * o * scale) / kx)
            }
        }

        /** Cesta pod bodom [p] v smere [bearing] (nie priečna) najviac [maxDistance] metrov od neho. */
        fun match(pieces: List<RoadPiece>, p: GeoPoint, bearing: Double, maxDistance: Double): RoadShape? {
            val box = maxDistance / 111_320.0 * 1.5
            val boxLon = box / cos(Math.toRadians(p.lat))
            var best: RoadShape? = null
            var bestDistance = maxDistance
            for (piece in pieces) {
                if (min(piece.a.lat, piece.b.lat) - box > p.lat || max(piece.a.lat, piece.b.lat) + box < p.lat) continue
                if (min(piece.a.lon, piece.b.lon) - boxLon > p.lon || max(piece.a.lon, piece.b.lon) + boxLon < p.lon) continue
                val turn = abs(((piece.bearing - bearing) % 180 + 180) % 180 - 90)
                if (turn < 55) continue // priečna cesta
                val distance = Geo.pointToSegment(p, piece.a, piece.b).distance
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = piece.road
                }
            }
            return best
        }
    }
}
