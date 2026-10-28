package online.virivky.amap.net

import online.virivky.amap.geo.Geo
import online.virivky.amap.geo.GeoPoint
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Priechod pre chodcov na ceste: poloha, smer cesty v stupňoch a trieda cesty (podľa nej šírka na mape). */
class Crossing(val point: GeoPoint, val bearing: Double, val road: String)

/** Priechody pre chodcov z OpenStreetMap (Overpass): vyznačené priechody a smer cesty, cez ktorú vedú. */
object CrossingData {
    /** Priechody v obdĺžniku (juh, západ, sever, východ). */
    fun fetch(south: Double, west: Double, north: Double, east: Double): List<Crossing> {
        val box = String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f", south, west, north, east)
        val query = "[out:json][timeout:25];" +
            "(node[highway=crossing][crossing!~\"^(unmarked|no|informal)$\"][\"crossing:markings\"!=\"no\"]($box);" +
            "node[highway=traffic_signals][crossing~\"^(traffic_signals|marked|zebra|uncontrolled)$\"]($box);)->.c;" +
            "way(bn.c)[highway~\"^(trunk|primary|secondary|tertiary|unclassified|residential|living_street|service|road)(_link)?$\"];" +
            "out body geom;.c out skel;"
        return parse(RoadData.overpass(query))
    }

    /** Z odpovede Overpass: pre každý priechod cesta, na ktorej leží, a jej smer v tom mieste. */
    fun parse(json: String): List<Crossing> {
        val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val crossingIds = HashSet<Long>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            if (e.optString("type") == "node") crossingIds.add(e.getLong("id"))
        }
        val seen = HashSet<Long>()
        val result = ArrayList<Crossing>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            if (e.optString("type") != "way") continue
            val road = roadClass(e.optJSONObject("tags")?.optString("highway").orEmpty()) ?: continue
            val nodes = e.optJSONArray("nodes") ?: continue
            val geometry = e.optJSONArray("geometry") ?: continue
            if (nodes.length() != geometry.length() || nodes.length() < 2) continue
            fun at(k: Int) = geometry.optJSONObject(k)?.let { GeoPoint(it.getDouble("lat"), it.getDouble("lon")) }
            for (k in 0 until nodes.length()) {
                val id = nodes.getLong(k)
                if (id !in crossingIds || id in seen) continue
                val here = at(k) ?: continue
                val a = at(max(0, k - 1)) ?: continue
                val b = at(min(nodes.length() - 1, k + 1)) ?: continue
                if (Geo.distance(a, b) < 0.5) continue
                seen.add(id)
                result.add(Crossing(here, Geo.bearing(a, b), road))
            }
        }
        return result
    }

    /** Trieda cesty ako v mapovom štýle (major, secondary, minor, service). */
    private fun roadClass(highway: String): String? = when (highway.removeSuffix("_link")) {
        "trunk", "primary" -> "major"
        "secondary", "tertiary" -> "secondary"
        "unclassified", "residential", "living_street", "road" -> "minor"
        "service" -> "service"
        else -> null
    }
}
