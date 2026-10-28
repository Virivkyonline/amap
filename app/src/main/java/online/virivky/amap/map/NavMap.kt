package online.virivky.amap.map

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import online.virivky.amap.data.Settings
import online.virivky.amap.geo.Geo
import online.virivky.amap.geo.GeoPoint
import online.virivky.amap.location.LocationTracker
import online.virivky.amap.nav.NavigationController
import online.virivky.amap.nav.NavigationController.Phase
import online.virivky.amap.nav.Route
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.ln

/**
 * Ovládanie vektorovej mapy pre telefón aj auto: trasa, šípka manévru, poloha
 * a kamera, ktorá počas navigácie sleduje auto v smere jazdy.
 */
class NavMap(private val context: Context, val map: MapLibreMap, dark: Boolean) {
    /** Či mapa sleduje polohu (vypne sa, keď používateľ mapou pohne). */
    var follow = true
        private set
    var onFollowChanged: (() -> Unit)? = null

    private var style: Style? = null
    private var dark = dark
    private val viewport = Rect()
    private var width = 0
    private var height = 0
    private var navZoom = 17.0
    /** Kedy používateľ naposledy sám menil priblíženie (vtedy nemeníme zoom podľa rýchlosti). */
    private var userZoomAt = 0L
    private var wasNavigating = false
    private var fittedRoute: Route? = null
    private var shownRoute: Route? = null
    private var shownSegment = -1
    private var shownArrowKey = ""
    private var shownCameras: Any? = null
    private var shownSignals: Any? = null
    private var shownDestination: GeoPoint? = null
    private var trafficKey = Settings.trafficLayerKey
    private val settingsListener: () -> Unit = {
        val key = Settings.trafficLayerKey
        if (key != trafficKey) {
            trafficKey = key
            loadStyle()
        }
    }

