package online.virivky.amap.data

import online.virivky.amap.geo.GeoPoint
import org.json.JSONObject

/**
 * Miesto (cieľ, výsledok hľadania). [kind] = druh miesta („Supermarket“), [hours] = stav
 * otváracích hodín na zobrazenie a [open] = či je práve otvorené (null = nevieme).
 */
data class Place(
    val name: String,
    val address: String,
    val point: GeoPoint,
    val kind: String = "",
    val hours: String = "",
    val open: Boolean? = null,
) {
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
