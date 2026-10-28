package online.virivky.amap.net

import online.virivky.amap.geo.Geo
import online.virivky.amap.geo.GeoPoint
import online.virivky.amap.nav.LaneInfo
import online.virivky.amap.nav.Route
import online.virivky.amap.nav.RouteStep
import online.virivky.amap.nav.TrafficSpan
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.roundToInt

/** Trasa podľa živej premávky cez TomTom Routing API (vyžaduje vlastný kľúč). */
object TomTom {
    private const val BASE = "https://api.tomtom.com/routing/1/calculateRoute/"

    fun route(key: String, from: GeoPoint, to: GeoPoint, heading: Float?): Route =
        try {
            parse(JSONObject(Http.getString(url(key, from, to, heading, extended = true))))
        } catch (e: HttpException) {
            // Staršie verzie API nepoznajú limity a pruhy – skúsime len premávku.
            if (e.code != 400) throw e
            parse(JSONObject(Http.getString(url(key, from, to, heading, extended = false))))
        }

    /** Overí kľúč krátkou požiadavkou. Vráti null, ak funguje, inak popis chyby. */
    fun check(key: String): String? = try {
        Http.getString(url(key, GeoPoint(48.1486, 17.1077), GeoPoint(48.1440, 17.1130), null, extended = false))
        null
    } catch (e: HttpException) {
        when (e.code) {
            403 -> "Kľúč nie je platný alebo je vyčerpaný denný limit (HTTP 403)."
            else -> "TomTom vrátil chybu HTTP ${e.code}."
        }
    } catch (e: Exception) {
        "Nepodarilo sa spojiť s TomTom. Skontrolujte internet."
    }

    private fun url(key: String, from: GeoPoint, to: GeoPoint, heading: Float?, extended: Boolean): String {
        val sb = StringBuilder(BASE)
            .append(from.lat).append(',').append(from.lon).append(':')
            .append(to.lat).append(',').append(to.lon)
            .append("/json?key=").append(URLEncoder.encode(key, "UTF-8"))
            .append("&traffic=true&travelMode=car&routeType=fastest&routeRepresentation=polyline")
            .append("&instructionsType=coded&computeTravelTimeFor=all&sectionType=traffic")
        if (extended) sb.append("&sectionType=speedLimit&sectionType=lanes")
        if (heading != null) sb.append("&vehicleHeading=").append(((heading.roundToInt() % 360) + 360) % 360)
        return sb.toString()
    }

    fun parse(json: JSONObject): Route {
        val route = json.getJSONArray("routes").getJSONObject(0)
        val summary = route.getJSONObject("summary")
        val totalLength = summary.optDouble("lengthInMeters", 0.0)
        val totalTime = summary.optDouble("travelTimeInSeconds", 0.0)

        val points = ArrayList<GeoPoint>()
        val legs = route.getJSONArray("legs")
        for (l in 0 until legs.length()) {
            val array = legs.getJSONObject(l).getJSONArray("points")
            for (i in 0 until array.length()) {
                val p = array.getJSONObject(i)
                // Indexy bodov v pokynoch a úsekoch zodpovedajú tomuto zoznamu, preto nič nevynechávame.
                points.add(GeoPoint(p.getDouble("latitude"), p.getDouble("longitude")))
            }
        }
        if (points.size < 2) throw IOException("Prázdna trasa")

        val laneSections = ArrayList<Pair<Int, List<LaneInfo>>>()
        val traffic = ArrayList<TrafficSpan>()
        var speedLimits: IntArray? = null
        val sections = route.optJSONArray("sections") ?: JSONArray()
        for (i in 0 until sections.length()) {
            val section = sections.getJSONObject(i)
            val start = section.optInt("startPointIndex", 0)
            val end = section.optInt("endPointIndex", start)
            when (section.optString("sectionType")) {
                "TRAFFIC" -> {
                    val level = when {
                        section.optString("simpleCategory") == "ROAD_CLOSURE" -> 3
                        else -> when (section.optInt("magnitudeOfDelay", 0)) {
                            1 -> 1
                            2 -> 2
                            3, 4 -> 3
                            else -> if (section.optDouble("delayInSeconds", 0.0) > 0) 1 else 0
                        }
                    }
                    if (level > 0) traffic.add(TrafficSpan(start, end, level))
                }
                "SPEED_LIMIT" -> {
                    val limit = section.optDouble("maxSpeedLimitInKmh", 0.0).roundToInt()
                    if (limit > 0) {
                        val limits = speedLimits ?: IntArray(points.size - 1).also { speedLimits = it }
                        for (k in start.coerceAtLeast(0) until end.coerceAtMost(limits.size)) limits[k] = limit
                    }
                }
                "LANES" -> lanes(section)?.let { laneSections.add(end to it) }
            }
        }

        val instructions = route.optJSONObject("guidance")?.optJSONArray("instructions") ?: JSONArray()
        val steps = ArrayList<RouteStep>()
        val usedLanes = HashSet<Int>()
        for (i in 0 until instructions.length()) {
            val ins = instructions.getJSONObject(i)
            val next = instructions.optJSONObject(i + 1)
            val offset = ins.optDouble("routeOffsetInMeters", 0.0)
            val time = ins.optDouble("travelTimeInSeconds", 0.0)
            val point = ins.optJSONObject("point")
            val location = if (point != null) GeoPoint(point.optDouble("latitude"), point.optDouble("longitude")) else points.first()
            var index = ins.optInt("pointIndex", -1)
            if (index !in points.indices) index = nearestIndex(points, location)
            val (type, modifier) = maneuver(ins.optString("maneuver"), ins.optDouble("turnAngleInDecimalDegrees", 0.0))
            val roadNumbers = ins.optJSONArray("roadNumbers")
            // Pruhy patria k manévru, ku ktorému úsek s pruhmi vedie (každý úsek len raz).
            val laneIndex = if (type == "depart" || type == "arrive") -1
            else laneSections.indices.firstOrNull { it !in usedLanes && laneSections[it].first in (index - 3)..(index + 1) } ?: -1
            if (laneIndex >= 0) usedLanes.add(laneIndex)
            steps.add(
                RouteStep(
                    type = type,
                    modifier = modifier,
                    exit = ins.optInt("roundaboutExitNumber", 0).takeIf { it > 0 && type == "roundabout" },
                    name = ins.optString("street"),
                    ref = if (roadNumbers == null) "" else (0 until roadNumbers.length()).joinToString(" ") { roadNumbers.getString(it) },
                    destinations = ins.optString("signpostText"),
                    distance = ((next?.optDouble("routeOffsetInMeters", totalLength) ?: totalLength) - offset).coerceAtLeast(0.0),
                    duration = ((next?.optDouble("travelTimeInSeconds", totalTime) ?: totalTime) - time).coerceAtLeast(0.0),
                    location = location,
                    startIndex = index,
                    lanes = if (laneIndex >= 0) laneSections[laneIndex].second else emptyList(),
                )
            )
        }
        steps.sortBy { it.startIndex }
        if (steps.isEmpty() || steps.first().type != "depart") {
            steps.add(0, RouteStep("depart", null, null, "", "", "", 0.0, 0.0, points.first(), 0))
        }
        if (steps.last().type != "arrive") {
            steps.add(RouteStep("arrive", null, null, "", "", "", 0.0, 0.0, points.last(), points.size - 1))
        }

        return Route(
            points = points,
            steps = steps,
            duration = totalTime,
            traffic = traffic,
            trafficDelay = summary.optDouble("trafficDelayInSeconds", 0.0),
            live = true,
        ).also { r -> speedLimits?.let { r.speedLimits = it } }
    }

