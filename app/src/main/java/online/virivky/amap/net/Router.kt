package online.virivky.amap.net

import online.virivky.amap.geo.GeoPoint
import online.virivky.amap.geo.Polyline
import online.virivky.amap.nav.Route
import online.virivky.amap.nav.RouteStep
import org.json.JSONObject
import java.io.IOException
import kotlin.math.roundToInt

/** Výpočet trasy autom cez verejný OSRM server (dáta OpenStreetMap). */
object Router {
    fun route(from: GeoPoint, to: GeoPoint, heading: Float?): Route {
        val url = StringBuilder("https://router.project-osrm.org/route/v1/driving/")
            .append(from.lon).append(',').append(from.lat).append(';')
            .append(to.lon).append(',').append(to.lat)
            .append("?overview=false&steps=true&geometries=polyline6&alternatives=false")
        if (heading != null) {
            // Pri prepočte trasy počas jazdy nech trasa začína v smere jazdy.
            url.append("&bearings=").append(((heading.roundToInt() % 360) + 360) % 360).append(",60;")
        }
        val json = JSONObject(Http.getString(url.toString()))
        if (json.optString("code") != "Ok") {
            throw IOException(json.optString("message", json.optString("code", "Chyba trasy")))
        }
        val route = json.getJSONArray("routes").getJSONObject(0)
        val legs = route.getJSONArray("legs")
        val points = ArrayList<GeoPoint>()
        val steps = ArrayList<RouteStep>()
        for (l in 0 until legs.length()) {
            val jsonSteps = legs.getJSONObject(l).getJSONArray("steps")
            for (i in 0 until jsonSteps.length()) {
                val s = jsonSteps.getJSONObject(i)
                val geometry = Polyline.decode(s.optString("geometry"), 6)
                val startIndex = if (points.isEmpty()) 0 else points.size - 1
                if (points.isEmpty()) points.addAll(geometry) else points.addAll(geometry.drop(1))
                val m = s.getJSONObject("maneuver")
                val location = m.getJSONArray("location")
                steps.add(
                    RouteStep(
                        type = m.optString("type"),
                        modifier = m.optString("modifier").ifBlank { null },
                        exit = if (m.has("exit")) m.optInt("exit") else null,
                        name = s.optString("name"),
                        ref = s.optString("ref"),
                        destinations = s.optString("destinations"),
                        distance = s.optDouble("distance", 0.0),
                        duration = s.optDouble("duration", 0.0),
                        location = GeoPoint(location.getDouble(1), location.getDouble(0)),
                        startIndex = startIndex,
                    )
                )
            }
        }
        if (points.size < 2 || steps.isEmpty()) throw IOException("Prázdna trasa")
        return Route(points, steps, route.optDouble("duration", 0.0))
    }
}
