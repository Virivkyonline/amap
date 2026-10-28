package online.virivky.amap.geo

import android.location.Location
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

data class GeoPoint(val lat: Double, val lon: Double) {
    constructor(location: Location) : this(location.latitude, location.longitude)
}

/** Výsledok priemetu bodu na úsečku: vzdialenosť v metroch a pozícia t v intervale 0..1. */
class SegmentHit(val distance: Double, val t: Double)

object Geo {
    private const val EARTH_RADIUS = 6371008.8

    fun distance(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Azimut z a do b v stupňoch 0..360. */
    fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun interpolate(a: GeoPoint, b: GeoPoint, t: Double) =
        GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)

    /** Priemet bodu p na úsečku ab v lokálnej rovinnej aproximácii (stačí na krátke úseky). */
    fun pointToSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): SegmentHit {
        val k = Math.toRadians(1.0) * EARTH_RADIUS
        val kx = k * cos(Math.toRadians(p.lat))
        val ax = (a.lon - p.lon) * kx
        val ay = (a.lat - p.lat) * k
        val bx = (b.lon - p.lon) * kx
        val by = (b.lat - p.lat) * k
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 < 1e-9) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val px = ax + dx * t
        val py = ay + dy * t
        return SegmentHit(sqrt(px * px + py * py), t)
    }

    /** Body čiary najviac každých [spacing] metrov (kratšie dotazy pre Overpass). */
    fun thin(points: List<GeoPoint>, spacing: Double): List<GeoPoint> {
        if (points.size <= 2) return points
        val result = arrayListOf(points.first())
        for (p in points) if (distance(result.last(), p) >= spacing) result.add(p)
        if (result.last() != points.last()) result.add(points.last())
        return result
    }
}

/** Dekódovanie Google polyline formátu (OSRM používa presnosť 6). */
object Polyline {
    fun decode(encoded: String, precision: Int = 6): List<GeoPoint> {
        val factor = Math.pow(10.0, precision.toDouble())
        val result = ArrayList<GeoPoint>()
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            for (axis in 0..1) {
                var shift = 0
                var value = 0L
                while (true) {
                    val b = encoded[index++].code - 63
                    value = value or ((b and 0x1f).toLong() shl shift)
                    shift += 5
                    if (b < 0x20) break
                }
                val delta = if (value and 1L != 0L) (value shr 1).inv() else value shr 1
                if (axis == 0) lat += delta else lon += delta
            }
            result.add(GeoPoint(lat / factor, lon / factor))
        }
        return result
    }
}

/** Web Mercator – rovnaké premietanie ako dlaždice OpenStreetMap. */
object Mercator {
    const val TILE = 256.0
    private const val MAX_LAT = 85.05112878

    fun x(lon: Double, worldSize: Double) = (lon + 180.0) / 360.0 * worldSize

    fun y(lat: Double, worldSize: Double): Double {
        val s = sin(Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT)))
        return (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * worldSize
    }

    fun lon(x: Double, worldSize: Double): Double {
        val lon = x / worldSize * 360.0 - 180.0
        return ((lon + 540.0) % 360.0) - 180.0
    }

    fun lat(y: Double, worldSize: Double): Double {
        val n = PI - 2.0 * PI * y.coerceIn(0.0, worldSize) / worldSize
        return Math.toDegrees(atan(sinh(n)))
    }
}
