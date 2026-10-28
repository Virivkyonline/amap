package online.virivky.amap.nav

import online.virivky.amap.geo.Geo
import online.virivky.amap.geo.GeoPoint
import kotlin.math.max
import kotlin.math.min

/** Jazdný pruh pred manévrom: šípky na ceste a či je pruh vhodný pre trasu. */
class LaneInfo(val indications: List<String>, val valid: Boolean)

/** Radar na trase: poloha, vzdialenosť od začiatku trasy a prípadný limit. */
class Camera(val point: GeoPoint, val along: Double, val limit: Int?)

/** Úsek trasy s premávkou medzi bodmi trasy: úroveň 1 = pomaly, 2 = zápcha, 3 = stojí/uzávierka. */
class TrafficSpan(val startIndex: Int, val endIndex: Int, val level: Int)

class RouteStep(
    /** Typ manévru z OSRM: turn, depart, arrive, roundabout, fork, ... */
    val type: String,
    /** Smer: left, slight right, straight, uturn, ... */
    val modifier: String?,
    /** Číslo výjazdu na kruhovom objazde. */
    val exit: Int?,
    val name: String,
    val ref: String,
    val destinations: String,
    val distance: Double,
    val duration: Double,
    val location: GeoPoint,
    /** Index bodu trasy, v ktorom sa manéver nachádza. */
    val startIndex: Int,
    val lanes: List<LaneInfo> = emptyList(),
    /** Číslo výjazdu z diaľnice (z tabule), ak je známe. */
    val exitNumber: String = "",
)

/** Aktuálny stav jazdy po trase. */
class Progress(
    val segmentIndex: Int,
    val snapped: GeoPoint,
    val segmentBearing: Float?,
    val offRouteDistance: Double,
    val traveled: Double,
    val nextStepIndex: Int,
    val distanceToNextStep: Double,
    val secondsToNextStep: Double,
    val remainingDistance: Double,
    val remainingSeconds: Double,
)

