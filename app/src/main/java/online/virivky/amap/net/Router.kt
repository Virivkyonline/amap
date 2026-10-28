package online.virivky.amap.net

import online.virivky.amap.data.Settings
import online.virivky.amap.geo.Geo
import online.virivky.amap.geo.GeoPoint
import online.virivky.amap.geo.Polyline
import online.virivky.amap.nav.LaneInfo
import online.virivky.amap.nav.Route
import online.virivky.amap.nav.RouteStep
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.math.roundToInt

/**
 * Výpočet trasy autom. S kľúčom TomTom podľa živej premávky, inak (alebo pri chybe)
 * cez verejný OSRM server s dátami OpenStreetMap.
 */
object Router {
    /** Posledná chyba TomTom (na zobrazenie v nastaveniach). */
    @Volatile
    var lastTomTomError: String? = null
        private set

    /** Trasa z [from] do [to], prípadne cez zastávku [via]. */
    fun route(from: GeoPoint, to: GeoPoint, heading: Float?, via: GeoPoint? = null): Route {
        val key = Settings.tomtomKey
        if (key != null) {
            try {
                return TomTom.route(key, from, to, heading, via).also { lastTomTomError = null }
            } catch (e: Exception) {
                lastTomTomError = if (e is HttpException && e.code == 403) "Kľúč TomTom nefunguje alebo je vyčerpaný denný limit."
                else "TomTom nedostupný, použitá trasa bez premávky."
            }
        }
        return osrm(from, to, heading, via)
    }

    fun osrm(from: GeoPoint, to: GeoPoint, heading: Float?, via: GeoPoint? = null): Route {
        val points = listOfNotNull(from, via, to)
        val url = StringBuilder("https://router.project-osrm.org/route/v1/driving/")
            .append(points.joinToString(";") { "${it.lon},${it.lat}" })
            .append("?overview=full&steps=true&geometries=polyline6&annotations=nodes&alternatives=false")
        if (heading != null) {
            // Pri prepočte trasy počas jazdy nech trasa začína v smere jazdy.
            url.append("&bearings=").append(((heading.roundToInt() % 360) + 360) % 360).append(",60")
                .append(";".repeat(points.size - 1))
        }
        return parse(JSONObject(Http.getString(url.toString())))
    }

    fun parse(json: JSONObject): Route {
        if (json.optString("code") != "Ok") {
            throw IOException(json.optString("message", json.optString("code", "Chyba trasy")))
        }
        val route = json.getJSONArray("routes").getJSONObject(0)
        val points = Polyline.decode(route.getString("geometry"), 6)
        if (points.size < 2) throw IOException("Prázdna trasa")

        val legs = route.getJSONArray("legs")
        val nodes = ArrayList<Long>()
        val steps = ArrayList<RouteStep>()
        var searchFrom = 0
        for (l in 0 until legs.length()) {
            val leg = legs.getJSONObject(l)
            leg.optJSONObject("annotation")?.optJSONArray("nodes")?.let { array ->
                val start = if (nodes.isEmpty()) 0 else 1
                for (i in start until array.length()) nodes.add(array.optLong(i))
            }
            val jsonSteps = leg.getJSONArray("steps")
            for (i in 0 until jsonSteps.length()) {
                val s = jsonSteps.getJSONObject(i)
                val m = s.getJSONObject("maneuver")
                // Za zastávkou začína nový úsek odchodom – jeho dĺžku a čas pripíšeme zastávke.
                if (l > 0 && i == 0 && m.optString("type") == "depart" && steps.isNotEmpty()) {
                    val stop = steps.removeAt(steps.size - 1)
                    steps.add(stop.with(distance = s.optDouble("distance", 0.0), duration = s.optDouble("duration", 0.0)))
                    continue
                }
                val type = m.optString("type").let { if (it == "arrive" && l < legs.length() - 1) "waypoint" else it }
                val loc = m.getJSONArray("location")
                val location = GeoPoint(loc.getDouble(1), loc.getDouble(0))
                // Manéver leží na jednom z bodov trasy – nájdeme ho od predchádzajúceho manévru ďalej.
                var index = searchFrom
                var best = Double.MAX_VALUE
                for (j in searchFrom until points.size) {
                    val d = Geo.distance(points[j], location)
                    if (d < best) {
                        best = d
                        index = j
                    }
                    if (d < 1.0) break
                }
                searchFrom = index
                steps.add(
                    RouteStep(
                        type = type,
                        modifier = m.optString("modifier").ifBlank { null },
                        exit = if (m.has("exit")) m.optInt("exit") else null,
                        name = s.optString("name"),
                        ref = s.optString("ref"),
                        destinations = s.optString("destinations"),
                        distance = s.optDouble("distance", 0.0),
                        duration = s.optDouble("duration", 0.0),
                        location = location,
                        startIndex = index,
                        lanes = lanes(s.optJSONArray("intersections")?.optJSONObject(0)?.optJSONArray("lanes")),
                        exitNumber = s.optString("exits").substringBefore(';'),
                    )
                )
            }
        }
        if (steps.isEmpty()) throw IOException("Prázdna trasa")
        val nodeIds = if (nodes.size == points.size) nodes.toLongArray() else null
        return Route(points, steps, route.optDouble("duration", 0.0), nodeIds)
    }

    private fun lanes(array: JSONArray?): List<LaneInfo> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { i ->
            val lane = array.getJSONObject(i)
            val indications = lane.optJSONArray("indications")
            LaneInfo(
                indications = if (indications == null) emptyList()
                else (0 until indications.length()).map { indications.getString(it) },
                valid = lane.optBoolean("valid", false),
            )
        }
    }
}
