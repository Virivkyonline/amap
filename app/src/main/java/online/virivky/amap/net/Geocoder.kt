package online.virivky.amap.net

import online.virivky.amap.data.Place
import online.virivky.amap.geo.GeoPoint
import org.json.JSONArray
import java.net.URLEncoder

/** Vyhľadávanie adries a miest cez OpenStreetMap Nominatim. */
object Geocoder {
    private val COORDINATES = Regex("""^\s*(-?\d{1,2}(?:\.\d+)?)\s*[,;\s]\s*(-?\d{1,3}(?:\.\d+)?)\s*$""")

    fun search(query: String, near: GeoPoint?): List<Place> {
        COORDINATES.matchEntire(query)?.let { m ->
            val lat = m.groupValues[1].toDouble()
            val lon = m.groupValues[2].toDouble()
            if (lat in -90.0..90.0 && lon in -180.0..180.0) {
                return listOf(Place("$lat, $lon", "Súradnice", GeoPoint(lat, lon)))
            }
        }
        val url = StringBuilder("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=10")
            .append("&accept-language=sk&q=").append(URLEncoder.encode(query.trim(), "UTF-8"))
        if (near != null) {
            // Uprednostní výsledky v okolí (cca 150 km), ale neobmedzí ich.
            val d = 1.5
            url.append("&viewbox=").append(near.lon - d).append(',').append(near.lat + d)
                .append(',').append(near.lon + d).append(',').append(near.lat - d)
        }
        val array = JSONArray(Http.getString(url.toString()))
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val display = o.optString("display_name")
            val title = o.optString("name").ifBlank { display.substringBefore(',') }.trim()
            val address = display.removePrefix(title).trimStart(',', ' ')
            Place(title, address, GeoPoint(o.getString("lat").toDouble(), o.getString("lon").toDouble()))
        }
    }
}