class Route(
    val points: List<GeoPoint>,
    val steps: List<RouteStep>,
    val duration: Double,
    /** OSM id bodov trasy (rovnaký počet ako [points]), ak ich server poslal. */
    val nodeIds: LongArray? = null,
    /** Úseky so zhoršenou premávkou (len trasy z TomTom). */
    val traffic: List<TrafficSpan> = emptyList(),
    /** Zdrženie kvôli premávke v sekundách. */
    val trafficDelay: Double = 0.0,
    /** Trasa vypočítaná podľa živej premávky (TomTom). */
    val live: Boolean = false,
) {
    private val cumulative = DoubleArray(points.size)
    val distance: Double

    /** Rýchlostný limit v km/h pre každý úsek trasy (0 = neznámy). Dopĺňa sa dodatočne. */
    @Volatile
    var speedLimits: IntArray? = null

    /** Radary pozdĺž trasy zoradené podľa vzdialenosti. Dopĺňajú sa dodatočne. */
    @Volatile
    var cameras: List<Camera> = emptyList()

    /** Vzdialenosti semaforov od začiatku trasy. Dopĺňajú sa dodatočne. */
    @Volatile
    var trafficSignals: DoubleArray = DoubleArray(0)

    /** Úroveň premávky pre každý úsek trasy (0 = plynulo alebo neznáme). */
    val segmentTraffic: IntArray by lazy {
        val levels = IntArray(maxOf(0, points.size - 1))
        for (span in traffic) {
            for (i in span.startIndex.coerceAtLeast(0) until span.endIndex.coerceAtMost(levels.size)) {
                if (span.level > levels[i]) levels[i] = span.level
            }
        }
        levels
    }

    fun alongAtIndex(index: Int) = cumulative[index.coerceIn(0, points.size - 1)]

    fun stepAlong(stepIndex: Int) = alongAt(steps[stepIndex.coerceIn(0, steps.size - 1)])

    /** Bod na trase vo vzdialenosti [along] od začiatku. */
    fun pointAt(along: Double): GeoPoint {
        if (along <= 0) return points.first()
        if (along >= distance) return points.last()
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (cumulative[mid] <= along) lo = mid else hi = mid
        }
        val len = cumulative[hi] - cumulative[lo]
        val t = if (len > 0) (along - cumulative[lo]) / len else 0.0
        return Geo.interpolate(points[lo], points[hi], t)
    }

    /** Časť trasy medzi dvoma vzdialenosťami od začiatku. */
    fun slice(from: Double, to: Double): List<GeoPoint> {
        val start = from.coerceIn(0.0, distance)
        val end = to.coerceIn(start, distance)
        val result = ArrayList<GeoPoint>()
        result.add(pointAt(start))
        for (i in points.indices) {
            if (cumulative[i] > start && cumulative[i] < end) result.add(points[i])
        }
        result.add(pointAt(end))
        return result
    }

    init {
        for (i in 1 until points.size) {
            cumulative[i] = cumulative[i - 1] + Geo.distance(points[i - 1], points[i])
        }
        distance = if (points.isEmpty()) 0.0 else cumulative[points.size - 1]
    }

    private fun alongAt(step: RouteStep) = cumulative[step.startIndex.coerceIn(0, points.size - 1)]

    /** Vzdialenosť od začiatku trasy k bodu, ak leží najviac [maxDistance] m od trasy. */
    fun alongOf(p: GeoPoint, maxDistance: Double): Double? {
        var best = maxDistance
        var result: Double? = null
        for (i in 0 until points.size - 1) {
            val hit = Geo.pointToSegment(p, points[i], points[i + 1])
            if (hit.distance <= best) {
                best = hit.distance
                result = cumulative[i] + (cumulative[i + 1] - cumulative[i]) * hit.t
            }
        }
        return result
    }

    /** Nájde polohu na trase. [hint] je úsek z minulej polohy, aby sme hľadali najprv okolo neho. */
    fun locate(p: GeoPoint, hint: Int): Progress {
        var best = Double.MAX_VALUE
        var bestSeg = 0
        var bestT = 0.0
        fun scan(from: Int, to: Int) {
            for (i in from until to) {
                val hit = Geo.pointToSegment(p, points[i], points[i + 1])
                if (hit.distance < best) {
                    best = hit.distance
                    bestSeg = i
                    bestT = hit.t
                }
            }
        }
        val last = points.size - 1
        if (last < 1) {
            return Progress(0, points.firstOrNull() ?: p, null, 0.0, 0.0, max(0, steps.size - 1), 0.0, 0.0, 0.0, 0.0)
        }
        scan(max(0, hint - 10), min(last, hint + 400))
        if (best > 60) scan(0, last)

        val a = points[bestSeg]
        val b = points[bestSeg + 1]
        val traveled = cumulative[bestSeg] + (cumulative[bestSeg + 1] - cumulative[bestSeg]) * bestT
        var next = steps.indexOfFirst { alongAt(it) > traveled + 3 }
        if (next < 0) next = steps.size - 1
        val toNext = max(0.0, alongAt(steps[next]) - traveled)

        // Čas: zvyšok aktuálneho kroku pomerne + celé nasledujúce kroky.
        val current = steps[max(0, next - 1)]
        val fraction = if (current.distance > 1) (toNext / current.distance).coerceIn(0.0, 1.0) else 0.0
        val toNextSeconds = current.duration * fraction
        var remainingSeconds = toNextSeconds
        for (i in next until steps.size) remainingSeconds += steps[i].duration

        val segBearing = if (Geo.distance(a, b) > 0.5) Geo.bearing(a, b).toFloat() else null
        return Progress(
            segmentIndex = bestSeg,
            snapped = Geo.interpolate(a, b, bestT),
            segmentBearing = segBearing,
            offRouteDistance = best,
            traveled = traveled,
            nextStepIndex = next,
            distanceToNextStep = toNext,
            secondsToNextStep = toNextSeconds,
            remainingDistance = max(0.0, distance - traveled),
            remainingSeconds = remainingSeconds,
        )
    }
}
