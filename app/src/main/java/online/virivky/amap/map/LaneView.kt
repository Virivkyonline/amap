package online.virivky.amap.map

import online.virivky.amap.geo.Geo
import online.virivky.amap.geo.GeoPoint
import online.virivky.amap.nav.Instructions
import online.virivky.amap.nav.LaneIcons
import online.virivky.amap.nav.Route
import online.virivky.amap.nav.RouteStep
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Cesta pod autom podľa mapy: šírka vozovky v metroch (ako ju mapa kreslí) a či je jednosmerná. */
class RoadShape(val width: Double, val oneWay: Boolean)

/**
 * Pohľad na pruhy pred manévrom (ako v AMap): úsek trasy pred križovatkou, kde sa vozovka kreslí po pruhoch
 * a trasa aj auto plynulo prejdú do správnych pruhov. Os je tu vyhladená (bez zubov na zlomoch čiary)
 * a posuny do strany sú v metroch doprava od nej.
 */
class LaneView private constructor(
    val route: Route,
    val stepIndex: Int,
    /** Začiatok pruhov, stop čiara a bod manévru (vzdialenosti od začiatku trasy). */
    val start: Double,
    val stop: Double,
    val end: Double,
    /** Ľavý okraj našich pruhov a šírka jedného pruhu. */
    val left: Double,
    val laneWidth: Double,
    /** Ktoré pruhy sú správne (zelené). */
    val good: BooleanArray,
) {
    val lanes = good.size

    /** Posun trasy a auta: do stredu správnych pruhov. */
    val shift = left + (good.indices.filter { good[it] }.average() + 0.5) * laneWidth

    /** Dĺžka plynulého prechodu trasy do pruhov. */
    val ramp = min(RAMP, (stop - start) / 2)

    private val count = max(1, ceil((end - start) / STEP).toInt())
    private val points: Array<GeoPoint>
    private val bearings: DoubleArray

    init {
        // Dvakrát kĺzavý priemer okolo každého bodu (plynulé oblúky namiesto zlomov); ku koncom sa okno
        // zužuje, aby os nadväzovala na trasu.
        var smooth = Array(count + 1) { route.pointAt(along(it)) }
        repeat(2) {
            val raw = smooth
            smooth = Array(count + 1) { i ->
                val k = min(SMOOTH, min(i, count - i))
                var lat = 0.0
                var lon = 0.0
                for (j in i - k..i + k) {
                    lat += raw[j].lat
                    lon += raw[j].lon
                }
                GeoPoint(lat / (2 * k + 1), lon / (2 * k + 1))
            }
        }
        points = smooth
        bearings = DoubleArray(count + 1) { i ->
            val a = points[max(0, i - 1)]
            val b = points[min(count, i + 1)]
            if (Geo.distance(a, b) > 0.05) Geo.bearing(a, b) else Geo.bearing(route.pointAt(along(i) - 3), route.pointAt(along(i) + 3))
        }
    }

    private fun along(i: Int) = start + (end - start) * i / count

    operator fun contains(along: Double) = along > start && along < end

    /** Posun trasy do strany v mieste [along]: nábeh do pruhov, pruhy a za stop čiarou späť k osi. */
    fun shiftAt(along: Double): Double = when {
        along <= start || along >= end -> 0.0
        along < start + ramp -> shift * ease((along - start) / ramp)
        along <= stop -> shift
        else -> shift * ease((end - along) / (end - stop))
    }

    /** Bod [right] metrov napravo od vyhladenej osi v mieste [along]. */
    fun pointAt(along: Double, right: Double): GeoPoint {
        val x = ((along - start) / (end - start) * count).coerceIn(0.0, count.toDouble())
        val i = min(x.toInt(), count - 1)
        val t = x - i
        return Geo.interpolate(Geo.offset(points[i], bearings[i], right), Geo.offset(points[i + 1], bearings[i + 1], right), t)
    }

    /** Smer osi v mieste [along]. */
    fun bearingAt(along: Double): Double {
        val x = ((along - start) / (end - start) * count).coerceIn(0.0, count.toDouble())
        return bearings[x.roundToIntSafe()]
    }

    /** Čiara po osi medzi [from] a [to] (orezané na tento úsek) posunutá do strany podľa [right]. */
    fun path(from: Double, to: Double, right: (Double) -> Double): List<GeoPoint> {
        val a = max(from, start)
        val b = min(to, end)
        if (b <= a) return emptyList()
        val result = ArrayList<GeoPoint>()
        result.add(pointAt(a, right(a)))
        for (i in 0..count) {
            val s = along(i)
            if (s > a && s < b) result.add(Geo.offset(points[i], bearings[i], right(s)))
        }
        result.add(pointAt(b, right(b)))
        return result
    }

    private fun Double.roundToIntSafe() = (this + 0.5).toInt().coerceIn(0, count)

    companion object {
        /** Pruhy sa kreslia najviac toľko metrov pred manévrom. */
        const val BEFORE = 320.0
        /** Stop čiara: kúsok pred bodom manévru (stredom križovatky). */
        private const val STOP_GAP = 5.0
        private const val RAMP = 35.0
        /** Krok vzoriek osi a polovica okna vyhladenia (v krokoch: ±14 m). */
        private const val STEP = 2.0
        private const val SMOOTH = 7
        /** Užší pruh by sa už nedal čítať – vtedy pruhy trochu presahujú cestu (ako rozšírenie pred križovatkou). */
        private const val MIN_LANE = 2.1

        /**
         * Pohľad na pruhy pre manéver [stepIndex] s pruhmi; začína pred autom ([carAlong]), aby auto neskočilo
         * do strany. [road] = cesta pod autom podľa mapy, null = odhad podľa typu manévru.
         */
        fun create(route: Route, stepIndex: Int, step: RouteStep, carAlong: Double?, road: RoadShape?): LaneView? {
            val n = step.lanes.size
            if (n == 0) return null
            val end = route.stepAlong(stepIndex)
            val start = max(max(0.0, end - BEFORE), (carAlong ?: 0.0) + 8)
            val stop = end - STOP_GAP
            if (stop - start < 20) return null
            // Jednosmerná cesta (diaľnica, smerovo rozdelená cesta) = os trasy je stred našich pruhov;
            // obojsmerná = os je stredová čiara a naše pruhy sú v pravej polovici cesty.
            val oneWay = road?.oneWay ?: (
                Instructions.isHighway(step) || step.type in setOf("fork", "merge", "on ramp", "off ramp") ||
                    step.ref.startsWith("D") || step.ref.startsWith("R")
                )
            val available = when {
                road == null -> n * 3.25
                oneWay -> road.width
                else -> road.width / 2
            }
            val w = max(available / n, MIN_LANE)
            val left = if (oneWay) -n * w / 2 else 0.0
            val good = BooleanArray(n) { LaneIcons.recommended(step.lanes[it], step.modifier) != null }
            if (good.none { it }) step.lanes.indices.forEach { good[it] = step.lanes[it].valid }
            if (good.none { it }) good.fill(true)
            return LaneView(route, stepIndex, start, stop, end, left, w, good)
        }

        private fun ease(t: Double) = t * t * (3 - 2 * t)
    }
}
