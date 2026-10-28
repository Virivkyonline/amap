package online.virivky.amap.net

import online.virivky.amap.geo.GeoPoint
import online.virivky.amap.nav.Camera
import online.virivky.amap.nav.Route
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import kotlin.math.roundToInt

/**
 * Rýchlostné limity a radary pozdĺž trasy z OpenStreetMap (Overpass API).
 * Úseky trasy sa spárujú s cestami presne podľa OSM bodov, ktoré vráti OSRM.
 */
object RoadData {
    private val SERVERS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
    )
    private const val CHUNK = 4000

    /** [speedLimits] je null, ak sa limity nezisťovali (trasa ich už má z TomTom). */
    class Result(val speedLimits: IntArray?, val cameras: List<Camera>, val trafficSignals: DoubleArray)

    fun fetch(route: Route): Result? {
        val nodeIds = route.nodeIds ?: return fetchAround(route)
        val unique = nodeIds.filter { it > 0 }.distinct()
        if (unique.isEmpty()) return null

        // (a, b) -> limit pre jazdu z bodu a do bodu b
        val limits = HashMap<Pair<Long, Long>, Int>()
        val cameraCandidates = ArrayList<Pair<GeoPoint, Int?>>()
        val signalIds = HashSet<Long>()
        for (chunk in unique.chunked(CHUNK)) {
            val query = "[out:json][timeout:60];" +
                "node(id:${chunk.joinToString(",")})->.r;" +
                "way(bn.r)[highway][~\"^maxspeed\"~\".\"];out body;" +
                "node(around.r:40)[highway=speed_camera];out;" +
                "node.r[highway=traffic_signals];out tags;"
            val elements = JSONObject(post(query)).optJSONArray("elements") ?: continue
            for (i in 0 until elements.length()) {
                val e = elements.getJSONObject(i)
                val tags = e.optJSONObject("tags") ?: JSONObject()
                when (e.optString("type")) {
                    "way" -> {
                        val nodes = e.optJSONArray("nodes") ?: continue
                        val both = parseMaxspeed(tags.optString("maxspeed"))
                        val forward = parseMaxspeed(tags.optString("maxspeed:forward")) ?: both
                        val backward = parseMaxspeed(tags.optString("maxspeed:backward")) ?: both
                        for (k in 0 until nodes.length() - 1) {
                            val a = nodes.getLong(k)
                            val b = nodes.getLong(k + 1)
                            forward?.let { limits[a to b] = it }
                            backward?.let { limits[b to a] = it }
                        }
                    }
                    "node" -> when (tags.optString("highway")) {
                        "speed_camera" -> cameraCandidates.add(
                            GeoPoint(e.getDouble("lat"), e.getDouble("lon")) to parseMaxspeed(tags.optString("maxspeed"))
                        )
                        "traffic_signals" -> signalIds.add(e.getLong("id"))
                    }
                }
            }
        }

        val speedLimits = IntArray(route.points.size - 1) { i ->
            limits[nodeIds[i] to nodeIds[i + 1]] ?: 0
        }
        val cameras = cameraCandidates
            .mapNotNull { (point, limit) -> route.alongOf(point, 45.0)?.let { Camera(point, it, limit) } }
            .sortedBy { it.along }
            .fold(ArrayList<Camera>()) { list, camera ->
                if (list.isEmpty() || camera.along - list.last().along > 30) list.add(camera)
                list
            }
        val signals = nodeIds.indices
            .filter { nodeIds[it] in signalIds }
            .map { route.alongAtIndex(it) }
            .distinct()
            .sorted()
            .toDoubleArray()
        return Result(speedLimits, cameras, signals)
    }

    /**
     * Pre trasy bez OSM bodov (TomTom): radary a semafory v obdĺžnikoch pozdĺž trasy,
     * potom ponecháme len tie, ktoré ležia na trase.
     */
    private fun fetchAround(route: Route): Result? {
        if (route.points.size < 2) return null
        val boxes = ArrayList<String>()
        val chunk = 3000.0
        var from = 0.0
        while (from < route.distance) {
            val part = route.slice(from, from + chunk)
            val pad = 0.0006
            val s = part.minOf { it.lat } - pad
            val n = part.maxOf { it.lat } + pad
            val w = part.minOf { it.lon } - pad
            val e = part.maxOf { it.lon } + pad
            boxes.add("$s,$w,$n,$e")
            from += chunk
        }
        val query = StringBuilder("[out:json][timeout:60];(")
        for (box in boxes.take(400)) {
            query.append("node[highway=speed_camera]($box);node[highway=traffic_signals]($box);")
        }
        query.append(");out;")
        val elements = JSONObject(post(query.toString())).optJSONArray("elements") ?: return null
        val cameras = ArrayList<Camera>()
        val signals = ArrayList<Double>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: continue
            val point = GeoPoint(e.optDouble("lat"), e.optDouble("lon"))
            when (tags.optString("highway")) {
                "speed_camera" -> route.alongOf(point, 45.0)?.let {
                    cameras.add(Camera(point, it, parseMaxspeed(tags.optString("maxspeed"))))
                }
                "traffic_signals" -> route.alongOf(point, 12.0)?.let { signals.add(it) }
            }
        }
        val sortedCameras = cameras.sortedBy { it.along }.fold(ArrayList<Camera>()) { list, camera ->
            if (list.isEmpty() || camera.along - list.last().along > 30) list.add(camera)
            list
        }
        // Semafory na jednej križovatke rátame raz.
        val sortedSignals = signals.sorted().fold(ArrayList<Double>()) { list, along ->
            if (list.isEmpty() || along - list.last() > 40) list.add(along)
            list
        }
        return Result(null, sortedCameras, sortedSignals.toDoubleArray())
    }

    private fun post(query: String): String {
        val form = "data=" + URLEncoder.encode(query, "UTF-8")
        var last: IOException? = null
        for (server in SERVERS) {
            try {
                return Http.post(server, form)
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("Overpass nedostupný")
    }

    /** Prevod hodnoty maxspeed z OSM na km/h (null = neznáme alebo bez obmedzenia). */
    fun parseMaxspeed(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val value = raw.substringBefore(';').trim().lowercase()
        Regex("""^(\d+)\s*(mph)?$""").find(value)?.let { m ->
            val n = m.groupValues[1].toInt()
            return if (m.groupValues[2].isNotEmpty()) (n * 1.609).roundToInt() else n
        }
        Regex("""zone:?(\d+)""").find(value)?.let { return it.groupValues[1].toInt() }
        val country = value.substringBefore(':', "")
        return when (value.substringAfter(':', "")) {
            "urban" -> 50
            "rural" -> if (country == "de" || country == "at") 100 else 90
            "motorway" -> when (country) {
                "de" -> null
                "pl" -> 140
                else -> 130
            }
            "living_street" -> 20
            else -> null
        }
    }
}
