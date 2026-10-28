package online.virivky.amap.data

import android.os.SystemClock
import online.virivky.amap.nav.Route
import online.virivky.amap.net.Incident
import online.virivky.amap.net.Incidents
import online.virivky.amap.net.RouteIncident
import online.virivky.amap.util.Bg
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Dopravné udalosti (TomTom): vo výreze mapy a na trase pred nami.
 * Len s kľúčom TomTom a zapnutou premávkou.
 */
object TrafficEvents {
    fun interface Listener {
        fun onTrafficEventsChanged()
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private var areaRequest = 0
    private var routeRequest = 0
    private var areaBox: DoubleArray? = null
    private var areaAt = 0L

    /** Udalosti vo výreze mapy. */
    var area: List<Incident> = emptyList()
        private set

    /** Udalosti na trase pred nami a trasa, ku ktorej patria. */
    var ahead: List<RouteIncident> = emptyList()
        private set
    var aheadRoute: Route? = null
        private set

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        listeners.forEach { it.onTrafficEventsChanged() }
    }

    /** Výrez mapy sa zmenil – dotiahne udalosti, ak nemáme čerstvé pre túto oblasť. */
    fun requestArea(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double) {
        val key = Settings.trafficLayerKey
        if (key == null) {
            if (area.isNotEmpty()) {
                area = emptyList()
                areaBox = null
                notifyChanged()
            }
            return
        }
        val now = SystemClock.elapsedRealtime()
        val box = areaBox
        if (box != null && now - areaAt < 3 * 60_000 &&
            minLat >= box[0] && minLon >= box[1] && maxLat <= box[2] && maxLon <= box[3]
        ) {
            return
        }
        // O tretinu väčší výrez, aby malý posun mapy nevyžadoval nový dotaz.
        val dLat = (maxLat - minLat) / 3
        val dLon = (maxLon - minLon) / 3
        val wanted = doubleArrayOf(minLat - dLat, minLon - dLon, maxLat + dLat, maxLon + dLon)
        val query = if (Incidents.areaKm2(wanted[0], wanted[1], wanted[2], wanted[3]) <= Incidents.MAX_AREA_KM2) wanted
        else doubleArrayOf(minLat, minLon, maxLat, maxLon)
        if (Incidents.areaKm2(query[0], query[1], query[2], query[3]) > Incidents.MAX_AREA_KM2) return
        val id = ++areaRequest
        areaBox = query
        areaAt = now
        Bg.run({ Incidents.fetch(key, query[0], query[1], query[2], query[3]) }) { result ->
            if (id != areaRequest) return@run
            result.onSuccess {
                area = it
                notifyChanged()
            }.onFailure {
                // Pri chybe skúsime znova pri ďalšom posune mapy.
                areaBox = null
            }
        }
    }

    /** Udalosti na trase pred nami (po novej trase a pri pravidelnom prepočte). */
    fun refreshRoute(route: Route?, traveled: Double) {
        val key = Settings.trafficLayerKey
        val id = ++routeRequest
        if (route == null || key == null) {
            if (ahead.isNotEmpty() || aheadRoute != null) {
                ahead = emptyList()
                aheadRoute = null
                notifyChanged()
            }
            return
        }
        Bg.run({ Incidents.alongRoute(key, route, traveled) }) { result ->
            if (id != routeRequest) return@run
            result.onSuccess {
                ahead = it
                aheadRoute = route
                notifyChanged()
            }
        }
    }
}
