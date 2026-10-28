package online.virivky.amap.net

import online.virivky.amap.data.Connector
import online.virivky.amap.data.Poi
import online.virivky.amap.data.PoiCategory
import online.virivky.amap.geo.Geo
import online.virivky.amap.geo.GeoPoint
import online.virivky.amap.nav.Route
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Vyhľadanie služieb v okolí: s kľúčom TomTom (aj živá obsadenosť nabíjačiek),
 * inak z OpenStreetMap cez Overpass.
 */
object Pois {
    private const val LIMIT = 50

    /** Ako ďaleko dopredu po trase hľadáme a ako ďaleko od trasy môže služba byť. */
    private const val AHEAD = 50_000.0
    private const val MAX_OFFSET = 1000.0

    fun search(key: String?, category: PoiCategory, center: GeoPoint, radius: Int): List<Poi> {
        if (key != null) {
            try {
                tomtom(key, category, center, radius)?.let { return it }
            } catch (e: Exception) {
                // TomTom nedostupný alebo kľúč bez Search API – použijeme OpenStreetMap.
            }
        }
        // V meste najprv blízke okolie, aby sme dostali najbližšie miesta, nie náhodných 50.
        val near = osm(category, "around:${radius / 4},${center.lat},${center.lon}", center)
        if (near.size >= 8) return near
        return osm(category, "around:$radius,${center.lat},${center.lon}", center)
    }

    /**
     * Služby pozdĺž zvyšku trasy (do 50 km), zoradené podľa vzdialenosti po trase od [traveled].
     */
    fun alongRoute(key: String?, category: PoiCategory, route: Route, traveled: Double): List<Poi> {
        val end = min(route.distance, traveled + AHEAD)
        var found: List<Poi>? = null
        if (key != null) {
            try {
                // Kruhy rozmiestnené po trase každých 10 km.
                val all = ArrayList<Poi>()
                var along = traveled + 5000
                while (true) {
                    val center = route.pointAt(min(along, end))
                    all.addAll(tomtom(key, category, center, 6000) ?: throw IOException("Nesprávna kategória"))
                    if (along + 5000 >= end) break
                    along += 10_000
                }
                found = all
            } catch (e: Exception) {
                // Bez TomTom použijeme OpenStreetMap.
            }
        }
        val pois = found ?: run {
            val line = Geo.thin(route.slice(traveled, end), 400.0)
            osm(category, "around:${MAX_OFFSET.toInt()}," + line.joinToString(",") { "${it.lat},${it.lon}" }, null)
        }
        return pois.mapNotNull { poi ->
            val along = route.alongOf(poi.point, MAX_OFFSET) ?: return@mapNotNull null
            if (along < traveled - 100 || along > end + 500) null else poi.at((along - traveled).coerceAtLeast(0.0))
        }
            .distinctBy { "${it.name}|${(it.point.lat * 2000).roundToInt()}|${(it.point.lon * 2000).roundToInt()}" }
            .sortedBy { it.distance }
            .take(LIMIT)
    }

