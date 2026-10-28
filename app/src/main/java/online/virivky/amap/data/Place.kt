package online.virivky.amap.data

import online.virivky.amap.geo.GeoPoint
import org.json.JSONObject

data class Place(val name: String, val address: String, val point: GeoPoint) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("address", address)
        .put("lat", point.lat)
        .put("lon", point.lon)

    companion object {
        fun fromJson(o: JSONObject) = Place(
            o.optString("name"),
            o.optString("address"),
            GeoPoint(o.getDouble("lat"), o.getDouble("lon")),
        )
    }
}
