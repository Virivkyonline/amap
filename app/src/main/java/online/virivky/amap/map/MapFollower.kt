package online.virivky.amap.map

import online.virivky.amap.geo.GeoPoint
import online.virivky.amap.location.LocationTracker
import online.virivky.amap.nav.NavigationController
import online.virivky.amap.nav.NavigationController.Phase
import online.virivky.amap.nav.Route
import kotlin.math.max

/**
 * Prenáša polohu a stav navigácie do mapy: sledovanie polohy, otáčanie v smere jazdy,
 * zobrazenie celej trasy v náhľade. Spoločné pre auto aj telefón.
 */
class MapFollower(private val renderer: MapRenderer) {
    /** Či mapa sleduje polohu (vypne sa, keď používateľ mapou posunie). */
    var follow = true
    private var fittedRoute: Route? = null
    private var wasNavigating = false

    fun sync(fitPaddingPx: Float) {
        val nav = NavigationController
        val location = LocationTracker.last
        val navigating = nav.phase == Phase.NAVIGATING || nav.phase == Phase.ARRIVED
        val route = nav.route

        renderer.myLocation = location?.let { GeoPoint(it) }
        renderer.myAccuracy = location?.accuracy ?: 0f
        renderer.myHeading = LocationTracker.heading
        renderer.destination = nav.destination?.point
        renderer.route = route?.points
        renderer.routeStartIndex = 0
        renderer.routeHead = null
        renderer.cameras = route?.cameras?.map { it.point } ?: emptyList()
        renderer.speedLimit = if (navigating) nav.speedLimit else null
        renderer.speedKmh = if (navigating) nav.speedKmh else null
        renderer.overSpeed = navigating && nav.overSpeed
        renderer.cameraDistance = if (navigating) nav.cameraDistance else null

        if (navigating) {
            if (!wasNavigating) {
                follow = true
                renderer.zoom = 16.5
            }
            val p = nav.progress
            if (p != null && route != null && p.offRouteDistance < 30) {
                // Prichytenie k trase, aby šípka nepreskakovala vedľa cesty.
                renderer.routeStartIndex = p.segmentIndex
                renderer.routeHead = p.snapped
                renderer.myLocation = p.snapped
                if (renderer.myHeading == null) renderer.myHeading = p.segmentBearing
            }
            if (follow) {
                renderer.myLocation?.let { renderer.center = it }
                renderer.myHeading?.let { renderer.bearing = it }
                renderer.anchorYFraction = 0.72f
            }
        } else {
            if (wasNavigating) follow = true
            renderer.anchorYFraction = 0.5f
            if (nav.phase == Phase.PREVIEW && route != null && route !== fittedRoute) {
                if (renderer.fit(route.points + listOfNotNull(renderer.myLocation), fitPaddingPx)) {
                    fittedRoute = route
                    follow = false
                }
            } else if (follow) {
                renderer.bearing = 0f
                renderer.myLocation?.let { renderer.center = it }
            }
        }
        if (nav.phase == Phase.IDLE) fittedRoute = null
        wasNavigating = navigating
    }

    /** Návrat na aktuálnu polohu. */
    fun recenter() {
        follow = true
        val navigating = NavigationController.phase == Phase.NAVIGATING
        renderer.zoom = if (navigating) 16.5 else max(renderer.zoom, 15.0)
        if (!navigating) renderer.bearing = 0f
    }
}