    init {
        map.uiSettings.isLogoEnabled = false
        map.uiSettings.isAttributionEnabled = false
        map.uiSettings.isCompassEnabled = false
        map.uiSettings.isRotateGesturesEnabled = true
        map.uiSettings.isTiltGesturesEnabled = false
        map.setMinZoomPreference(3.0)
        map.setMaxZoomPreference(19.5)
        map.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) setFollow(false)
        }
        LocationTracker.last?.let {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 15.0))
        } ?: map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(48.1486, 17.1077), 12.0))
        Settings.addListener(settingsListener)
        loadStyle()
    }

    /** Uvoľní mapu (prestane počúvať zmeny nastavení). */
    fun dispose() {
        Settings.removeListener(settingsListener)
    }

    private fun loadStyle() {
        style = null
        val builder = Style.Builder().fromJson(MapStyle.json(dark, trafficKey))
        MapIcons.all(context).forEach { (name, bitmap) -> builder.withImage(name, bitmap) }
        map.setStyle(builder) { loaded ->
            style = loaded
            shownRoute = null
            shownSegment = -1
            shownArrowKey = ""
            shownCameras = null
            shownSignals = null
            shownDestination = null
            update()
        }
    }

    fun setDark(dark: Boolean) {
        if (this.dark == dark) return
        this.dark = dark
        loadStyle()
    }

    private fun setFollow(value: Boolean) {
        if (follow == value) return
        follow = value
        onFollowChanged?.invoke()
    }

    /** Voľná časť plochy (bez panelov) a celková veľkosť mapy v pixeloch. */
    fun setViewport(area: Rect, width: Int, height: Int) {
        if (area == viewport && this.width == width && this.height == height) return
        viewport.set(area)
        this.width = width
        this.height = height
        update(animate = false)
    }

    fun stopFollowing() = setFollow(false)

    fun zoomIn() = zoomBy(1.0)

    fun zoomOut() = zoomBy(-1.0)

    private fun zoomBy(delta: Double) {
        userZoomAt = android.os.SystemClock.elapsedRealtime()
        if (isNavigating()) navZoom = (navZoom + delta).coerceIn(12.0, 19.0)
        map.animateCamera(CameraUpdateFactory.zoomBy(delta), 250)
    }

    fun recenter() {
        setFollow(true)
        userZoomAt = 0L
        if (isNavigating()) navZoom = 17.0
        else if (map.cameraPosition.zoom < 15) map.moveCamera(CameraUpdateFactory.zoomTo(15.0))
        update()
    }

    /** Posun prstom na obrazovke auta (kladné dx = prst sa pohol doľava). */
    fun scrollBy(dx: Float, dy: Float) {
        setFollow(false)
        map.scrollBy(-dx, -dy, 0)
    }

    /** Priblíženie z obrazovky auta. */
    fun scaleBy(focusX: Float, focusY: Float, factor: Float) {
        if (factor <= 0f) return
        val delta = ln(factor.toDouble()) / ln(2.0)
        userZoomAt = android.os.SystemClock.elapsedRealtime()
        if (isNavigating()) navZoom = (navZoom + delta).coerceIn(12.0, 19.0)
        if (follow || focusX < 0 || focusY < 0) map.moveCamera(CameraUpdateFactory.zoomBy(delta))
        else map.moveCamera(CameraUpdateFactory.zoomBy(delta, Point(focusX.toInt(), focusY.toInt())))
    }

    private fun isNavigating() =
        NavigationController.phase == Phase.NAVIGATING || NavigationController.phase == Phase.ARRIVED

    /** Prekreslí trasu, značky a posunie kameru podľa aktuálneho stavu. */
    fun update(animate: Boolean = true) {
        val s = style ?: return
        val nav = NavigationController
        val navigating = isNavigating()
        val route = nav.route
        val progress = nav.progress
        val location = LocationTracker.last
        val snapped = progress?.takeIf { navigating && it.offRouteDistance < 30 }

        // Trasa – počas jazdy len zostávajúca časť od auta.
        val segment = if (navigating) progress?.segmentIndex ?: 0 else -1
        if (route !== shownRoute || segment != shownSegment || snapped != null) {
            s.getSourceAs<GeoJsonSource>("route")?.setGeoJson(
                if (route == null) EMPTY else routeFeatures(route, snapped?.segmentIndex ?: 0, snapped?.snapped)
            )
            shownRoute = route
            shownSegment = segment
            // Svetlý žiar na prvých metroch trasy pred autom.
            s.getSourceAs<GeoJsonSource>("route-head")?.setGeoJson(
                if (route != null && progress != null && navigating) {
                    lineString(route.slice(progress.traveled, progress.traveled + 70))
                } else {
                    EMPTY
                }
            )
        }

        // Šípka najbližšieho manévru.
        val step = if (navigating && route != null && progress != null) route.steps[progress.nextStepIndex] else null
        val showArrow = step != null && progress!!.distanceToNextStep < 1000 &&
            step.type !in setOf("arrive", "depart") && !(step.modifier == "straight" && step.type != "roundabout")
        val arrowKey = if (showArrow) "${route.hashCode()}-${progress!!.nextStepIndex}" else ""
        if (arrowKey != shownArrowKey) {
            if (showArrow) {
                val along = route!!.stepAlong(progress!!.nextStepIndex)
                val shaft = route.slice(along - 50, along + 35)
                s.getSourceAs<GeoJsonSource>("arrow")?.setGeoJson(lineString(shaft))
                val end = shaft.last()
                val before = shaft.getOrNull(shaft.size - 2) ?: end
                val bearing = if (Geo.distance(before, end) > 0.1) Geo.bearing(before, end) else 0.0
                s.getSourceAs<GeoJsonSource>("arrowhead")?.setGeoJson(point(end, """"bearing":$bearing"""))
            } else {
                s.getSourceAs<GeoJsonSource>("arrow")?.setGeoJson(EMPTY)
                s.getSourceAs<GeoJsonSource>("arrowhead")?.setGeoJson(EMPTY)
            }
            shownArrowKey = arrowKey
        }

        val cameras = route?.cameras
        if (cameras !== shownCameras) {
            s.getSourceAs<GeoJsonSource>("cameras")?.setGeoJson(
                if (cameras.isNullOrEmpty()) EMPTY
                else """{"type":"FeatureCollection","features":[${cameras.joinToString(",") { point(it.point) }}]}"""
            )
            shownCameras = cameras
        }

        val signals = route?.trafficSignals
        if (signals !== shownSignals) {
            s.getSourceAs<GeoJsonSource>("signals")?.setGeoJson(
                if (route == null || signals == null || signals.isEmpty()) EMPTY
                else """{"type":"FeatureCollection","features":[${signals.joinToString(",") { point(route.pointAt(it)) }}]}"""
            )
            shownSignals = signals
        }

        val destination = nav.destination?.point
        if (destination != shownDestination) {
            s.getSourceAs<GeoJsonSource>("destination")?.setGeoJson(destination?.let { point(it) } ?: EMPTY)
            shownDestination = destination
        }

        // Moja poloha: počas navigácie auto s kompasom, inak bodka alebo auto podľa smeru.
        val heading = LocationTracker.heading ?: snapped?.segmentBearing
        val me = snapped?.snapped ?: location?.let { GeoPoint(it) }
        s.getSourceAs<GeoJsonSource>("destination-line")?.setGeoJson(
            if (navigating && me != null && destination != null) lineString(listOf(me, destination)) else EMPTY
        )
        s.getSourceAs<GeoJsonSource>("me")?.setGeoJson(
            if (me == null) EMPTY
            else point(
                me,
                """"bearing":${heading ?: 0f},"icon":"${if (heading != null || navigating) "car" else "dot"}","compass":${if (navigating) 1 else 0}"""
            )
        )

        updateCamera(navigating, route, me, heading, animate)
        wasNavigating = navigating
    }

    private fun updateCamera(navigating: Boolean, route: Route?, me: GeoPoint?, heading: Float?, animate: Boolean) {
        if (width == 0 || height == 0) return
        if (navigating && !wasNavigating) {
            setFollow(true)
            navZoom = 17.0
        }
        if (!navigating && wasNavigating) setFollow(true)
        val area = if (viewport.isEmpty) Rect(0, 0, width, height) else viewport

        if (!navigating && NavigationController.phase == Phase.PREVIEW && route != null && route !== fittedRoute) {
            val bounds = LatLngBounds.Builder()
            route.points.forEach { bounds.include(LatLng(it.lat, it.lon)) }
            me?.let { bounds.include(LatLng(it.lat, it.lon)) }
            val pad = (40 * context.resources.displayMetrics.density).toInt()
            try {
                map.easeCamera(
                    CameraUpdateFactory.newLatLngBounds(
                        bounds.build(), 0.0, 0.0,
                        area.left + pad, area.top + pad, width - area.right + pad, height - area.bottom + pad,
                    ),
                    600,
                )
                fittedRoute = route
                setFollow(false)
            } catch (e: Exception) {
                // Trasa s jedným bodom nemá ohraničenie.
            }
            return
        }
        if (NavigationController.phase == Phase.IDLE) fittedRoute = null
        if (!follow || me == null) return

        val position = if (navigating) {
            // Vo vyššej rýchlosti viac oddialiť a nakloniť, aby bolo vidieť ďalej dopredu.
            val speed = (NavigationController.speedKmh ?: 0).toDouble()
            if (android.os.SystemClock.elapsedRealtime() - userZoomAt > 20_000) {
                var zoom = (17.3 - (speed - 40).coerceAtLeast(0.0) / 55).coerceIn(15.6, 17.3)
                // Pred odbočkou či zjazdom postupne priblížiť, aby bola križovatka dobre vidieť.
                val p = NavigationController.progress
                val step = if (p != null && route != null) route.steps[p.nextStepIndex] else null
                if (p != null && step != null && step.type != "arrive" && step.modifier != "straight") {
                    val k = ((700 - p.distanceToNextStep) / 600).coerceIn(0.0, 1.0)
                    zoom += (17.8 - zoom).coerceAtLeast(0.0) * k
                }
                navZoom = zoom
            }
            val tilt = (40 + (speed - 30).coerceAtLeast(0.0) * 0.3).coerceIn(40.0, 58.0)
            // Auto v dolnej časti voľnej plochy, mapa otočená v smere jazdy.
            CameraPosition.Builder()
                .target(LatLng(me.lat, me.lon))
                .zoom(navZoom)
                .bearing((heading ?: map.cameraPosition.bearing.toFloat()).toDouble())
                .tilt(tilt)
                .padding(
                    area.left.toDouble(),
                    area.top + area.height() * 0.42,
                    (width - area.right).toDouble(),
                    (height - area.bottom).toDouble(),
                )
                .build()
        } else {
            CameraPosition.Builder()
                .target(LatLng(me.lat, me.lon))
                .zoom(map.cameraPosition.zoom.coerceAtLeast(13.0))
                .bearing(0.0)
                .tilt(0.0)
                .padding(
                    area.left.toDouble(), area.top.toDouble(),
                    (width - area.right).toDouble(), (height - area.bottom).toDouble(),
                )
                .build()
        }
        if (animate) map.easeCamera(CameraUpdateFactory.newCameraPosition(position), 900, false)
        else map.moveCamera(CameraUpdateFactory.newCameraPosition(position))
    }

    /** Trasa rozdelená na úseky podľa premávky (vlastnosť „level“ určuje farbu). */
    private fun routeFeatures(route: Route, fromSegment: Int, head: GeoPoint?): String {
        val levels = route.segmentTraffic
        val features = ArrayList<String>()
        var current = ArrayList<GeoPoint>()
        var level = -1
        for (i in fromSegment until route.points.size - 1) {
            val segmentLevel = levels.getOrElse(i) { 0 }
            if (segmentLevel != level) {
                if (current.size >= 2) features.add(feature(current, level))
                current = ArrayList()
                current.add(if (i == fromSegment && head != null) head else route.points[i])
                level = segmentLevel
            } else if (current.isEmpty()) {
                current.add(route.points[i])
            }
            current.add(route.points[i + 1])
        }
        if (current.size >= 2) features.add(feature(current, level))
        return """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""
    }

    private fun feature(points: List<GeoPoint>, level: Int) =
        """{"type":"Feature","properties":{"level":$level},"geometry":{"type":"LineString","coordinates":[${points.joinToString(",") { coord(it) }}]}}"""

    companion object {
        private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""

        private fun round(v: Double) = Math.round(v * 1e7) / 1e7

        private fun coord(p: GeoPoint) = "[${round(p.lon)},${round(p.lat)}]"

        fun lineString(points: List<GeoPoint>): String =
            if (points.size < 2) EMPTY
            else """{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[${points.joinToString(",") { coord(it) }}]}}"""

        fun point(p: GeoPoint, properties: String = ""): String =
            """{"type":"Feature","properties":{$properties},"geometry":{"type":"Point","coordinates":${coord(p)}}}"""
    }
}
