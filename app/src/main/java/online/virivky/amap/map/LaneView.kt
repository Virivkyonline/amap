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
data class RoadShape(val width: Double, val oneWay: Boolean)

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
    /** Os trasy (zaoblená v rohoch, [RoadSide.axisAt]), z ktorej sa pruhy počítajú. */
    axis: (Double) -> GeoPoint,
    /** Okraje nášho smeru jazdy podľa mapy – na začiatku pruhy začínajú v šírke cesty. */
    private val roadLeft: Double,
    private val roadRight: Double,
) {
    val lanes = good.size

    /** Posun trasy a auta: do stredu správnych pruhov. */
    val shift = left + (good.indices.filter { good[it] }.average() + 0.5) * laneWidth

    /** Dĺžka plynulého prechodu trasy do pruhov. */
    val ramp = min(RAMP, (stop - start) / 2)

    /** Na akej dĺžke sa pruhy plynulo rozšíria zo šírky cesty na svoju šírku (ako odbočovací pruh). */
    private val taper = min(TAPER, (stop - start) * 0.4)

    /**
     * Hranica pruhov [i] (0 = ľavý okraj, [lanes] = pravý) v mieste [along]: na začiatku v šírke cesty
     * z mapy, potom sa plynulo rozšíri na šírku pruhov (ak sa do cesty nezmestia).
     */
    fun edge(i: Int, along: Double): Double {
        val full = left + i * laneWidth
        val narrow = roadLeft + i * (roadRight - roadLeft) / lanes
        val f = if (taper <= 0) 1.0 else ease(((along - start) / taper).coerceIn(0.0, 1.0))
        return narrow + (full - narrow) * f
    }

    /** Stred pruhu [i] v mieste [along]. */
    fun laneCenter(i: Int, along: Double) = (edge(i, along) + edge(i + 1, along)) / 2

    /** Stred správnych pruhov v mieste [along]. */
    private fun goodCenter(along: Double) = good.indices.filter { good[it] }.map { laneCenter(it, along) }.average()

    private val count = max(1, ceil((end - start) / STEP).toInt())
    private val points: Array<GeoPoint>
    private val bearings: DoubleArray

    init {
        // Dvakrát kĺzavý priemer okolo každého bodu (plynulé oblúky namiesto zlomov); ku koncom sa okno
        // zužuje, aby os nadväzovala na trasu.
        var smooth = Array(count + 1) { axis(along(it)) }
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

    /** Posun trasy mimo pruhov (trasa v pravej polovici cesty, [RoadSide]); pruhy naň plynulo nadväzujú. */
    var base: (Double) -> Double = { 0.0 }

    /** Posun trasy do strany v mieste [along]: nábeh do pruhov, pruhy a za stop čiarou späť na posun mimo pruhov. */
    fun shiftAt(along: Double): Double = when {
        along <= start || along >= end -> base(along)
        along < start + ramp -> base(start).let { it + (goodCenter(along) - it) * ease((along - start) / ramp) }
        along <= stop -> goodCenter(along)
        else -> base(end).let { it + (goodCenter(stop) - it) * ease((end - along) / (end - stop)) }
    }

    /**
     * Plocha medzi dvoma čiarami pozdĺž osi (od [from] po [to]): vľavo [leftOf], vpravo [rightOf]
     * (posuny doprava v mieste along) – uzavretý obrys pre mnohouholník.
     */
    fun band(from: Double, to: Double, leftOf: (Double) -> Double, rightOf: (Double) -> Double): List<GeoPoint> {
        val leftSide = path(from, to, leftOf)
        if (leftSide.size < 2) return emptyList()
        return leftSide + path(from, to, rightOf).reversed() + leftSide.first()
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
        private const val TAPER = 45.0
        /** Krok vzoriek osi a polovica okna vyhladenia (v krokoch: ±14 m). */
        private const val STEP = 2.0
        private const val SMOOTH = 7
        /** Užší pruh by sa už nedal čítať – vtedy pruhy trochu presahujú cestu (ako rozšírenie pred križovatkou). */
        private const val MIN_LANE = 2.1

        /**
         * Pohľad na pruhy pre manéver [stepIndex] s pruhmi; začína pred autom ([carAlong]), aby auto neskočilo
         * do strany. [road] = cesta pod autom podľa mapy, null = odhad podľa typu manévru.
         */
        fun create(
            route: Route, stepIndex: Int, step: RouteStep, carAlong: Double?, road: RoadShape?,
            axis: (Double) -> GeoPoint = route::pointAt,
        ): LaneView? {
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
            // Okraje nášho smeru na mape (bez údajov o ceste rovno okraje pruhov – bez rozširovania).
            val (roadLeft, roadRight) = when {
                road == null -> left to left + n * w
                oneWay -> -road.width / 2 to road.width / 2
                else -> 0.0 to road.width / 2
            }
            return LaneView(route, stepIndex, start, stop, end, left, w, good, axis, roadLeft, roadRight)
        }

        private fun ease(t: Double) = t * t * (3 - 2 * t)
    }
}