    /** Prevod manévru TomTom na typ a smer v štýle OSRM (pre spoločné texty a ikony). */
    private fun maneuver(code: String, angle: Double): Pair<String, String?> {
        val side = if (angle < 0) "left" else "right"
        return when (code) {
            "DEPART" -> "depart" to null
            "ARRIVE", "WAYPOINT_REACHED" -> "arrive" to null
            "ARRIVE_LEFT", "WAYPOINT_LEFT" -> "arrive" to "left"
            "ARRIVE_RIGHT", "WAYPOINT_RIGHT" -> "arrive" to "right"
            "STRAIGHT", "FOLLOW" -> "continue" to "straight"
            "KEEP_RIGHT" -> "fork" to "slight right"
            "KEEP_LEFT" -> "fork" to "slight left"
            "BEAR_RIGHT" -> "turn" to "slight right"
            "BEAR_LEFT" -> "turn" to "slight left"
            "TURN_RIGHT" -> "turn" to "right"
            "TURN_LEFT" -> "turn" to "left"
            "SHARP_RIGHT" -> "turn" to "sharp right"
            "SHARP_LEFT" -> "turn" to "sharp left"
            "MAKE_UTURN", "TRY_MAKE_UTURN" -> "turn" to "uturn"
            "ENTER_MOTORWAY", "ENTER_FREEWAY", "ENTER_HIGHWAY", "ENTRANCE_RAMP" -> "on ramp" to "slight $side"
            "TAKE_EXIT", "MOTORWAY_EXIT_RIGHT" -> "off ramp" to "slight right"
            "MOTORWAY_EXIT_LEFT" -> "off ramp" to "slight left"
            "ROUNDABOUT_CROSS" -> "roundabout" to "straight"
            "ROUNDABOUT_RIGHT" -> "roundabout" to "right"
            "ROUNDABOUT_LEFT" -> "roundabout" to "left"
            "ROUNDABOUT_BACK" -> "roundabout" to "uturn"
            "SWITCH_PARALLEL_ROAD", "SWITCH_MAIN_ROAD" -> "continue" to "slight $side"
            else -> {
                val a = abs(angle)
                when {
                    a < 20 -> "continue" to "straight"
                    a < 60 -> "turn" to "slight $side"
                    a < 135 -> "turn" to side
                    a < 170 -> "turn" to "sharp $side"
                    else -> "turn" to "uturn"
                }
            }
        }
    }

    private fun lanes(section: JSONObject): List<LaneInfo>? {
        val array = section.optJSONArray("lanes") ?: return null
        return (0 until array.length()).map { i ->
            val lane = array.getJSONObject(i)
            val directions = lane.optJSONArray("directions")
            LaneInfo(
                indications = if (directions == null) emptyList()
                else (0 until directions.length()).map { direction(directions.getString(it)) },
                valid = lane.has("follow"),
            )
        }.takeIf { it.isNotEmpty() }
    }

    private fun direction(code: String): String {
        val d = code.lowercase().replace('_', ' ')
        return when {
            d.contains("u turn") -> "uturn"
            d == "straight" -> "straight"
            else -> d
        }
    }

    private fun nearestIndex(points: List<GeoPoint>, p: GeoPoint): Int {
        var best = 0
        var bestDistance = Double.MAX_VALUE
        for (i in points.indices) {
            val d = Geo.distance(points[i], p)
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best
    }
}
