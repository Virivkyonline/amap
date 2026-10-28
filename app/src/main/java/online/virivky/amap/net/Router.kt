package online.virivky.amap.net

import online.virivky.amap.data.RouteOptions
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
 * cez verejný OSRM server s dátami OpenStreetMap. Pešo po chodníkoch alebo priamo ([walk]).
 */
object Router {
    /** Posledná chyba TomTom (na zobrazenie v nastaveniach). */
    @Volatile
    var lastTomTomError: String? = null
        private set

    /** Trasa z [from] do [to], prípadne cez zastávky [vias]. */
    fun route(from: GeoPoint, to: GeoPoint, heading: Float?, vias: List<GeoPoint> = emptyList()): Route =
        routes(from, to, heading, vias).first()

    /**
     * Trasa a pri [alternatives] aj iné varianty (najlepšia je prvá). Rešpektuje nastavenia,
     * čomu sa vyhnúť (diaľnice, spoplatnené úseky, trajekty).
     */
    fun routes(
        from: GeoPoint,
        to: GeoPoint,
        heading: Float?,
        vias: List<GeoPoint> = emptyList(),
        alternatives: Boolean = false,
        options: RouteOptions = Settings.routeOptions,
    ): List<Route> {
        val key = Settings.tomtomKey
        if (key != null) {
            try {
                return TomTom.routes(key, from, to, heading, vias, alternatives, options).also { lastTomTomError = null }
            } catch (e: Exception) {
                lastTomTomError = if (e is HttpException && e.code == 403) "Kľúč TomTom nefunguje alebo je vyčerpaný denný limit."
                else "TomTom nedostupný, použitá trasa bez premávky."
            }
        }
        return osrm(from, to, heading, vias, alternatives, options)
    }

    fun osrm(
        from: GeoPoint,
        to: GeoPoint,
        heading: Float?,
        vias: List<GeoPoint> = emptyList(),
        alternatives: Boolean = false,
        options: RouteOptions = RouteOptions(),
    ): List<Route> {
        val points = listOf(from) + vias + to
        val url = StringBuilder("https://router.project-osrm.org/route/v1/driving/")
            .append(points.joinToString(";") { "${it.lon},${it.lat}" })
            .append("?overview=full&steps=true&geometries=polyline6&annotations=nodes")
            .append("&alternatives=").append(alternatives && vias.isEmpty())
        // Verejný server vie vylúčiť len jeden druh ciest naraz.
        when {
            options.avoidMotorways -> url.append("&exclude=motorway")
            options.avoidTolls -> url.append("&exclude=toll")
            options.avoidFerries -> url.append("&exclude=ferry")
        }
        if (heading != null) {
            // Pri prepočte trasy počas jazdy nech trasa začína v smere jazdy.
            url.append("&bearings=").append(((heading.roundToInt() % 360) + 360) % 360).append(",60")
                .append(";".repeat(points.size - 1))
        }
        return parseAll(JSONObject(Http.getString(url.toString())))
    }

    /**
     * Pešia trasa po chodníkoch a lesných cestách (OSRM s pešími pravidlami na serveri FOSSGIS).
     * Ak miesto nie je pri chodníku, posledný kúsok vedie priamo k nemu; bez siete či cesty celá priamo.
     */
    fun walk(from: GeoPoint, to: GeoPoint): Route {
        // Blízko (do 80 m) rovno – chodníky by mohli viesť okolo celého bloku.
        val straight = Geo.distance(from, to)
        if (straight < 80) return direct(from, to)
        val url = "https://routing.openstreetmap.de/routed-foot/route/v1/driving/" +
            "${from.lon},${from.lat};${to.lon},${to.lat}?overview=full&steps=true&geometries=polyline6"
        val route = try {
            parseAll(JSONObject(Http.getString(url))).first()
        } catch (e: Exception) {
            return direct(from, to)
        }
        // Začiatok či cieľ sa „prilepili“ na chodníky, ktoré sa spájajú až ďaleko: nezmyselná okľuka.
        if (route.distance > maxOf(straight * 2.5, straight + 400)) return direct(from, to)
        val end = route.points.last()
        val rest = Geo.distance(end, to)
        if (rest < 10) return route
        // Do cieľa mimo chodníka priamo: posledný bod trasy je samotné miesto.
        val points = route.points + to
        val steps = route.steps.toMutableList()
        val arrive = steps.removeAt(steps.size - 1)
        if (steps.isNotEmpty()) {
            val last = steps.removeAt(steps.size - 1)
            steps.add(last.with(last.distance + rest, last.duration + rest / WALK_SPEED))
        }
        steps.add(
            RouteStep(arrive.type, arrive.modifier, arrive.exit, arrive.name, arrive.ref, arrive.destinations, 0.0, 0.0, to, points.size - 1)
        )
        return Route(points, steps, route.duration + rest / WALK_SPEED)
    }

    /** Priamo vzdušnou čiarou: smer a vzdialenosť k miestu (napr. v lese bez internetu). */
    fun direct(from: GeoPoint, to: GeoPoint): Route {
        val distance = Geo.distance(from, to)
        val seconds = distance / WALK_SPEED
        val steps = listOf(
            RouteStep("depart", null, null, "", "", "", distance, seconds, from, 0),
            RouteStep("arrive", null, null, "", "", "", 0.0, 0.0, to, 1),
        )
        return Route(listOf(from, to), steps, seconds, direct = true)
    }

    /** Rýchlosť chôdze v teréne (m/s). */
    private const val WALK_SPEED = 1.2

    fun parse(json: JSONObject): Route = parseAll(json).first()

    fun parseAll(json: JSONObject): List<Route> {
        if (json.optString("code") != "Ok") {
            throw IOException(json.optString("message", json.optString("code", "Chyba trasy")))
        }
        val routes = json.getJSONArray("routes")
        return (0 until routes.length()).map { parseRoute(routes.getJSONObject(it)) }
    }

    private fun parseRoute(route: JSONObject): Route {
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
