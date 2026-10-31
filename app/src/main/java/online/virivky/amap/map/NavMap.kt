package online.virivky.amap.map

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.Choreographer
import online.virivky.amap.data.Crossings
import online.virivky.amap.data.SavedPlaces
import online.virivky.amap.data.TravelMode
import online.virivky.amap.data.MapTheme
import online.virivky.amap.data.MapPerspective
import online.virivky.amap.data.Place
import online.virivky.amap.data.Poi
import online.virivky.amap.data.Services
import online.virivky.amap.data.Settings
import online.virivky.amap.data.TrafficEvents
import online.virivky.amap.geo.Geo
import online.virivky.amap.geo.GeoPoint
import online.virivky.amap.location.Compass
import online.virivky.amap.location.LocationTracker
import online.virivky.amap.nav.Instructions
import online.virivky.amap.nav.LaneIcons
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
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import online.virivky.amap.util.Bg
import online.virivky.amap.util.Sun
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
    /** V telefóne pešo smer podľa kompasu (telefón v ruke); v aute nie. */
    private val useCompass: Boolean = false,
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
    /**
     * Trasa sa kreslí na dve časti: od auta po [routeCut] (zdroj „route-near“, na každej snímke, takže
     * začiatok trasy ide plynulo s autom) a od [routeCut] ďalej (zdroj „route“, len raz za ~40 m).
     */
    private var routeCut: Double? = null
    private var routeLanes: LaneView? = null
    private var routeFoot = false
    private var routeSideVersion = -1
    /** Trasa v pravej polovici cesty (počas navigácie autom) a kedy sa naposledy zisťovali cesty pod ňou. */
    private var roadSide: RoadSide? = null
    private var classifiedAt = 0L
    private var nearAlong = Double.NaN
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
    /** Pohľad na pruhy pred najbližším manévrom (null = nie je) a pre ktorý manéver bol zistený. */
    private var laneView: LaneView? = null
    private var laneViewKey = ""
    private var shownLaneView: LaneView? = null
    private var lanesSideVersion: Int? = null
    private var shownCrossings = ""
    private var shownSaved: Any? = null
    private var shownSelected: Route? = null
    private var trafficKey = Settings.trafficLayerKey
    private var aerial = Settings.aerialMap
    private var vehicle = Settings.vehicleModel to Settings.vehicleColor

    /** Plynulý pohyb auta a kamery po snímkach obrazovky (bez skákania raz za sekundu). */
    private val motion = CarMotion()
    private var framing = false
    private val frame = Choreographer.FrameCallback { onFrame() }
    /** Vzhľad polohy: "car" alebo "dot", a či ukazovať kompas. */
    private var meIcon = "dot"
    private var meCompass = false
    /** Kamera počas sledovania: cieľové a aktuálne (plynulo sa približujúce) priblíženie a náklon. */
    private var navTilt = 0.0
    private var freeZoom = 15.0
    private var camZoom = Double.NaN
    private var camTilt = 0.0
    /** Plynulý presun kamery k autu po zapnutí sledovania: odkiaľ a odkedy. */
    private var catchFrom: CameraPosition? = null
    private var catchStart = 0L
    /** Kamera sa má k autu presunúť plynulo (po zapnutí sledovania), nie skokom. */
    private var catchUp = false
    private var idleWorkAt = 0L
    /** Kedy používateľ naposledy hýbal mapou (na návrat k polohe počas navigácie). */
    private var touchedAt = 0L
    private var compassOn = false
    private val compassListener: () -> Unit = { if (motion.turnTo(Compass.heading)) startFrames() }

    /** Plynutie trasy: posun prerušovanej čiary po krokoch (ako pohyblivá trasa v AMap). */
    private var flowStep = 0
    private var flowRunning = false
    private var disposed = false

    /** Vlastník mapy vypne animáciu, keď mapa nie je vidieť (aplikácia na pozadí). */
    var flowEnabled = true
        set(value) {
            field = value
            updateFlow()
            if (!value) updateCompass(false)
        }
    private val flow = object : Runnable {
        override fun run() {
            val st = style
            val layer = st?.getLayerAs<LineLayer>("route-flow")
            if (layer == null || !shouldFlow()) {
                flowRunning = false
                return
            }
            val now = SystemClock.uptimeMillis()
            if (now - flowSteppedAt >= FLOW_FRAME_MS) {
                flowSteppedAt = now
                flowStep = (flowStep + 1) % FLOW.size
                layer.setProperties(PropertyFactory.lineDasharray(FLOW[flowStep]))
                st.getLayerAs<LineLayer>("route-flow-near")?.setProperties(PropertyFactory.lineDasharray(FLOW[flowStep]))
            }
            animatePulse(st, now)
            Bg.main.postDelayed(this, PULSE_FRAME_MS)
        }
    }
    private var flowSteppedAt = 0L
    /** Či je na trase svetelná vlna (počas navigácie autom). */
    private var pulseShown = false

    /**
     * Pulzovanie trasy: okraj trasy žiari a pohasína a svetelná vlna vybieha od auta po trase dopredu,
     * raz za [PULSE_PERIOD_MS].
     */
    private fun animatePulse(st: Style, now: Long) {
        val phase = (now % PULSE_PERIOD_MS) / PULSE_PERIOD_MS.toDouble()
        // Najjasnejšie, keď vlna vybieha od auta.
        val glow = 0.1 + 0.9 * (0.5 + 0.5 * cos(2 * PI * phase))
        val casing = Expression.raw(MapStyle.routeCasingColor(dark, glow))
        st.getLayer("route-casing")?.setProperties(PropertyFactory.lineColor(casing))
        st.getLayer("route-casing-near")?.setProperties(PropertyFactory.lineColor(casing))
        if (!pulseShown) return
        // Vlna beží prvých 70 % periódy (od auta po koniec úseku), potom chvíľu nič.
        val center = -0.1 + 1.3 * (phase / 0.7)
        st.getLayer("route-pulse")?.setProperties(PropertyFactory.lineGradient(Expression.raw(pulseGradient(center))))
    }

    private fun shouldFlow() = !disposed && flowEnabled && style != null && shownRoute != null

    private fun updateFlow() {
        if (shouldFlow() && !flowRunning) {
            flowRunning = true
            Bg.main.postDelayed(flow, FLOW_FRAME_MS)
        }
    }
    private val settingsListener: () -> Unit = {
        // Iné auto na mape: stačí vymeniť obrázok.
        val wantedVehicle = Settings.vehicleModel to Settings.vehicleColor
        if (wantedVehicle != vehicle) {
            vehicle = wantedVehicle
            style?.addImage("car", MapIcons.vehicle(context))
        }
        val key = Settings.trafficLayerKey
        val themeChanged = updateTheme(force = true)
        if (key != trafficKey || themeChanged || Settings.aerialMap != aerial) {
            trafficKey = key
            loadStyle()
        } else {
            // Napr. iný pohľad na mapu.
            update()
        }
    }

    private val savedListener = SavedPlaces.Listener { update() }

    private val crossingsListener = Crossings.Listener { updateCrossings() }

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
        map.addOnCameraMoveListener { if (!follow) touchedAt = SystemClock.uptimeMillis() }
        LocationTracker.last?.let {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 15.0))
        } ?: map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(48.1486, 17.1077), 12.0))
        Settings.addListener(settingsListener)
        Crossings.addListener(crossingsListener)
        SavedPlaces.addListener(savedListener)
        // Dopravné udalosti vo výreze mapy (mimo navigácie; počas jazdy tie na trase).
        map.addOnCameraIdleListener {
            // Pri sledovaní auta sa kamera posúva po snímkach – stačí raz za pol sekundy.
            val now = SystemClock.uptimeMillis()
            if (follow && now - idleWorkAt < 500) return@addOnCameraIdleListener
            idleWorkAt = now
            updateCrossings()
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
        updateCompass(false)
        Bg.main.removeCallbacks(flow)
        if (framing) Choreographer.getInstance().removeFrameCallback(frame)
        framing = false
        Settings.removeListener(settingsListener)
        Crossings.removeListener(crossingsListener)
        SavedPlaces.removeListener(savedListener)
    }

    private fun loadStyle() {
        style = null
        aerial = Settings.aerialMap
        val builder = Style.Builder().fromJson(MapStyle.json(dark, trafficKey, aerial))
        vehicle = Settings.vehicleModel to Settings.vehicleColor
        MapIcons.all(context).forEach { (name, bitmap) -> builder.withImage(name, bitmap) }
        map.setStyle(builder) { loaded ->
            style = loaded
            shownRoute = null
            nearAlong = Double.NaN
            shownArrowKey = ""
            shownCameras = null
            shownSignals = null
            shownDestination = null
            shownStops = null
            shownPois = null
            shownAlternatives = null
            shownIncidents = null
            shownLanes = ""
            shownLaneView = null
            shownCrossings = ""
            shownSaved = null
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
        if (!value) touchedAt = SystemClock.uptimeMillis()
        if (value) {
            catchUp = true
            freeZoom = map.cameraPosition.zoom.coerceAtLeast(13.0)
        }
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
        if (follow) {
            if (!isNavigating()) freeZoom = (freeZoom + delta).coerceIn(3.0, 19.5)
            startFrames()
        } else {
            map.animateCamera(CameraUpdateFactory.zoomBy(delta), 250)
        }
    }

    fun recenter() {
        setFollow(true)
        userZoomAt = 0L
        if (isNavigating()) navZoom = 18.0
        else freeZoom = freeZoom.coerceAtLeast(15.0)
        catchUp = true
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
        if (follow) {
            if (!isNavigating()) freeZoom = (freeZoom + delta).coerceIn(3.0, 19.5)
            camZoom = (if (camZoom.isNaN()) map.cameraPosition.zoom else camZoom) + delta
            startFrames()
        } else if (focusX < 0 || focusY < 0) map.moveCamera(CameraUpdateFactory.zoomBy(delta))
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
        // Počas navigácie sa mapa chvíľu po posunutí prstom sama vráti na polohu (aj po náhodnom dotyku).
        if (navigating && !follow && SystemClock.uptimeMillis() - touchedAt > RECENTER_MS) setFollow(true)
        val route = nav.route
        val progress = nav.progress
        val location = LocationTracker.last
        val snapped = progress?.takeIf { navigating && it.offRouteDistance < 30 }

        // Moja poloha: počas navigácie auto s kompasom, inak bodka alebo auto podľa smeru.
        // Na trase ide auto po trase a smer berie z jej tvaru; mimo nej poloha a smer z GPS.
        // Pešo smer podľa kompasu telefónu (GPS pri chôdzi smer takmer nevie), mapa sa otáča s ním.
        val walking = navigating && nav.travelMode == TravelMode.FOOT
        updateCompass(walking)
        motion.followHeading = walking && Compass.heading != null
        val movement = (if (nav.estimating) snapped?.segmentBearing ?: LocationTracker.heading
        else LocationTracker.heading ?: snapped?.segmentBearing)?.toDouble()
        val heading = if (walking) Compass.heading ?: movement else movement
        val target = snapped?.snapped ?: location?.let { GeoPoint(it) }
        meIcon = when {
            walking -> "walker"
            heading != null || navigating -> "car"
            else -> "dot"
        }
        meCompass = navigating
        if (target == null) {
            s.getSourceAs<GeoJsonSource>("me")?.setGeoJson(EMPTY)
        } else if (motion.moveTo(
                SystemClock.uptimeMillis(), target, heading, if (snapped != null) route else null, snapped?.traveled,
                // Pri odhade bez GPS (tunel) je poloha už „teraz“; pešo stačí poloha z GPS (smer je z kompasu).
                speed = if (location != null && location.hasSpeed() && !nav.estimating && !walking) location.speed.toDouble() else 0.0,
                ageMs = location?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000 }?.coerceIn(0L, 2000L) ?: 0L,
            )
        ) {
            startFrames()
        }
        drawMe()
        val me = shownMe() ?: target

        val shownAlong = motion.along?.takeIf { snapped != null && motion.onRoute === route }

        val step = if (navigating && route != null && progress != null) route.steps[progress.nextStepIndex] else null
        // Pred manévrom s pruhmi: vozovka rozdelená na pruhy, trasa a auto v správnych pruhoch (ako v AMap).
        val laneStep = step?.takeIf {
            progress!!.distanceToNextStep < 600 && LaneIcons.useful(it.lanes) &&
                it.type !in setOf("roundabout", "rotary", "arrive", "waypoint") && nav.travelMode != TravelMode.FOOT
        }
        roadSide = if (route != null && navigating && nav.travelMode != TravelMode.FOOT) {
            roadSide?.takeIf { it.route === route } ?: RoadSide(route)
        } else {
            null
        }
        val viewKey = if (laneStep != null) "${System.identityHashCode(route)}-${progress!!.nextStepIndex}" else ""
        if (viewKey != laneViewKey) {
            laneViewKey = viewKey
            laneView = if (laneStep != null && route != null) {
                val carAlong = shownAlong ?: progress!!.traveled
                LaneView.create(route, progress!!.nextStepIndex, laneStep, carAlong, roadAt(route, carAlong))
                    ?.also { v -> roadSide?.let { side -> v.base = side::offsetAt } }
            } else {
                null
            }
        }
        val lanes = laneView

        drawRoute()

        // Šípka najbližšieho manévru.
        val showArrow = step != null && progress!!.distanceToNextStep < 1000 &&
            step.type !in setOf("arrive", "depart", "waypoint") && !(step.modifier == "straight" && step.type != "roundabout")
        val arrowKey = if (showArrow) "${route.hashCode()}-${progress!!.nextStepIndex}-${System.identityHashCode(lanes)}-${roadSide?.version}" else ""
        if (arrowKey != shownArrowKey) {
            if (showArrow) {
                val along = route!!.stepAlong(progress!!.nextStepIndex)
                val shaft = pathOf(route, along - 50, along + 35)
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

        if (lanes !== shownLaneView || (lanes != null && roadSide?.version != lanesSideVersion)) {
            lanesSideVersion = roadSide?.version
            if (lanes != null) {
                val (lines, marks) = laneFeatures(lanes)
                s.getSourceAs<GeoJsonSource>("lanes")?.setGeoJson(lines)
                s.getSourceAs<GeoJsonSource>("lane-marks")?.setGeoJson(marks)
            } else {
                s.getSourceAs<GeoJsonSource>("lanes")?.setGeoJson(EMPTY)
                s.getSourceAs<GeoJsonSource>("lane-marks")?.setGeoJson(EMPTY)
            }
            // Deliace čiary diaľnice z mapy by išli cez naše pruhy.
            s.getLayer("route-lane-divider")?.setProperties(PropertyFactory.visibility(if (lanes != null) Property.NONE else Property.VISIBLE))
            shownLaneView = lanes
            shownLanes = if (lanes != null) viewKey else ""
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

        // Moje miesta (hviezdičky s názvom).
        val saved = SavedPlaces.all
        if (saved !== shownSaved) {
            s.getSourceAs<GeoJsonSource>("saved")?.setGeoJson(
                """{"type":"FeatureCollection","features":[${
                    saved.joinToString(",") { point(it.point, """"name":${JSONObject.quote(it.name)},"id":${it.id}""") }
                }]}"""
            )
            shownSaved = saved
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

        updateCamera(navigating, route, me, animate)
        wasNavigating = navigating
        updateFlow()
        updateCrossings()
    }

    /** Priechody pre chodcov okolo stredu mapy (načítajú sa len pri veľkom priblížení). */
    private fun updateCrossings() {
        val s = style ?: return
        val camera = map.cameraPosition
        val target = camera.target ?: return
        if (camera.zoom < 15.5) return
        val center = GeoPoint(target.latitude, target.longitude)
        Crossings.request(center)
        val key = "${Crossings.version}-${Crossings.tileKey(center)}"
        if (key == shownCrossings) return
        shownCrossings = key
        val features = Crossings.around(center).joinToString(",") { crossing ->
            // Krátka čiara pozdĺž cesty (hĺbka priechodu podľa šírky cesty na mape).
            val half = when (crossing.road) {
                "major" -> 1.8
                "secondary" -> 1.5
                "service" -> 0.8
                else -> 1.1
            }
            val a = Geo.offset(crossing.point, crossing.bearing - 90, -half)
            val b = Geo.offset(crossing.point, crossing.bearing - 90, half)
            val line = "[${coord(a)},${coord(b)}]"
            (0 until ZEBRA_BARS).joinToString(",") { i ->
                """{"type":"Feature","properties":{"c":"${crossing.road}","f":${(i - (ZEBRA_BARS - 1) / 2.0) / ZEBRA_BARS}},""" +
                    """"geometry":{"type":"LineString","coordinates":$line}}"""
            }
        }
        s.getSourceAs<GeoJsonSource>("crossings")?.setGeoJson("""{"type":"FeatureCollection","features":[$features]}""")
    }

    /**
     * Pruhy pred manévrom: povrch, deliace čiary, okraje a zelené správne pruhy, do ktorých trasa plynulo
     * vojde; k tomu šípky na pruhoch a červené X na nesprávnych. Všetko vnútri cesty, ako ju kreslí mapa.
     */
    private fun laneFeatures(v: LaneView): Pair<String, String> {
        val step = v.route.steps[v.stepIndex]
        val n = v.lanes
        val w = v.laneWidth
        val lines = ArrayList<String>()
        fun line(kind: String, width: Double, points: List<GeoPoint>) {
            if (points.size < 2) return
            lines.add("""{"type":"Feature","properties":{"k":"$kind","w":$width},"geometry":{"type":"LineString","coordinates":[${points.joinToString(",") { coord(it) }}]}}""")
        }
        fun along(offset: Double, from: Double = v.start) = v.path(from, v.stop) { offset }
        line("surface", n * w, along(v.left + n * w / 2))
        // Trasa vchádza do správnych pruhov plynulo; pri viacerých správnych pruhoch smeruje do ich stredu.
        val good = (0 until n).filter { v.good[it] }
        line("good", w - 0.5, v.path(v.start, v.start + v.ramp, v::shiftAt))
        for (i in good) line("good", w - 0.5, along(v.left + (i + 0.5) * w, v.start + v.ramp * 0.6))
        for (i in 1 until n) line("sep", 0.16, along(v.left + i * w))
        // Na obojsmernej ceste je ľavým okrajom stredová čiara z mapy.
        if (v.left < 0) line("edge", 0.3, along(v.left + 0.15))
        line("edge", 0.3, along(v.left + n * w - 0.15))

        val marks = ArrayList<String>()
        val scale = (w / 3.5).coerceIn(0.6, 1.0)
        fun mark(at: Double, offset: Double, icon: String) {
            if (at < v.start + 5 || at > v.stop) return
            marks.add(point(v.pointAt(at, offset), """"icon":"$icon","bearing":${v.bearingAt(at)},"s":$scale"""))
        }
        step.lanes.forEachIndexed { i, lane ->
            val center = v.left + (i + 0.5) * w
            mark(v.end - 70, center, laneIcon(lane, step.modifier))
            if (!lane.valid) mark(v.end - 25, center, "lane-x")
        }
        return """{"type":"FeatureCollection","features":[${lines.joinToString(",")}]}""" to
            """{"type":"FeatureCollection","features":[${marks.joinToString(",")}]}"""
    }

    /** Cesty vykreslené v obdĺžniku obrazovky ako úsečky s triedou (šírka vozovky ako na mape) a jednosmernosťou. */
    private fun renderedRoads(box: RectF): List<RoadPiece> {
        val pieces = ArrayList<RoadPiece>()
        for (feature in map.queryRenderedFeatures(box, "motorway", "major", "secondary", "minor", "service")) {
            val lines = when (val g = feature.geometry()) {
                is LineString -> listOf(g.coordinates())
                is MultiLineString -> g.coordinates()
                else -> continue
            }
            val width = when (feature.getStringProperty("class")) {
                "motorway" -> 11.0
                "trunk", "primary" -> 10.0
                "secondary", "tertiary" -> 8.0
                "service" -> 4.0
                else -> 6.5
            }
            val oneWay = feature.hasNonNullValueForProperty("oneway") && feature.getNumberProperty("oneway").toInt() != 0
            val road = RoadShape(width, oneWay)
            for (line in lines) {
                for (k in 0 until line.size - 1) {
                    val a = GeoPoint(line[k].latitude(), line[k].longitude())
                    val b = GeoPoint(line[k + 1].latitude(), line[k + 1].longitude())
                    if (Geo.distance(a, b) >= 0.5) pieces.add(RoadPiece(a, b, road))
                }
            }
        }
        return pieces
    }

    /**
     * Cesta pod bodom trasy [along] podľa vykreslenej mapy: trieda (šírka vozovky ako na mape) a jednosmernosť.
     * Berie sa len cesta v smere trasy (nie priečna na križovatke). Null, ak bod nie je na obrazovke.
     */
    private fun roadAt(route: Route, along: Double): RoadShape? {
        val bearing = Geo.bearing(route.pointAt(along - 5), route.pointAt(along + 10))
        for (probe in listOf(along, along + 25)) {
            val p = route.pointAt(probe)
            val screen = map.projection.toScreenLocation(LatLng(p.lat, p.lon))
            if (screen.x < 0 || screen.y < 0 || screen.x > width || screen.y > height) continue
            val r = 4 * context.resources.displayMetrics.density
            RoadSide.match(renderedRoads(RectF(screen.x - r, screen.y - r, screen.x + r, screen.y + r)), p, bearing, 15.0)?.let { return it }
        }
        return null
    }

    /** Zistí z mapy cesty pod trasou pred autom, ktoré sú na obrazovke a ešte ich nepoznáme. */
    private fun classifyRoads(side: RoadSide, from: Double) {
        if (width == 0 || height == 0) return
        val route = side.route
        val samples = ArrayList<Double>()
        var a = from
        val until = min(route.distance, from + CLASSIFY_AHEAD)
        while (a < until) {
            if (!side.known(a)) {
                val p = route.pointAt(a)
                val screen = map.projection.toScreenLocation(LatLng(p.lat, p.lon))
                if (screen.x >= 0 && screen.y >= 0 && screen.x <= width && screen.y <= height) samples.add(a)
            }
            a += RoadSide.STEP
        }
        if (samples.isEmpty()) return
        val pieces = renderedRoads(RectF(0f, 0f, width.toFloat(), height.toFloat()))
        if (pieces.isEmpty()) return // mapa sa ešte načítava
        for (sample in samples) {
            val bearing = Geo.bearing(route.pointAt(sample - 4), route.pointAt(sample + 4))
            side.set(sample, RoadSide.match(pieces, route.pointAt(sample), bearing, 7.0))
        }
    }

    /** Časť trasy medzi dvoma vzdialenosťami; pred manévrom s pruhmi posunutá do správnych pruhov. */
    private fun pathOf(route: Route, from: Double, to: Double): List<GeoPoint> {
        val side = roadSide?.takeIf { it.route === route }
        fun plain(a: Double, b: Double) = side?.path(a, b) ?: route.slice(a, b)
        val v = laneView?.takeIf { it.route === route }
        if (v == null || to <= v.start || from >= v.end) return plain(from, to)
        val result = ArrayList<GeoPoint>()
        fun add(points: List<GeoPoint>) {
            for (p in points) if (result.isEmpty() || Geo.distance(result.last(), p) > 0.01) result.add(p)
        }
        if (from < v.start) add(plain(from, v.start))
        add(v.path(from, to, v::shiftAt))
        if (to > v.end) add(plain(v.end, to))
        return result
    }

    /** Poloha zobrazeného auta na trase (vzdialenosť od začiatku), ak počas navigácie ide po trase. */
    private fun routeAlong(route: Route): Double? {
        if (!isNavigating()) return null
        val p = NavigationController.progress?.takeIf { it.offRouteDistance < 30 } ?: return null
        return motion.along?.takeIf { motion.onRoute === route } ?: p.traveled
    }

    /** Trasa pred autom (počas jazdy len zostávajúca časť) a svetlý žiar na jej prvých metroch. */
    private fun drawRoute() {
        val s = style ?: return
        val nav = NavigationController
        val route = nav.route
        val foot = nav.travelMode == TravelMode.FOOT
        val along = route?.let { routeAlong(it) }
        // Cesty pod trasou pred autom z mapy (obojsmerná = trasa v pravej polovici), raz za chvíľu.
        val side = roadSide?.takeIf { it.route === route }
        if (side != null && along != null && follow && SystemClock.uptimeMillis() - classifiedAt > CLASSIFY_MS) {
            classifiedAt = SystemClock.uptimeMillis()
            classifyRoads(side, along)
        }
        val sideVersion = side?.version ?: -1
        val cut = routeCut
        val far = route !== shownRoute || laneView !== routeLanes || foot != routeFoot || (along == null) != (cut == null) ||
            sideVersion != routeSideVersion ||
            (along != null && cut != null && (along > cut - 10 || along < cut - CUT_AHEAD - 20))
        if (far) {
            routeSideVersion = sideVersion
            // Podfarbený náš smer jazdy od kúska za autom ďalej po trase.
            s.getSourceAs<GeoJsonSource>("route-tint")?.setGeoJson(
                if (side == null || along == null) EMPTY
                else """{"type":"FeatureCollection","features":[${
                    side.tint(along - 100, along + TINT_AHEAD).joinToString(",") { (line, width) ->
                        """{"type":"Feature","properties":{"w":${Math.round(width * 10) / 10.0}},"geometry":{"type":"LineString","coordinates":[${line.joinToString(",") { coord(it) }}]}}"""
                    }
                }]}"""
            )
            routeCut = along?.let { it + CUT_AHEAD }
            s.getSourceAs<GeoJsonSource>("route")?.setGeoJson(
                if (route == null) EMPTY else routeFeatures(route, routeCut ?: 0.0, route.distance, foot)
            )
            shownRoute = route
            routeLanes = laneView
            routeFoot = foot
        }
        val to = routeCut
        if (route != null && along != null && to != null) {
            if (!far && abs(along - nearAlong) < 0.2) return
            s.getSourceAs<GeoJsonSource>("route-near")?.setGeoJson(routeFeatures(route, along, to, foot))
            s.getSourceAs<GeoJsonSource>("route-head")?.setGeoJson(if (foot) EMPTY else lineString(pathOf(route, along, along + 30)))
            s.getSourceAs<GeoJsonSource>("route-pulse")?.setGeoJson(if (foot) EMPTY else lineString(pathOf(route, along, along + PULSE_M)))
            pulseShown = !foot
            nearAlong = along
        } else if (!nearAlong.isNaN() || far) {
            s.getSourceAs<GeoJsonSource>("route-near")?.setGeoJson(EMPTY)
            s.getSourceAs<GeoJsonSource>("route-head")?.setGeoJson(EMPTY)
            s.getSourceAs<GeoJsonSource>("route-pulse")?.setGeoJson(EMPTY)
            pulseShown = false
            nearAlong = Double.NaN
        }
    }

    /** Kompas beží len pešo počas navigácie a kým je mapa vidieť. */
    private fun updateCompass(walking: Boolean) {
        val want = useCompass && walking && flowEnabled && !disposed
        if (want == compassOn) return
        compassOn = want
        if (want) Compass.addListener(context, compassListener) else Compass.removeListener(compassListener)
    }

    /** Zobrazená poloha auta; v pohľade na pruhy v správnom pruhu. */
    private fun shownMe(): GeoPoint? {
        val me = motion.point ?: return null
        val along = motion.along ?: return me
        val v = laneView?.takeIf { motion.onRoute === it.route && along in it }
        if (v != null) return v.pointAt(along, v.shiftAt(along))
        // Auto v pravej polovici cesty spolu s trasou.
        val side = roadSide?.takeIf { motion.onRoute === it.route } ?: return me
        return side.pointAt(along, side.offsetAt(along))
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

    /** Moje miesto pod bodom obrazovky. */
    fun savedAt(x: Float, y: Float): SavedPlaces.Saved? {
        val r = 18 * context.resources.displayMetrics.density
        val features = map.queryRenderedFeatures(RectF(x - r, y - r, x + r, y + r), "saved")
        val id = features.firstOrNull()?.getNumberProperty("id")?.toLong() ?: return null
        return SavedPlaces.all.firstOrNull { it.id == id }
    }

    /** Ukáže bod na mape (prestane sledovať polohu). */
    fun showPoint(p: GeoPoint) {
        setFollow(false)
        map.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lon), 16.5), 600)
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

    private fun updateCamera(navigating: Boolean, route: Route?, me: GeoPoint?, animate: Boolean) {
        if (width == 0 || height == 0) return
        if (navigating && !wasNavigating) {
            setFollow(true)
            navZoom = 18.0
            catchUp = true
        }
        if (!navigating && wasNavigating) {
            setFollow(true)
            catchUp = true
        }
        if (!navigating && NavigationController.phase == Phase.PREVIEW && route != null && route !== fittedRoute) {
            fittedRoute = route
            // Všetky varianty trasy naraz.
            fit(NavigationController.routes.flatMap { it.points }.ifEmpty { route.points } + listOfNotNull(me))
            return
        }
        if (NavigationController.phase == Phase.IDLE) fittedRoute = null
        if (!follow || me == null) return

        if (navigating) {
            // Vo vyššej rýchlosti viac oddialiť a nakloniť, aby bolo vidieť ďalej dopredu.
            val speed = (NavigationController.speedKmh ?: 0).toDouble()
            if (android.os.SystemClock.elapsedRealtime() - userZoomAt > 20_000) {
                // Blízko ako AMap: v meste (do 30 km/h) priblíženie 18,6, na diaľnici (130 km/h) 16,2.
                var zoom = (18.6 - (speed - 30).coerceAtLeast(0.0) * 0.024).coerceIn(16.0, 18.6)
                // Pred odbočkou či zjazdom postupne priblížiť, aby bola križovatka dobre vidieť.
                val p = NavigationController.progress
                val step = if (p != null && route != null) route.steps[p.nextStepIndex] else null
                if (p != null && step != null && step.type != "arrive" && step.modifier != "straight") {
                    val k = ((700 - p.distanceToNextStep) / 600).coerceIn(0.0, 1.0)
                    zoom += (18.6 - zoom).coerceAtLeast(0.0) * k
                }
                // Pri pohľade na pruhy bližšie, aby boli pruhy dobre vidieť.
                if (shownLanes.isNotEmpty()) zoom = max(zoom, 18.9)
                // Pešo trochu ďalej, nech je vidieť okolité chodníky.
                if (NavigationController.travelMode == TravelMode.FOOT) zoom = 17.6
                navZoom = zoom
            }
            navTilt = if (Settings.mapPerspective == MapPerspective.THREE_D) {
                if (NavigationController.travelMode == TravelMode.FOOT) 35.0
                else (52 + (speed - 30).coerceAtLeast(0.0) * 0.1).coerceIn(52.0, 60.0)
            } else {
                0.0
            }
        }
        if (catchUp || !animate) {
            // K autu plynulo (po zapnutí sledovania), potom už kamera ide s autom po snímkach.
            camZoom = if (navigating) navZoom else freeZoom
            camTilt = if (navigating) navTilt else 0.0
            if (catchUp && animate) {
                catchFrom = map.cameraPosition
                catchStart = SystemClock.uptimeMillis()
            } else {
                catchFrom = null
                cameraPosition(navigating, me)?.let { map.moveCamera(CameraUpdateFactory.newCameraPosition(it)) }
            }
            catchUp = false
        }
        startFrames()
    }

    /** Kamera nad zobrazeným autom: počas navigácie v smere jazdy, auto v dolnej časti voľnej plochy. */
    private fun cameraPosition(navigating: Boolean, me: GeoPoint): CameraPosition? {
        val area = if (viewport.isEmpty) Rect(0, 0, width, height) else viewport
        if (!navigating) {
            return CameraPosition.Builder()
                .target(LatLng(me.lat, me.lon))
                .zoom(camZoom)
                .bearing(0.0)
                .tilt(0.0)
                .padding(
                    area.left.toDouble(), area.top.toDouble(),
                    (width - area.right).toDouble(), (height - area.bottom).toDouble(),
                )
                .build()
        }
        val mode = Settings.mapPerspective
        val bearing = if (mode == MapPerspective.NORTH_UP) 0.0 else motion.bearing ?: map.cameraPosition.bearing
        // Pri „sever hore“ je auto bližšie k stredu.
        val carShare = when (mode) {
            MapPerspective.THREE_D -> 0.42
            MapPerspective.HEADING_UP -> 0.36
            MapPerspective.NORTH_UP -> 0.15
        }
        return CameraPosition.Builder()
            .target(LatLng(me.lat, me.lon))
            .zoom(camZoom)
            .bearing(bearing)
            .tilt(camTilt)
            .padding(
                area.left.toDouble(),
                area.top + area.height() * carShare,
                (width - area.right).toDouble(),
                (height - area.bottom).toDouble(),
            )
            .build()
    }

    private fun startFrames() {
        if (framing || disposed) return
        framing = true
        lastFrameAt = SystemClock.uptimeMillis()
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private var lastFrameAt = 0L

    /** Jedna snímka: posun auta, kamera za ním, plynulá zmena priblíženia a náklonu. */
    private fun onFrame() {
        framing = false
        if (disposed || style == null) return
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastFrameAt).coerceIn(0L, 100L)
        lastFrameAt = now
        var moving = motion.step(now)
        drawMe()
        drawRoute()
        val me = shownMe()
        if (follow && me != null && width > 0 && height > 0) {
            val navigating = isNavigating()
            if (camZoom.isNaN()) camZoom = map.cameraPosition.zoom
            val wantedZoom = if (navigating) navZoom else freeZoom
            val wantedTilt = if (navigating) navTilt else 0.0
            val k = 1 - kotlin.math.exp(-dt / 600.0)
            camZoom += (wantedZoom - camZoom) * k
            camTilt += (wantedTilt - camTilt) * k
            if (abs(wantedZoom - camZoom) > 0.005 || abs(wantedTilt - camTilt) > 0.05) moving = true
            var position = cameraPosition(navigating, me)
            val from = catchFrom
            if (from != null && position != null) {
                val f = ((now - catchStart) / CATCH_MS).coerceIn(0.0, 1.0)
                position = blend(from, position, f * f * (3 - 2 * f))
                if (f >= 1) catchFrom = null else moving = true
            }
            position?.let { map.moveCamera(CameraUpdateFactory.newCameraPosition(it)) }
        } else {
            catchFrom = null
        }
        if (moving) startFrames()
    }

    /** Kamera medzi [a] a [b] (0 = a, 1 = b), smer najkratšou cestou. */
    private fun blend(a: CameraPosition, b: CameraPosition, t: Double): CameraPosition {
        val at = a.target ?: return b
        val bt = b.target ?: return b
        val turn = ((b.bearing - a.bearing + 540) % 360) - 180
        val builder = CameraPosition.Builder()
            .target(LatLng(at.latitude + (bt.latitude - at.latitude) * t, at.longitude + (bt.longitude - at.longitude) * t))
            .zoom(a.zoom + (b.zoom - a.zoom) * t)
            .tilt(a.tilt + (b.tilt - a.tilt) * t)
            .bearing((a.bearing + turn * t + 360) % 360)
        val pa = a.padding
        val pb = b.padding
        if (pb != null) {
            builder.padding(DoubleArray(pb.size) { i -> (pa?.getOrNull(i) ?: pb[i]) + (pb[i] - (pa?.getOrNull(i) ?: pb[i])) * t })
        }
        return builder.build()
    }

    /** Auto (či bodka) na zobrazenej polohe. */
    private fun drawMe() {
        val s = style ?: return
        val me = shownMe() ?: return
        s.getSourceAs<GeoJsonSource>("me")?.setGeoJson(
            point(me, """"bearing":${motion.bearing ?: 0.0},"icon":"$meIcon","compass":${if (meCompass) 1 else 0}""")
        )
    }

    /**
     * Trasa medzi [from] a [to] rozdelená na úseky podľa premávky (vlastnosť „level“ určuje farbu). V pohľade na pruhy
     * ju po stop čiaru nahrádzajú zelené pruhy; za stop čiarou pokračuje zo správneho pruhu.
     */
    private fun routeFeatures(route: Route, from: Double, to: Double, foot: Boolean): String {
        val features = ArrayList<String>()
        val v = laneView?.takeIf { it.route === route && from < it.end && to > it.start }
        if (v == null) {
            addRoute(route, from, to, foot, features)
        } else {
            addRoute(route, from, min(to, v.start), foot, features)
            if (to > v.stop) {
                val exit = pathOf(route, max(from, v.stop), min(to, v.end))
                if (exit.size >= 2) features.add(feature(exit, route.segmentTraffic.getOrElse(route.progressAt(v.end).segmentIndex) { 0 }, foot))
            }
            addRoute(route, max(from, v.end), to, foot, features)
        }
        return """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""
    }

    private fun addRoute(route: Route, from: Double, to: Double, foot: Boolean, features: MutableList<String>) {
        if (to - from < 0.5) return
        val levels = route.segmentTraffic
        val side = roadSide?.takeIf { it.route === route }
        fun piece(a: Double, b: Double, level: Int) {
            if (b - a < 0.05) return
            val points = side?.path(a, b) ?: route.slice(a, b)
            if (points.size >= 2) features.add(feature(points, level, foot))
        }
        // Prvý úsek, ktorý končí za [from] (trasa môže mať tisíce bodov a časť pri aute sa kreslí na každej snímke).
        var lo = 0
        var hi = route.points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (route.alongAtIndex(mid) <= from) lo = mid else hi = mid
        }
        var start = from
        var level = -1
        for (i in lo until route.points.size - 1) {
            val a = route.alongAtIndex(i)
            val b = route.alongAtIndex(i + 1)
            if (b <= from) continue
            if (a >= to) break
            val segmentLevel = levels.getOrElse(i) { 0 }
            if (segmentLevel != level) {
                if (level >= 0) piece(start, a, level)
                start = max(from, a)
                level = segmentLevel
            }
        }
        if (level >= 0) piece(start, to, level)
    }

    private fun feature(points: List<GeoPoint>, level: Int, foot: Boolean = false) =
        """{"type":"Feature","properties":{"level":$level,"foot":${if (foot) 1 else 0}},"geometry":{"type":"LineString","coordinates":[${points.joinToString(",") { coord(it) }}]}}"""

    companion object {
        private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""

        /** Počet bielych pruhov priechodu pre chodcov (šírky a posuny podľa cesty v štýle mapy). */
        private const val ZEBRA_BARS = 6

        /** Kroky posunu pruhov (v násobkoch hrúbky čiary): pruh 3, medzera 4, posun o pol jednotky. */
        private val FLOW: Array<Array<Float>> = arrayOf(
            arrayOf(0f, 4f, 3f), arrayOf(0.5f, 4f, 2.5f), arrayOf(1f, 4f, 2f), arrayOf(1.5f, 4f, 1.5f),
            arrayOf(2f, 4f, 1f), arrayOf(2.5f, 4f, 0.5f), arrayOf(3f, 4f, 0f), arrayOf(0f, 0.5f, 3f, 3.5f),
            arrayOf(0f, 1f, 3f, 3f), arrayOf(0f, 1.5f, 3f, 2.5f), arrayOf(0f, 2f, 3f, 2f), arrayOf(0f, 2.5f, 3f, 1.5f),
            arrayOf(0f, 3f, 3f, 1f), arrayOf(0f, 3.5f, 3f, 0.5f),
        )
        private const val FLOW_FRAME_MS = 60L
        /** Krok animácie pulzovania trasy (~30 snímok za sekundu), perióda a dĺžka vlny na trase. */
        private const val PULSE_FRAME_MS = 33L
        private const val PULSE_PERIOD_MS = 2000L
        private const val PULSE_M = 260.0
        /** Ako ďaleko pred autom je podfarbený náš smer jazdy. */
        private const val TINT_AHEAD = 3000.0

        /** Svetlá vlna: (poloha voči stredu vlny, farba r, g, b, priehľadnosť) – za ňou dlhší chvost. */
        private val PULSE = arrayOf(
            doubleArrayOf(-0.18, 90.0, 230.0, 255.0, 0.0),
            doubleArrayOf(-0.08, 110.0, 245.0, 230.0, 0.55),
            doubleArrayOf(0.0, 255.0, 255.0, 255.0, 0.95),
            doubleArrayOf(0.03, 160.0, 255.0, 225.0, 0.6),
            doubleArrayOf(0.07, 90.0, 230.0, 255.0, 0.0),
        )

        /** Farebný prechod pozdĺž úseku trasy (line-progress 0 až 1) s vlnou so stredom v [center]. */
        fun pulseGradient(center: Double): String {
            fun colorAt(x: Double): DoubleArray {
                val rel = x - center
                if (rel <= PULSE.first()[0] || rel >= PULSE.last()[0]) return doubleArrayOf(255.0, 255.0, 255.0, 0.0)
                val i = PULSE.indexOfLast { it[0] <= rel }
                val a = PULSE[i]
                val b = PULSE[i + 1]
                val t = (rel - a[0]) / (b[0] - a[0])
                return DoubleArray(4) { a[it + 1] + (b[it + 1] - a[it + 1]) * t }
            }
            val positions = (listOf(0.0, 1.0) + PULSE.map { center + it[0] }.filter { it > 0 && it < 1 }).sorted()
            val stops = ArrayList<Double>()
            for (x in positions) if (stops.isEmpty() || x - stops.last() > 1e-4) stops.add(x)
            return """["interpolate",["linear"],["line-progress"],""" + stops.joinToString(",") { x ->
                val c = colorAt(x)
                "${Math.round(x * 10000) / 10000.0},\"rgba(${c[0].roundToInt()},${c[1].roundToInt()},${c[2].roundToInt()},${Math.round(c[3] * 100) / 100.0})\""
            } + "]"
        }

        /** Dĺžka časti trasy pred autom, ktorá sa kreslí na každej snímke (najviac, kým sa celá trasa neprekreslí). */
        private const val CUT_AHEAD = 50.0

        /** Ako často a ako ďaleko pred autom sa z mapy zisťujú cesty pod trasou. */
        private const val CLASSIFY_MS = 1500L
        private const val CLASSIFY_AHEAD = 600.0

        /** Po koľkých ms bez dotyku sa mapa počas navigácie vráti na polohu. */
        private const val RECENTER_MS = 10_000L

        /** Ako dlho trvá plynulý presun kamery k autu. */
        private const val CATCH_MS = 800.0

        private fun round(v: Double) = Math.round(v * 1e7) / 1e7

        private fun coord(p: GeoPoint) = "[${round(p.lon)},${round(p.lat)}]"

        fun lineString(points: List<GeoPoint>): String =
            if (points.size < 2) EMPTY
            else """{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[${points.joinToString(",") { coord(it) }}]}}"""

        fun point(p: GeoPoint, properties: String = ""): String =
            """{"type":"Feature","properties":{$properties},"geometry":{"type":"Point","coordinates":${coord(p)}}}"""
    }
}
