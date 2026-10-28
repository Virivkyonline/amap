package online.virivky.amap.nav

import online.virivky.amap.geo.Geo
import online.virivky.amap.geo.GeoPoint
import kotlin.math.max
import kotlin.math.min

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
) {
    private val cumulative = DoubleArray(points.size)
    val distance: Double

    init {
        for (i in 1 until points.size) {
            cumulative[i] = cumulative[i - 1] + Geo.distance(points[i - 1], points[i])
        }
        distance = if (points.isEmpty()) 0.0 else cumulative[points.size - 1]
    }

    private fun alongAt(step: RouteStep) = cumulative[step.startIndex.coerceIn(0, points.size - 1)]

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
