package online.virivky.amap.net

import online.virivky.amap.geo.Geo
import online.virivky.amap.geo.GeoPoint
import online.virivky.amap.nav.Route
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt

/** Dopravná udalosť (TomTom): nehoda, práce na ceste, uzávierka, zápcha, … */
class Incident(
    val id: String,
    /** Kategória TomTom: 1 nehoda, 6 zápcha, 7 uzavretý pruh, 8 uzavretá cesta, 9 práce, … */
    val category: Int,
    /** Poloha; pri čiare v smere, ktorým udalosť pôsobí. */
    val points: List<GeoPoint>,
    val description: String,
    /** Zdržanie v sekundách (0 = neznáme). */
    val delay: Int,
    val road: String,
) {
    val icon: String
        get() = when (category) {
            1 -> "inc-accident"
            6 -> "inc-jam"
            8 -> "inc-closed"
            7, 9 -> "inc-works"
            else -> "inc-danger"
        }

    val label: String
        get() = when (category) {
            1 -> "Nehoda"
            2 -> "Hmla"
            4 -> "Dážď"
            5 -> "Poľadovica"
            6 -> "Zápcha"
            7 -> "Uzavretý pruh"
            8 -> "Uzavretá cesta"
            9 -> "Práce na ceste"
            10 -> "Silný vietor"
            11 -> "Záplavy"
            14 -> "Pokazené vozidlo"
            else -> "Nebezpečenstvo"
        }

    /** Do hlasového upozornenia: „je nehoda“, „sú práce na ceste“. */
    val spoken: String
        get() {
            val what = when (category) {
                9 -> "sú práce na ceste"
                11 -> "sú záplavy"
                else -> "je " + label.lowercase()
            }
            val minutes = (delay / 60.0).roundToInt()
            return if (minutes >= 2) "$what, zdržanie asi $minutes ${if (minutes <= 4) "minúty" else "minút"}" else what
        }
}

/** Udalosť na trase: vzdialenosť jej začiatku od začiatku trasy. */
class RouteIncident(val incident: Incident, val along: Double)

/** Dopravné udalosti z TomTom Traffic Incident Details API. */
object Incidents {
    private const val FIELDS =
        "{incidents{type,geometry{type,coordinates},properties{id,iconCategory,magnitudeOfDelay," +
            "events{description,code,iconCategory},from,to,length,delay,roadNumbers}}}"

    /** Najväčšia plocha jedného dotazu (TomTom povoľuje 10 000 km²). */
    const val MAX_AREA_KM2 = 9_500.0

    fun areaKm2(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): Double =
        (maxLat - minLat) * 111.0 * (maxLon - minLon) * 111.0 * cos(Math.toRadians((minLat + maxLat) / 2))

    fun fetch(key: String, minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): List<Incident> {
        fun url(language: String) = "https://api.tomtom.com/traffic/services/5/incidentDetails?key=" +
            URLEncoder.encode(key, "UTF-8") + "&bbox=$minLon,$minLat,$maxLon,$maxLat" +
            "&fields=" + URLEncoder.encode(FIELDS, "UTF-8") + "&language=$language&timeValidityFilter=present"
        val body = try {
            Http.getString(url("sk-SK"))
        } catch (e: HttpException) {
            // Jazyk nemusí byť podporovaný – potom po anglicky.
            if (e.code != 400) throw e
            Http.getString(url("en-GB"))
        }
        return parse(JSONObject(body))
    }

    fun parse(json: JSONObject): List<Incident> {
        val array = json.optJSONArray("incidents") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val p = o.optJSONObject("properties") ?: return@mapNotNull null
            val geometry = o.optJSONObject("geometry") ?: return@mapNotNull null
            val coordinates = geometry.optJSONArray("coordinates") ?: return@mapNotNull null
            val points = if (geometry.optString("type") == "Point") listOfNotNull(point(coordinates))
            else (0 until coordinates.length()).mapNotNull { point(coordinates.optJSONArray(it)) }
            if (points.isEmpty()) return@mapNotNull null
            val roads = p.optJSONArray("roadNumbers")
            Incident(
                id = p.optString("id").ifBlank { "${points.first().lat},${points.first().lon}" },
                category = p.optInt("iconCategory", 0),
                points = points,
                description = p.optJSONArray("events")?.optJSONObject(0)?.optString("description").orEmpty(),
                delay = p.optInt("delay", 0),
                road = if (roads == null) "" else (0 until roads.length()).joinToString(" ") { roads.optString(it) },
            )
        }
    }

    private fun point(a: JSONArray?): GeoPoint? {
        if (a == null || a.length() < 2) return null
        return GeoPoint(a.optDouble(1), a.optDouble(0))
    }

    /** Udalosti na trase pred nami (do 50 km), zoradené podľa vzdialenosti. */
    fun alongRoute(key: String, route: Route, traveled: Double): List<RouteIncident> {
        var ahead = 50_000.0
        var box: DoubleArray
        while (true) {
            box = bounds(route.slice(traveled, traveled + ahead))
            if (areaKm2(box[0], box[1], box[2], box[3]) <= MAX_AREA_KM2 || ahead < 5000) break
            ahead /= 2
        }
        val incidents = fetch(key, box[0], box[1], box[2], box[3])
        return incidents.mapNotNull { incident -> match(route, incident, traveled) }.sortedBy { it.along }
    }

    /** Udalosť leží na trase v smere jazdy? (Druhý smer diaľnice vedie tesne vedľa.) */
    private fun match(route: Route, incident: Incident, traveled: Double): RouteIncident? {
        val pts = incident.points
        var start: Double? = null
        for (k in listOf(0, pts.size / 2, pts.size - 1).distinct()) {
            val along = route.alongOf(pts[k], 40.0) ?: continue
            if (along < traveled - 100) continue
            if (pts.size >= 2) {
                val a = pts[min(k, pts.size - 2)]
                val b = pts[min(k, pts.size - 2) + 1]
                val routeBearing = Geo.bearing(route.pointAt(along), route.pointAt(along + 25))
                val diff = abs(((Geo.bearing(a, b) - routeBearing + 540) % 360) - 180)
                if (diff > 70) continue
            }
            // Začiatok udalosti je jej najbližší bod na trase pred nami.
            if (start == null || along < start) start = along
        }
        return start?.let { RouteIncident(incident, it) }
    }

    private fun bounds(points: List<GeoPoint>): DoubleArray {
        val margin = 0.01
        return doubleArrayOf(
            points.minOf { it.lat } - margin,
            points.minOf { it.lon } - margin,
            points.maxOf { it.lat } + margin,
            points.maxOf { it.lon } + margin,
        )
    }
}