    private fun tomtom(key: String, category: PoiCategory, center: GeoPoint, radius: Int): List<Poi>? {
        val url = StringBuilder("https://api.tomtom.com/search/2/nearbySearch/.json?key=")
            .append(URLEncoder.encode(key, "UTF-8"))
            .append("&lat=").append(center.lat).append("&lon=").append(center.lon)
            .append("&radius=").append(radius).append("&limit=").append(LIMIT)
            .append("&categorySet=").append(category.tomtom)
        val results = JSONObject(Http.getString(url.toString())).optJSONArray("results") ?: return emptyList()
        val prefixes = category.tomtom.split(',')
        val pois = ArrayList<Poi>()
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            val poi = r.optJSONObject("poi") ?: continue
            // Pre istotu len výsledky z požadovanej kategórie.
            val ids = poi.optJSONArray("categorySet")
            val matches = ids == null || (0 until ids.length()).any { k ->
                val id = ids.getJSONObject(k).optString("id")
                prefixes.any { id.startsWith(it) }
            }
            if (!matches) continue
            val position = r.optJSONObject("position") ?: continue
            val point = GeoPoint(position.optDouble("lat"), position.optDouble("lon"))
            val brand = poi.optJSONArray("brands")?.optJSONObject(0)?.optString("name").orEmpty()
            val name = poi.optString("name").ifBlank { brand }.ifBlank { category.singular }
            val connectors = r.optJSONObject("chargingPark")?.optJSONArray("connectors")?.let { array ->
                (0 until array.length()).map { k ->
                    val c = array.getJSONObject(k)
                    connectorName(c.optString("connectorType")) to c.optDouble("ratedPowerKW", 0.0)
                }.groupBy { it }.map { (key, list) -> Connector(key.first, key.second, list.size) }
            } ?: emptyList()
            pois.add(
                Poi(
                    category = category,
                    name = name,
                    address = r.optJSONObject("address")?.optString("freeformAddress").orEmpty(),
                    point = point,
                    distance = r.optDouble("dist", Geo.distance(center, point)),
                    connectors = connectors,
                    availabilityId = r.optJSONObject("dataSources")?.optJSONObject("chargingAvailability")
                        ?.optString("id")?.ifBlank { null },
                )
            )
        }
        // Výsledky iných kategórií = zlý kód kategórie; radšej OpenStreetMap.
        if (pois.isEmpty() && results.length() > 0) return null
        return pois.sortedBy { it.distance }
    }

    /** Živá obsadenosť nabíjačky (TomTom). */
    fun loadAvailability(key: String, poi: Poi) {
        val id = poi.availabilityId ?: return
        val url = "https://api.tomtom.com/search/2/chargingAvailability.json?key=" +
            URLEncoder.encode(key, "UTF-8") + "&chargingAvailability=" + URLEncoder.encode(id, "UTF-8")
        val connectors = JSONObject(Http.getString(url)).optJSONArray("connectors") ?: return
        var free = 0
        var all = 0
        for (i in 0 until connectors.length()) {
            val c = connectors.getJSONObject(i)
            all += c.optInt("total", 0)
            free += c.optJSONObject("availability")?.optJSONObject("current")?.optInt("available", 0) ?: 0
        }
        if (all > 0) {
            poi.total = all
            poi.available = free
        }
    }

    private fun connectorName(type: String): String = when {
        type.contains("CCS", ignoreCase = true) -> if (type.contains("Type1")) "CCS1" else "CCS"
        type.contains("Chademo", ignoreCase = true) -> "CHAdeMO"
        type.contains("Tesla", ignoreCase = true) -> "Tesla"
        type.contains("Type2", ignoreCase = true) -> "Typ 2"
        type.contains("Type1", ignoreCase = true) -> "Typ 1"
        type.contains("Household", ignoreCase = true) -> "Zásuvka"
        else -> type.ifBlank { "?" }
    }

    /** Služby z OpenStreetMap v oblasti [area] (filter „around“ pre Overpass). */
    private fun osm(category: PoiCategory, area: String, center: GeoPoint?): List<Poi> {
        val query = "[out:json][timeout:25];(${category.osm}($area););out center 300;"
        val elements = JSONObject(RoadData.overpass(query)).optJSONArray("elements") ?: return emptyList()
        val pois = ArrayList<Poi>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: continue
            val c = e.optJSONObject("center")
            val lat = c?.optDouble("lat") ?: e.optDouble("lat", Double.NaN)
            val lon = c?.optDouble("lon") ?: e.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            val point = GeoPoint(lat, lon)
            val name = tags.optString("name").ifBlank { tags.optString("brand") }
                .ifBlank { tags.optString("operator") }.ifBlank { category.singular }
            val street = listOf(tags.optString("addr:street"), tags.optString("addr:housenumber"))
                .filter { it.isNotBlank() }.joinToString(" ")
            val address = listOf(street, tags.optString("addr:city")).filter { it.isNotBlank() }.joinToString(", ")
            pois.add(
                Poi(
                    category = category,
                    name = name,
                    address = address,
                    point = point,
                    distance = center?.let { Geo.distance(it, point) } ?: 0.0,
                    connectors = if (category == PoiCategory.EV) osmConnectors(tags) else emptyList(),
                )
            )
        }
        // Pozdĺž trasy (bez stredu) sa zoradí a skráti až podľa vzdialenosti po trase.
        return if (center == null) pois else pois.sortedBy { it.distance }.take(LIMIT)
    }

    /** Konektory nabíjačky z OSM značiek socket:*. */
    private fun osmConnectors(tags: JSONObject): List<Connector> {
        val sockets = listOf(
            "type2_combo" to "CCS",
            "chademo" to "CHAdeMO",
            "type2" to "Typ 2",
            "type2_cable" to "Typ 2",
            "tesla_supercharger" to "Tesla",
            "schuko" to "Zásuvka",
        )
        return sockets.mapNotNull { (tag, label) ->
            val count = tags.optString("socket:$tag").toIntOrNull() ?: return@mapNotNull null
            if (count <= 0) return@mapNotNull null
            val power = Regex("""(\d+(?:\.\d+)?)""").find(tags.optString("socket:$tag:output"))
                ?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            Connector(label, power, count)
        }
    }
}
