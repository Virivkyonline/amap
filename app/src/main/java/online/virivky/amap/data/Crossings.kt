package online.virivky.amap.data

import android.os.SystemClock
import online.virivky.amap.geo.GeoPoint
import online.virivky.amap.net.Crossing
import online.virivky.amap.net.CrossingData
import online.virivky.amap.util.Bg
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Priechody pre chodcov pre mapu pri veľkom priblížení, načítané po štvorcoch ~1,6 km
 * (dlaždice priblíženia 14) okolo stredu mapy. Len z hlavného vlákna.
 */
object Crossings {
    fun interface Listener {
        fun onCrossingsChanged()
    }

    private const val ZOOM = 14
    private const val MAX_TILES = 24
    private const val RETRY_MS = 90_000L

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val tiles = object : LinkedHashMap<Long, List<Crossing>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<Crossing>>?) = size > MAX_TILES
    }
    private val loading = HashSet<Long>()
    private val failedAt = HashMap<Long, Long>()

    /** Zvýši sa pri každej novej načítanej dlaždici. */
    var version = 0
        private set

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /** Dlaždica, v ktorej leží bod (na zistenie, či sa mapa presunula inam). */
    fun tileKey(p: GeoPoint): Long = key(floor(tileX(p.lon)).toInt(), floor(tileY(p.lat)).toInt())

    /** Načíta dlaždicu s bodom a susedné, ak je bod bližšie ako tretina dlaždice k jej okraju. */
    fun request(p: GeoPoint) {
        val x = tileX(p.lon)
        val y = tileY(p.lat)
        val ix = floor(x).toInt()
        val iy = floor(y).toInt()
        val dx = when {
            x - ix < 0.33 -> -1
            x - ix > 0.67 -> 1
            else -> 0
        }
        val dy = when {
            y - iy < 0.33 -> -1
            y - iy > 0.67 -> 1
            else -> 0
        }
        load(ix, iy)
        if (dx != 0) load(ix + dx, iy)
        if (dy != 0) load(ix, iy + dy)
        if (dx != 0 && dy != 0) load(ix + dx, iy + dy)
    }

    /** Načítané priechody v dlaždici s bodom a v ôsmich okolitých. */
    fun around(p: GeoPoint): List<Crossing> {
        val ix = floor(tileX(p.lon)).toInt()
        val iy = floor(tileY(p.lat)).toInt()
        val result = ArrayList<Crossing>()
        for (x in ix - 1..ix + 1) for (y in iy - 1..iy + 1) tiles[key(x, y)]?.let { result.addAll(it) }
        return result
    }

    private fun load(x: Int, y: Int) {
        val k = key(x, y)
        if (tiles.containsKey(k) || k in loading) return
        val failed = failedAt[k]
        if (failed != null && SystemClock.elapsedRealtime() - failed < RETRY_MS) return
        loading.add(k)
        val n = (1 shl ZOOM).toDouble()
        val west = x / n * 360 - 180
        val east = (x + 1) / n * 360 - 180
        val north = lat(y / n)
        val south = lat((y + 1) / n)
        Bg.run({ CrossingData.fetch(south, west, north, east) }) { result ->
            loading.remove(k)
            result.onSuccess {
                failedAt.remove(k)
                tiles[k] = it
                version++
                listeners.forEach { listener -> listener.onCrossingsChanged() }
            }.onFailure {
                failedAt[k] = SystemClock.elapsedRealtime()
            }
        }
    }

    private fun key(x: Int, y: Int) = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

    private fun tileX(lon: Double) = (lon + 180) / 360 * (1 shl ZOOM)

    private fun tileY(lat: Double): Double {
        val r = Math.toRadians(lat)
        return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * (1 shl ZOOM)
    }

    private fun lat(yFraction: Double) = Math.toDegrees(atan(sinh(PI * (1 - 2 * yFraction))))
}
