package online.virivky.amap.map

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import online.virivky.amap.data.MapTheme
import online.virivky.amap.data.MapPerspective
import online.virivky.amap.data.Place
import online.virivky.amap.data.Poi
import online.virivky.amap.data.Services
import online.virivky.amap.data.Settings
import online.virivky.amap.data.TrafficEvents
import online.virivky.amap.geo.Geo
import online.virivky.amap.geo.GeoPoint
import online.virivky.amap.location.LocationTracker
import online.virivky.amap.nav.Instructions
import online.virivky.amap.nav.LaneIcons
import online.virivky.amap.nav.RouteStep
import online.virivky.amap.nav.NavigationController
import online.virivky.amap.nav.NavigationController.Phase
import online.virivky.amap.nav.Route
import online.virivky.amap.net.Incident
import online.virivky.amap.net.RouteIncident
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import online.virivky.amap.util.Bg
import online.virivky.amap.util.Sun
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.max

/**
 * Ovládanie vektorovej mapy pre telefón aj auto: trasa, šípka manévru, poloha
 * a kamera, ktorá počas navigácie sleduje auto v smere jazdy.
 */
class NavMap(
    private val context: Context,
    val map: MapLibreMap,
    dark: Boolean,
    /** V telefóne sa pri automatických farbách riadime slnkom (v aute rozhoduje auto). */
    private val followSun: Boolean = false,
) {
    /** Či mapa sleduje polohu (vypne sa, keď používateľ mapou pohne). */
    var follow = true
        private set
    var onFollowChanged: (() -> Unit)? = null

    private var style: Style? = null
    private var systemDark = dark
    private var dark = dark
    private var themeCheckedAt = 0L
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
    private var shownStops: List<Place>? = null
    private var shownPois: List<Poi>? = null
    private var fittedPois: List<Poi>? = null
    private var shownAlternatives: List<Route>? = null
    private var shownIncidents: Any? = null
    private var shownLanes = ""
    private var shownSelected: Route? = null
    private var trafficKey = Settings.trafficLayerKey

    /** Plynutie trasy: posun prerušovanej čiary po krokoch (ako pohyblivá trasa v AMap). */
    private var flowStep = 0
    private var flowRunning = false
    private var disposed = false

    /** Vlastník mapy vypne animáciu, keď mapa nie je vidieť (aplikácia na pozadí). */
    var flowEnabled = true
        set(value) {
            field = value
            updateFlow()
        }
    private val flow = object : Runnable {
        override fun run() {
            val layer = style?.getLayerAs<LineLayer>("route-flow")
            if (layer == null || !shouldFlow()) {
                flowRunning = false
                return
            }
            flowStep = (flowStep + 1) % FLOW.size
            layer.setProperties(PropertyFactory.lineDasharray(FLOW[flowStep]))
            Bg.main.postDelayed(this, FLOW_FRAME_MS)
        }
    }

    private fun shouldFlow() = !disposed && flowEnabled && style != null && shownRoute != null

    private fun updateFlow() {
        if (shouldFlow() && !flowRunning) {
            flowRunning = true
            Bg.main.postDelayed(flow, FLOW_FRAME_MS)
        }
    }
    private val settingsListener: () -> Unit = {
        val key = Settings.trafficLayerKey
        val themeChanged = updateTheme(force = true)
        if (key != trafficKey || themeChanged) {
            trafficKey = key
            loadStyle()
        } else {
            // Napr. iný pohľad na mapu.
            update()
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
        // Dopravné udalosti vo výreze mapy (mimo navigácie; počas jazdy tie na trase).
        map.addOnCameraIdleListener {
            if (!isNavigating() && map.cameraPosition.zoom >= 9) {
                val b = map.projection.visibleRegion.latLngBounds
                TrafficEvents.requestArea(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast)
            }
        }
        updateTheme(force = true)
        loadStyle()
    }

    /** Uvoľní mapu (prestane počúvať zmeny nastavení). */
    fun dispose() {
        disposed = true
        Bg.main.removeCallbacks(flow)
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
            shownStops = null
            shownPois = null
            shownAlternatives = null
            shownIncidents = null
            shownLanes = ""
            update()
        }
    }

    /** Mapa má práve nočné farby. */
    val isDark: Boolean
        get() = dark

    /** Systémový (v aute podľa auta) denný či nočný režim. */
    fun setDark(dark: Boolean) {
        systemDark = dark
        if (updateTheme(force = true)) loadStyle()
    }

    /** Prepočíta denné/nočné farby; vráti true, ak sa zmenili (štýl treba načítať znova). */
    private fun updateTheme(force: Boolean = false): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - themeCheckedAt < 60_000) return false
        themeCheckedAt = now
        val location = LocationTracker.last
        val wanted = when (Settings.mapTheme) {
            MapTheme.DAY -> false
            MapTheme.NIGHT -> true
            MapTheme.AUTO ->
                if (followSun && location != null) Sun.isNight(location.latitude, location.longitude) else systemDark
        }
        if (wanted == dark) return false
        dark = wanted
        return true
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
        // Panel so službami zmenil voľnú plochu – miesta znova vmestíme do nej.
        if (!follow && fittedPois === Services.results) fittedPois = null
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
        // Raz za minútu kontrola súmraku/svitania (automatické farby).
        if (updateTheme()) {
            loadStyle()
            return
        }
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
            step.type !in setOf("arrive", "depart", "waypoint") && !(step.modifier == "straight" && step.type != "roundabout")
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

        // Pred manévrom s pruhmi: cesta rozdelená na pruhy, správne zvýraznené (ako v AMap).
        val laneStep = step?.takeIf {
            progress!!.distanceToNextStep < 600 && LaneIcons.useful(it.lanes) &&
                it.type !in setOf("roundabout", "rotary", "arrive", "waypoint")
        }
        val lanesKey = if (laneStep != null) "${System.identityHashCode(route)}-${progress!!.nextStepIndex}" else ""
        if (lanesKey != shownLanes) {
            if (laneStep != null && route != null) {
                val (lines, marks) = laneFeatures(route, progress!!.nextStepIndex, laneStep)
                s.getSourceAs<GeoJsonSource>("lanes")?.setGeoJson(lines)
                s.getSourceAs<GeoJsonSource>("lane-marks")?.setGeoJson(marks)
            } else {
                s.getSourceAs<GeoJsonSource>("lanes")?.setGeoJson(EMPTY)
                s.getSourceAs<GeoJsonSource>("lane-marks")?.setGeoJson(EMPTY)
            }
            shownLanes = lanesKey
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

        // Varianty trasy v náhľade: ostatné sivo, pri každom čas jazdy.
        val alternatives = if (nav.phase == Phase.PREVIEW) nav.routes else emptyList()
        if (alternatives !== shownAlternatives || route !== shownSelected) {
            s.getSourceAs<GeoJsonSource>("alternatives")?.setGeoJson(
                if (alternatives.size < 2) EMPTY
                else """{"type":"FeatureCollection","features":[${
                    alternatives.withIndex().filter { it.value !== route }.joinToString(",") { (i, r) ->
                        """{"type":"Feature","properties":{"i":$i},"geometry":{"type":"LineString","coordinates":[${r.points.joinToString(",") { coord(it) }}]}}"""
                    }
                }]}"""
            )
            s.getSourceAs<GeoJsonSource>("route-labels")?.setGeoJson(
                if (alternatives.size < 2) EMPTY
                else """{"type":"FeatureCollection","features":[${
                    alternatives.joinToString(",") { r ->
                        point(
                            labelPoint(r, alternatives),
                            """"label":${JSONObject.quote(Instructions.displayDuration(r.duration))},"selected":${if (r === route) 1 else 0}""",
                        )
                    }
                }]}"""
            )
            shownAlternatives = alternatives
            shownSelected = route
        }

        // Dopravné udalosti: počas jazdy na trase pred nami, inak vo výreze mapy.
        val incidentSource: List<Any> = when {
            Settings.trafficLayerKey == null -> emptyList()
            navigating -> if (TrafficEvents.aheadRoute === route) TrafficEvents.ahead else emptyList()
            else -> TrafficEvents.area
        }
        if (incidentSource !== shownIncidents) {
            val incidents = incidentSource.map { if (it is RouteIncident) it.incident else it as Incident }
            s.getSourceAs<GeoJsonSource>("incidents")?.setGeoJson(
                if (incidents.isEmpty()) EMPTY
                else """{"type":"FeatureCollection","features":[${
                    incidents.joinToString(",") { point(it.points.first(), """"icon":"${it.icon}"""") }
                }]}"""
            )
            val lines = incidents.filter { it.points.size >= 2 && it.category in setOf(7, 8, 9) }
            s.getSourceAs<GeoJsonSource>("incident-lines")?.setGeoJson(
                if (lines.isEmpty()) EMPTY
                else """{"type":"FeatureCollection","features":[${
                    lines.joinToString(",") {
                        """{"type":"Feature","properties":{"closed":${if (it.category == 8) 1 else 0}},"geometry":{"type":"LineString","coordinates":[${it.points.joinToString(",") { p -> coord(p) }}]}}"""
                    }
                }]}"""
            )
            shownIncidents = incidentSource
        }

        val stops = nav.stops
        if (stops !== shownStops) {
            s.getSourceAs<GeoJsonSource>("via")?.setGeoJson(
                if (stops.isEmpty()) EMPTY
                else """{"type":"FeatureCollection","features":[${stops.joinToString(",") { point(it.point) }}]}"""
            )
            shownStops = stops
        }

        val destination = nav.destination?.point
        if (destination != shownDestination) {
            s.getSourceAs<GeoJsonSource>("destination")?.setGeoJson(destination?.let { point(it) } ?: EMPTY)
            shownDestination = destination
        }

        // Moja poloha: počas navigácie auto s kompasom, inak bodka alebo auto podľa smeru.
        // Bez GPS (tunel) sa smer berie z trasy, inak z GPS.
        val heading = if (nav.estimating) snapped?.segmentBearing ?: LocationTracker.heading
        else LocationTracker.heading ?: snapped?.segmentBearing
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

        // Služby: mimo navigácie mapu priblížime na najbližšie nájdené miesta.
        val pois = Services.results
        if (pois !== shownPois) {
            s.getSourceAs<GeoJsonSource>("pois")?.setGeoJson(poiFeatures(pois))
            val hadPois = !shownPois.isNullOrEmpty()
            shownPois = pois
            if (pois.isEmpty() && hadPois && nav.phase == Phase.IDLE) setFollow(true)
        }
        if (pois.isNotEmpty() && pois !== fittedPois && !navigating && nav.phase == Phase.IDLE && width > 0 && height > 0) {
            // Označiť vopred: fit() vypne sledovanie a to môže hneď znova zavolať update().
            fittedPois = pois
            fit(pois.take(12).map { it.point } + listOfNotNull(me))
        }

        updateCamera(navigating, route, me, heading, animate)
        wasNavigating = navigating
        updateFlow()
    }

    /**
     * Pruhy pred manévrom: povrch, deliace čiary, okraje a zvýraznené správne pruhy (posun od osi trasy
     * v metroch rieši mapa), k tomu šípky na pruhoch a červené X na nesprávnych.
     */
    private fun laneFeatures(route: Route, stepIndex: Int, step: RouteStep): Pair<String, String> {
        val lanes = step.lanes
        val n = lanes.size
        val w = 3.5
        val end = route.stepAlong(stepIndex)
        val start = max(0.0, end - 320)
        val coords = route.slice(start, end + 12).joinToString(",") { coord(it) }
        // Diaľnica či rampa = os trasy je stred jedného smeru; inak sú naše pruhy vpravo od osi cesty.
        val oneWay = Instructions.isHighway(step) || step.type in setOf("fork", "merge", "on ramp", "off ramp") ||
            step.ref.startsWith("D") || step.ref.startsWith("R")
        val left = if (oneWay) -n * w / 2 else 0.0
        val lines = ArrayList<String>()
        fun line(kind: String, width: Double, offset: Double) {
            lines.add("""{"type":"Feature","properties":{"k":"$kind","w":$width,"o":$offset},"geometry":{"type":"LineString","coordinates":[$coords]}}""")
        }
        line("surface", n * w + 0.6, left + n * w / 2)
        lanes.forEachIndexed { i, lane ->
            if (LaneIcons.recommended(lane, step.modifier) != null) line("good", w - 0.5, left + (i + 0.5) * w)
        }
        for (i in 1 until n) line("sep", 0.18, left + i * w)
        line("edge", 0.35, left - 0.15)
        line("edge", 0.35, left + n * w + 0.15)

        val marks = ArrayList<String>()
        fun mark(along: Double, offset: Double, icon: String) {
            if (along < start + 5) return
            val p = route.pointAt(along)
            val bearing = Geo.bearing(p, route.pointAt(along + 5))
            marks.add(point(Geo.offset(p, bearing, offset), """"icon":"$icon","bearing":$bearing"""))
        }
        lanes.forEachIndexed { i, lane ->
            val center = left + (i + 0.5) * w
            mark(end - 55, center, laneIcon(lane, step.modifier))
            if (!lane.valid) mark(end - 22, center, "lane-x")
        }
        return """{"type":"FeatureCollection","features":[${lines.joinToString(",")}]}""" to
            """{"type":"FeatureCollection","features":[${marks.joinToString(",")}]}"""
    }

    private fun laneIcon(lane: online.virivky.amap.nav.LaneInfo, modifier: String?): String {
        val indication = LaneIcons.recommended(lane, modifier) ?: lane.indications.firstOrNull { it != "none" } ?: "straight"
        return when (indication) {
            "left", "sharp left" -> "lane-left"
            "right", "sharp right" -> "lane-right"
            "slight left" -> "lane-slight-left"
            "slight right" -> "lane-slight-right"
            "uturn" -> "lane-uturn"
            else -> "lane-straight"
        }
    }

    /** Variant trasy pod bodom obrazovky (index v [NavigationController.routes]). */
    fun alternativeAt(x: Float, y: Float): Int? {
        val r = 16 * context.resources.displayMetrics.density
        val features = map.queryRenderedFeatures(RectF(x - r, y - r, x + r, y + r), "alt-line", "alt-casing")
        return features.firstOrNull()?.getNumberProperty("i")?.toInt()
    }

    /** Miesto pre popis variantu: kde je najďalej od ostatných variantov. */
    private fun labelPoint(r: Route, all: List<Route>): GeoPoint {
        val others = all.filter { it !== r }
        var best = r.pointAt(r.distance / 2)
        var bestGap = -1.0
        for (k in 1..19) {
            val p = r.pointAt(r.distance * k / 20)
            val gap = others.minOfOrNull { it.distanceTo(p) } ?: 0.0
            if (gap > bestGap) {
                bestGap = gap
                best = p
            }
        }
        return best
    }

    /** Služba pod bodom obrazovky (napr. po ťuknutí na mapu). */
    fun poiAt(x: Float, y: Float): Poi? {
        val r = 14 * context.resources.displayMetrics.density
        val features = map.queryRenderedFeatures(RectF(x - r, y - r, x + r, y + r), "pois")
        val index = features.firstOrNull()?.getNumberProperty("i")?.toInt() ?: return null
        return Services.results.getOrNull(index)
    }

    /** Priblíži mapu tak, aby boli body vidieť vo voľnej ploche. */
    private fun fit(points: List<GeoPoint>) {
        if (width == 0 || height == 0 || points.isEmpty()) return
        val area = if (viewport.isEmpty) Rect(0, 0, width, height) else viewport
        val pad = (40 * context.resources.displayMetrics.density).toInt()
        val bounds = LatLngBounds.Builder()
        points.forEach { bounds.include(LatLng(it.lat, it.lon)) }
        try {
            map.easeCamera(
                CameraUpdateFactory.newLatLngBounds(
                    bounds.build(), 0.0, 0.0,
                    area.left + pad, area.top + pad, width - area.right + pad, height - area.bottom + pad,
                ),
                600,
            )
        } catch (e: Exception) {
            // Jediný bod nemá ohraničenie – len naň posunieme mapu.
            val p = points.first()
            map.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lon), 15.0), 600)
        }
        setFollow(false)
    }

    private fun poiFeatures(pois: List<Poi>): String =
        if (pois.isEmpty()) EMPTY
        else """{"type":"FeatureCollection","features":[${
            pois.mapIndexed { i, poi ->
                point(poi.point, """"icon":"${poi.category.icon}","name":${JSONObject.quote(poi.name)},"rank":$i,"i":$i""")
            }.joinToString(",")
        }]}"""

    private fun updateCamera(navigating: Boolean, route: Route?, me: GeoPoint?, heading: Float?, animate: Boolean) {
        if (width == 0 || height == 0) return
        if (navigating && !wasNavigating) {
            setFollow(true)
            navZoom = 17.0
        }
        if (!navigating && wasNavigating) setFollow(true)
        val area = if (viewport.isEmpty) Rect(0, 0, width, height) else viewport

        if (!navigating && NavigationController.phase == Phase.PREVIEW && route != null && route !== fittedRoute) {
            fittedRoute = route
            // Všetky varianty trasy naraz.
            fit(NavigationController.routes.flatMap { it.points }.ifEmpty { route.points } + listOfNotNull(me))
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
                // Pri pohľade na pruhy bližšie, aby boli pruhy dobre vidieť.
                if (shownLanes.isNotEmpty()) zoom = max(zoom, 18.7)
                navZoom = zoom
            }
            val mode = Settings.mapPerspective
            val tilt = if (mode == MapPerspective.THREE_D) (40 + (speed - 30).coerceAtLeast(0.0) * 0.3).coerceIn(40.0, 58.0) else 0.0
            val bearing = if (mode == MapPerspective.NORTH_UP) 0.0 else (heading ?: map.cameraPosition.bearing.toFloat()).toDouble()
            // Auto v dolnej časti voľnej plochy, mapa otočená v smere jazdy (pri „sever hore“ bližšie k stredu).
            val carShare = when (mode) {
                MapPerspective.THREE_D -> 0.42
                MapPerspective.HEADING_UP -> 0.36
                MapPerspective.NORTH_UP -> 0.15
            }
            CameraPosition.Builder()
                .target(LatLng(me.lat, me.lon))
                .zoom(navZoom)
                .bearing(bearing)
                .tilt(tilt)
                .padding(
                    area.left.toDouble(),
                    area.top + area.height() * carShare,
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

        /** Kroky posunu pruhov (v násobkoch hrúbky čiary): pruh 3, medzera 4, posun o pol jednotky. */
        private val FLOW: Array<Array<Float>> = arrayOf(
            arrayOf(0f, 4f, 3f), arrayOf(0.5f, 4f, 2.5f), arrayOf(1f, 4f, 2f), arrayOf(1.5f, 4f, 1.5f),
            arrayOf(2f, 4f, 1f), arrayOf(2.5f, 4f, 0.5f), arrayOf(3f, 4f, 0f), arrayOf(0f, 0.5f, 3f, 3.5f),
            arrayOf(0f, 1f, 3f, 3f), arrayOf(0f, 1.5f, 3f, 2.5f), arrayOf(0f, 2f, 3f, 2f), arrayOf(0f, 2.5f, 3f, 1.5f),
            arrayOf(0f, 3f, 3f, 1f), arrayOf(0f, 3.5f, 3f, 0.5f),
        )
        private const val FLOW_FRAME_MS = 60L

        private fun round(v: Double) = Math.round(v * 1e7) / 1e7

        private fun coord(p: GeoPoint) = "[${round(p.lon)},${round(p.lat)}]"

        fun lineString(points: List<GeoPoint>): String =
            if (points.size < 2) EMPTY
            else """{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[${points.joinToString(",") { coord(it) }}]}}"""

        fun point(p: GeoPoint, properties: String = ""): String =
            """{"type":"Feature","properties":{$properties},"geometry":{"type":"Point","coordinates":${coord(p)}}}"""
    }
}
