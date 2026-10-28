package online.virivky.amap.util

import android.net.Uri
import online.virivky.amap.geo.GeoPoint

/** Rozbor odkazov typu geo:48.1,17.1?q=… (z kontaktov, kalendára, Google Asistenta). */
class GeoTarget(val point: GeoPoint?, val label: String?, val query: String?)

object GeoUri {
    private val LATLON = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*(?:\((.*)\))?\s*$""")

    fun parse(uri: Uri): GeoTarget? {
        if (uri.scheme != "geo") return null
        val ssp = uri.schemeSpecificPart ?: return null
        val coords = ssp.substringBefore('?').substringBefore(';')
        val q = if (ssp.contains("?q=")) Uri.decode(ssp.substringAfter("?q=").substringBefore('&')) else null

        q?.let { query ->
            LATLON.matchEntire(query)?.let { m ->
                val point = GeoPoint(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
                return GeoTarget(point, m.groupValues[3].ifBlank { null }, null)
            }
            if (query.isNotBlank()) return GeoTarget(null, null, query)
        }
        LATLON.matchEntire(coords)?.let { m ->
            val point = GeoPoint(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
            if (point.lat != 0.0 || point.lon != 0.0) return GeoTarget(point, null, null)
        }
        return null
    }
}
