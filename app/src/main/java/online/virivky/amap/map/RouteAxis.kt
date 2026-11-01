package online.virivky.amap.map

import online.virivky.amap.geo.Geo
import online.virivky.amap.geo.GeoPoint
import online.virivky.amap.nav.Route
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * Os trasy na kreslenie: v rohoch (odbočky, zlomy čiary) plynulý oblúk namiesto ostrého zlomu, ako v AMap.
 * Každý bod nesie vzdialenosť od začiatku pôvodnej trasy, takže poloha auta, pruhy, šípka aj farby premávky sedia.
 */
class RouteAxis(val route: Route) {
    private val points: List<GeoPoint>
    private val alongs: DoubleArray

    init {
        val (p, a) = rounded(route)
        points = p
        alongs = a
    }

    /** Bod osi v mieste [along]. */
    fun at(along: Double): GeoPoint {
        val a = along.coerceIn(0.0, route.distance)
        if (alongs.size < 2) return route.pointAt(a)
        val lo = indexBefore(a)
        val hi = min(lo + 1, alongs.size - 1)
        val span = alongs[hi] - alongs[lo]
        val t = if (span > 0) ((a - alongs[lo]) / span).coerceIn(0.0, 1.0) else 0.0
        return Geo.interpolate(points[lo], points[hi], t)
    }

    /** Časť osi medzi [from] a [to]: body a ich vzdialenosti od začiatku trasy. */
    fun slice(from: Double, to: Double): Pair<List<GeoPoint>, DoubleArray> {
        val start = from.coerceIn(0.0, route.distance)
        val end = to.coerceIn(start, route.distance)
        val result = ArrayList<GeoPoint>()
        val resultAlongs = ArrayList<Double>()
        fun add(p: GeoPoint, a: Double) {
            if (result.isNotEmpty() && Geo.distance(result.last(), p) < 0.3) return
            result.add(p)
            resultAlongs.add(a)
        }
        add(at(start), start)
        if (alongs.size >= 2) {
            for (i in indexBefore(start) until alongs.size) {
                val a = alongs[i]
                if (a >= end) break
                if (a > start) add(points[i], a)
            }
        }
        val last = at(end)
        if (result.size < 2 || Geo.distance(result.last(), last) >= 0.3) {
            result.add(last)
            resultAlongs.add(end)
        }
        return result to DoubleArray(resultAlongs.size) { resultAlongs[it] }
    }

    /** Časť osi medzi [from] a [to] bez posunu do strany. */
    fun path(from: Double, to: Double): List<GeoPoint> = slice(from, to).first

    /** Posledný bod osi pred [along] (alebo v ňom). */
    private fun indexBefore(along: Double): Int {
        var lo = 0
        var hi = alongs.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (alongs[mid] <= along) lo = mid else hi = mid
        }
        return lo
    }

    companion object {
        /** Najväčší polomer oblúka v rohu trasy (v metroch) a najmenší uhol, od ktorého sa roh zaobľuje. */
        private const val ROUND_RADIUS = 10.0
        private const val ROUND_MIN_TURN = 8.0

        /**
         * Os trasy so zaoblenými rohmi: každý zlom väčší ako [ROUND_MIN_TURN] nahradí oblúk (kvadratická
         * Bézierova krivka s vrcholom v rohu, takmer kružnica) s polomerom najviac [ROUND_RADIUS], najviac
         * do 45 % susedných úsekov, aby sa oblúky neprekrývali. Vráti body a ich vzdialenosti na pôvodnej trase.
         */
        fun rounded(route: Route): Pair<List<GeoPoint>, DoubleArray> {
            val pts = route.points
            val points = ArrayList<GeoPoint>()
            val alongs = ArrayList<Double>()
            fun add(p: GeoPoint, a: Double) {
                if (alongs.isNotEmpty() && a <= alongs.last()) return
                points.add(p)
                alongs.add(a)
            }
            if (pts.isEmpty()) return points to DoubleArray(0)
            add(pts[0], 0.0)
            for (i in 1 until pts.size - 1) {
                val prev = pts[i - 1]
                val cur = pts[i]
                val next = pts[i + 1]
                val d1 = Geo.distance(prev, cur)
                val d2 = Geo.distance(cur, next)
                val along = route.alongAtIndex(i)
                val turn = abs(((Geo.bearing(cur, next) - Geo.bearing(prev, cur)) + 540) % 360 - 180)
                if (d1 < 0.5 || d2 < 0.5 || turn < ROUND_MIN_TURN) {
                    add(cur, along)
                    continue
                }
                val half = Math.toRadians(min(turn, 170.0) / 2)
                val t = min(ROUND_RADIUS * tan(half), min(0.45 * d1, 0.45 * d2))
                val a = Geo.interpolate(cur, prev, t / d1)
                val b = Geo.interpolate(cur, next, t / d2)
                val k = max(2, (turn / 12).toInt())
                for (j in 0..k) {
                    val u = j.toDouble() / k
                    // Kvadratická Bézierova krivka a → roh → b.
                    val w0 = (1 - u) * (1 - u)
                    val w1 = 2 * u * (1 - u)
                    val w2 = u * u
                    add(
                        GeoPoint(w0 * a.lat + w1 * cur.lat + w2 * b.lat, w0 * a.lon + w1 * cur.lon + w2 * b.lon),
                        along - t + 2 * t * u,
                    )
                }
            }
            if (pts.size > 1) add(pts.last(), route.distance)
            return points to DoubleArray(alongs.size) { alongs[it] }
        }

    }
}
