package online.virivky.amap.nav

import android.content.Context
import android.location.Location
import android.os.SystemClock
import online.virivky.amap.data.Place
import online.virivky.amap.data.PoiCategory
import online.virivky.amap.data.Recents
import online.virivky.amap.data.Services
import online.virivky.amap.data.Settings
import online.virivky.amap.data.TrafficEvents
import online.virivky.amap.geo.Geo
import online.virivky.amap.geo.GeoPoint
import online.virivky.amap.location.LocationTracker
import online.virivky.amap.net.Incident
import online.virivky.amap.net.RoadData
import online.virivky.amap.net.Router
import online.virivky.amap.net.Weather
import online.virivky.amap.net.WeatherInfo
import online.virivky.amap.util.Bg
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Spoločný stav navigácie pre telefón aj Android Auto.
 * Navigáciu spustenú v telefóne vidno v aute a naopak.
 */
object NavigationController {
    enum class Phase { IDLE, ROUTING, PREVIEW, NAVIGATING, ARRIVED }

    fun interface Listener {
        fun onNavigationChanged()
    }

    private lateinit var app: Context
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val voice by lazy { Voice(app) }
    private val announced = HashSet<String>()
    private var request = 0
    private var roadRequest = 0
    private var offRouteCount = 0
    private var lastRerouteAt = 0L
    private var lastSpeedWarningAt = 0L
    private var lastTrafficRefreshAt = 0L

    var phase = Phase.IDLE
        private set
    var destination: Place? = null
        private set

    /** Zastávka pred cieľom (napr. nabíjačka), ak je pridaná. */
    var via: Place? = null
        private set
    var route: Route? = null
        private set

    /** Varianty trasy v náhľade (vybraná je [route]). */
    var routes: List<Route> = emptyList()
        private set

    val selectedRouteIndex: Int
        get() = routes.indexOf(route)
    var progress: Progress? = null
        private set
    var error: String? = null
        private set
    var rerouting = false
        private set

    /** Rýchlostný limit na aktuálnom úseku (km/h), ak je známy. */
    var speedLimit: Int? = null
        private set

    /** Aktuálna rýchlosť v km/h. */
    var speedKmh: Int? = null
        private set

    /** Najbližší radar vpredu (do 1,5 km) a vzdialenosť k nemu. */
    var cameraAhead: Camera? = null
        private set
    var cameraDistance: Double? = null
        private set

    /** Najbližší tunel pred nami (do 1 km) a vzdialenosť k nemu. */
    var tunnelAhead: Tunnel? = null
        private set
    var tunnelDistance: Double? = null
        private set

    /** Počasie v cieli v čase príchodu (Open-Meteo). */
    var destinationWeather: WeatherInfo? = null
        private set
    private var weatherFor: Place? = null
    private var weatherRequest = 0

    /** Blížime sa k cieľu (posledných 1,5 km) – ukážu sa parkoviská pri cieli. */
    var nearDestination = false
        private set

    /** Najbližšia dopravná udalosť na trase (do 3 km) a vzdialenosť k nej. */
    var incidentAhead: Incident? = null
        private set
    var incidentDistance: Double? = null
        private set

    /** Poloha sa odhaduje bez GPS (napr. v tuneli). */
    var estimating = false
        private set

    private var lastFixAt = 0L
    private var lastSpeed = 0.0
    private val ticker = object : Runnable {
        override fun run() {
            estimateWithoutGps()
            if (phase == Phase.NAVIGATING) Bg.main.postDelayed(this, 1000)
        }
    }

    /** Názov cesty, po ktorej práve ideme. */
    val currentRoad: String?
        get() {
            val r = route ?: return null
            val p = progress ?: return null
            val step = r.steps[(p.nextStepIndex - 1).coerceAtLeast(0)]
            return listOf(step.name, step.ref).firstOrNull { it.isNotBlank() }
        }

    /** Počet semaforov do cieľa. */
    val trafficLightsAhead: Int
        get() {
            val r = route ?: return 0
            val traveled = progress?.traveled ?: 0.0
            return r.trafficSignals.count { it > traveled + 5 }
        }

    /** Prejdená časť trasy 0..1. */
    val progressFraction: Float
        get() {
            val r = route ?: return 0f
            val p = progress ?: return 0f
            return if (r.distance > 0) (p.traveled / r.distance).toFloat().coerceIn(0f, 1f) else 0f
        }

    val overSpeed: Boolean
        get() {
            val limit = speedLimit ?: return false
            val speed = speedKmh ?: return false
            return speed > limit + max(3, limit / 10)
        }

    var voiceEnabled: Boolean
        get() = app.getSharedPreferences("amap", Context.MODE_PRIVATE).getBoolean("voice", true)
        set(value) {
            app.getSharedPreferences("amap", Context.MODE_PRIVATE).edit().putBoolean("voice", value).apply()
            if (!value) voice.stop()
            notifyChanged()
        }

    fun init(context: Context) {
        app = context.applicationContext
        LocationTracker.addListener { onLocation(it) }
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        listeners.forEach { it.onNavigationChanged() }
    }

    /** Vypočíta trasu k cieľu a zobrazí náhľad. */
    fun preview(place: Place) {
        if (phase == Phase.NAVIGATING || phase == Phase.ARRIVED) stopInternal()
        destination = place
        via = null
        route = null
        routes = emptyList()
        progress = null
        error = null
        phase = Phase.ROUTING
        Recents.add(place)
        notifyChanged()
        computeRoute(reroute = false)
    }

    /** Počas navigácie zmení cieľ (napr. na parkovisko) bez zastavenia navigácie; inak náhľad. */
    fun navigateTo(place: Place) {
        if (phase != Phase.NAVIGATING) {
            preview(place)
            return
        }
        destination = place
        via = null
        nearDestination = false
        Recents.add(place)
        speak("Nový cieľ ${place.name}.")
        computeRoute(reroute = true)
        notifyChanged()
    }

    /** Predpoveď v cieli pre čas príchodu (raz pre každý cieľ). */
    private fun loadWeather(target: Place, seconds: Double) {
        if (weatherFor == target) return
        weatherFor = target
        destinationWeather = null
        val id = ++weatherRequest
        val arrival = java.time.Instant.now().plusSeconds(seconds.toLong())
        Bg.run({ Weather.at(target.point, arrival) }) { result ->
            if (id != weatherRequest || destination != target) return@run
            destinationWeather = result.getOrNull()
            notifyChanged()
        }
    }

    /** Výber iného variantu trasy v náhľade. */
    fun selectRoute(index: Int) {
        if (phase != Phase.PREVIEW) return
        val chosen = routes.getOrNull(index) ?: return
        if (chosen === route) return
        route = chosen
        clearRoadInfo()
        loadRoadData(chosen)
        notifyChanged()
    }

    /** Doplní názov a adresu cieľa (napr. po zistení adresy bodu z mapy). */
    fun relabel(point: GeoPoint, name: String, address: String) {
        val current = destination ?: return
        if (current.point != point) return
        val place = current.copy(name = name, address = address)
        destination = place
        Recents.add(place)
        notifyChanged()
    }

    /**
     * Počas navigácie pridá zastávku (napr. nabíjačku) a trasa povedie cez ňu do pôvodného cieľa.
     * Mimo navigácie len naplánuje trasu k miestu.
     */
    fun addStop(place: Place) {
        if (phase != Phase.NAVIGATING) {
            preview(place)
            return
        }
        via = place
        speak("Pridávam zastávku ${place.name}.")
        computeRoute(reroute = true)
        notifyChanged()
    }

    /** Zruší zastávku a trasa povedie priamo do cieľa. */
    fun removeStop() {
        if (via == null) return
        via = null
        if (phase == Phase.NAVIGATING) computeRoute(reroute = true)
        notifyChanged()
    }

    fun retry() {
        if (destination == null) return
        error = null
        if (phase != Phase.NAVIGATING) phase = Phase.ROUTING
        notifyChanged()
        computeRoute(reroute = phase == Phase.NAVIGATING)
    }

    fun cancelPreview() {
        if (phase == Phase.ROUTING || phase == Phase.PREVIEW) {
            stopInternal()
            notifyChanged()
        }
    }

    fun start() {
        if (route == null) return
        routes = emptyList()
        nearDestination = false
        phase = Phase.NAVIGATING
        announced.clear()
        offRouteCount = 0
        progress = null
        NavigationService.start(app)
        lastFixAt = SystemClock.elapsedRealtime()
        Bg.main.removeCallbacks(ticker)
        Bg.main.postDelayed(ticker, 1000)
        LocationTracker.last?.let { updateProgress(it) }
        val p = progress
        val step = p?.let { route?.steps?.getOrNull(it.nextStepIndex) }
        val first = if (p != null && step != null) {
            announced.add("far-${(step.location.lat * 1e5).roundToInt()},${(step.location.lon * 1e5).roundToInt()}")
            ". O ${Instructions.spokenDistance(p.distanceToNextStep)} ${Instructions.phrase(step)}."
        } else {
            "."
        }
        if (phase == Phase.NAVIGATING) speak("Navigácia spustená$first")
        TrafficEvents.refreshRoute(route, progress?.traveled ?: 0.0)
        notifyChanged()
    }

    fun stop() {
        stopInternal()
        notifyChanged()
    }

    private fun stopInternal() {
        request++
        phase = Phase.IDLE
        destination = null
        via = null
        route = null
        routes = emptyList()
        destinationWeather = null
        weatherFor = null
        progress = null
        error = null
        rerouting = false
        estimating = false
        Bg.main.removeCallbacks(ticker)
        clearRoadInfo()
        nearDestination = false
        if (Services.atDestination) Services.clear()
        TrafficEvents.refreshRoute(null, 0.0)
        voice.stop()
        NavigationService.stop(app)
    }

    private fun clearRoadInfo() {
        roadRequest++
        speedLimit = null
        speedKmh = null
        cameraAhead = null
        cameraDistance = null
        tunnelAhead = null
        tunnelDistance = null
        incidentAhead = null
        incidentDistance = null
    }

    /** Na pozadí dotiahne limity a radary pre novú trasu. */
    private fun loadRoadData(r: Route) {
        val id = ++roadRequest
        Bg.run({ RoadData.fetch(r) }) { result ->
            if (id != roadRequest || route !== r) return@run
            val data = result.getOrNull() ?: return@run
            data.speedLimits?.let { r.speedLimits = it }
            r.cameras = data.cameras
            r.trafficSignals = data.trafficSignals
            data.tunnels?.let { r.tunnels = it }
            val p = progress
            if (phase == Phase.NAVIGATING && p != null) updateRoadInfo(r, p, LocationTracker.last?.let { speedOf(it) })
            notifyChanged()
        }
    }

    /**
     * Vypočíta trasu z aktuálnej polohy. [silent] = pravidelná kontrola premávky počas jazdy:
     * bez hlásenia „prepočítavam“ a pri chybe sa ponechá pôvodná trasa.
     */
    private fun computeRoute(reroute: Boolean, silent: Boolean = false) {
        val target = destination ?: return
        val here = LocationTracker.last
        if (here == null) {
            if (silent) return
            error = "Čakám na polohu GPS…"
            if (phase == Phase.ROUTING) phase = Phase.PREVIEW
            notifyChanged()
            return
        }
        val id = ++request
        if (!silent) rerouting = reroute
        lastTrafficRefreshAt = SystemClock.elapsedRealtime()
        val remainingBefore = progress?.remainingSeconds
        val heading = if (reroute) LocationTracker.heading else null
        val stop = via?.point
        // V náhľade aj iné varianty trasy na výber.
        val alternatives = phase != Phase.NAVIGATING && stop == null
        Bg.run({ Router.routes(GeoPoint(here), target.point, heading, stop, alternatives) }) { result ->
            if (id != request) return@run
            rerouting = false
            result.onSuccess { found ->
                val newRoute = found.first()
                loadWeather(target, newRoute.duration)
                route = newRoute
                routes = if (phase == Phase.NAVIGATING) emptyList() else found
                error = null
                clearRoadInfo()
                loadRoadData(newRoute)
                if (phase == Phase.NAVIGATING) {
                    progress = null
                    LocationTracker.last?.let { updateProgress(it) }
                    TrafficEvents.refreshRoute(newRoute, progress?.traveled ?: 0.0)
                    val saved = (remainingBefore ?: 0.0) - newRoute.duration
                    if (silent && remainingBefore != null && saved > max(120.0, remainingBefore * 0.1)) {
                        val minutes = (saved / 60).roundToInt()
                        speak("Našla sa rýchlejšia trasa, ušetríte $minutes ${if (minutes <= 4) "minúty" else "minút"}.")
                    }
                } else {
                    phase = Phase.PREVIEW
                }
            }.onFailure {
                if (silent) return@onFailure
                error = "Trasu sa nepodarilo vypočítať. Skontrolujte internet."
                if (phase == Phase.ROUTING) phase = Phase.PREVIEW
            }
            notifyChanged()
        }
    }

    private fun onLocation(location: Location) {
        if (phase != Phase.NAVIGATING) return
        lastFixAt = SystemClock.elapsedRealtime()
        speedOf(location)?.let { lastSpeed = it }
        estimating = false
        updateProgress(location)
        // So živou premávkou každých 5 minút overíme, či nie je rýchlejšia trasa.
        if (phase == Phase.NAVIGATING && Settings.tomtomKey != null && !rerouting &&
            SystemClock.elapsedRealtime() - lastTrafficRefreshAt > 5 * 60_000
        ) {
            computeRoute(reroute = true, silent = true)
        }
        notifyChanged()
    }

    private fun updateProgress(location: Location) {
        val r = route ?: return
        val here = GeoPoint(location)
        val p = r.locate(here, progress?.segmentIndex ?: 0)
        progress = p

        // Pred cieľom ukážeme parkoviská v jeho okolí (na dlhších trasách).
        val target = destination
        if (!nearDestination && via == null && target != null && p.remainingDistance < 1500 && r.distance > 3000) {
            nearDestination = true
            Services.show(PoiCategory.PARKING, target.point, destination = true, radius = 800)
            speak("Blížite sa k cieľu. Na mape sú parkoviská v okolí cieľa.", interrupt = false)
        }

        // Pri zastávke (alebo keď ju trasa už minula) pokračujeme rovno do cieľa.
        via?.let { stop ->
            val stopStep = r.steps.indexOfFirst { it.type == "waypoint" }
            if (Geo.distance(here, stop.point) < 60 || (stopStep >= 0 && p.nextStepIndex > stopStep)) {
                via = null
                speak("Ste pri zastávke ${stop.name}.")
            }
        }

        if (p.remainingDistance < 25 || Geo.distance(here, r.points.last()) < 20) {
            phase = Phase.ARRIVED
            speak("Ste v cieli.")
            NavigationService.stop(app)
            return
        }

        val tolerance = max(40.0, location.accuracy * 1.5)
        offRouteCount = if (p.offRouteDistance > tolerance) offRouteCount + 1 else 0
        val now = SystemClock.elapsedRealtime()
        if (offRouteCount >= 3 && !rerouting && now - lastRerouteAt > 10_000) {
            lastRerouteAt = now
            offRouteCount = 0
            speak("Prepočítavam trasu.")
            computeRoute(reroute = true)
            return
        }
        // Najprv pokyn na odbočenie, upozornenia na radar a rýchlosť sa zaradia za neho.
        val speed = speedOf(location)
        if (!rerouting) announce(r, p, speed ?: 0.0)
        updateRoadInfo(r, p, speed)
    }

    private fun speedOf(location: Location): Double? =
        if (location.hasSpeed()) location.speed.toDouble() else null

    /**
     * Bez GPS (tunel) sa auto posúva po trase poslednou známou rýchlosťou,
     * kým sa signál nevráti (najviac 3 minúty).
     */
    private fun estimateWithoutGps() {
        if (phase != Phase.NAVIGATING || rerouting) return
        val r = route ?: return
        val p = progress ?: return
        val age = SystemClock.elapsedRealtime() - lastFixAt
        if (age < 2500 || age > 180_000 || lastSpeed < 5) return
        val next = r.progressAt(p.traveled + lastSpeed)
        if (next.remainingDistance < 25) return
        progress = next
        estimating = true
        announce(r, next, lastSpeed)
        updateRoadInfo(r, next, lastSpeed)
        notifyChanged()
    }

    private fun updateRoadInfo(r: Route, p: Progress, speedMps: Double?) {
        speedKmh = speedMps?.let { (it * 3.6).roundToInt() }
        speedLimit = r.speedLimits?.getOrNull(p.segmentIndex)?.takeIf { it > 0 }

        // Tunel pred nami, ak je bližšie ako ďalší manéver.
        val tunnel = r.tunnels.firstOrNull { it.endAlong > p.traveled }
        val toTunnel = tunnel?.let { it.startAlong - p.traveled }
        if (tunnel != null && toTunnel != null && toTunnel in 0.0..1000.0 && toTunnel < p.distanceToNextStep) {
            tunnelAhead = tunnel
            tunnelDistance = toTunnel
        } else {
            tunnelAhead = null
            tunnelDistance = null
        }

        // Nehoda, uzávierka či práce na ceste pred nami (TomTom).
        val incident = if (TrafficEvents.aheadRoute === r) TrafficEvents.ahead.firstOrNull { it.along > p.traveled - 30 } else null
        val toIncident = incident?.let { it.along - p.traveled }
        if (incident != null && toIncident != null && toIncident < 3000) {
            incidentAhead = incident.incident
            incidentDistance = max(0.0, toIncident)
            val warnAt = max(1200.0, (speedMps ?: 0.0) * 50)
            if (toIncident < warnAt && toIncident > 50 && announced.add("incident-${incident.incident.id}")) {
                speak("Pozor, o ${Instructions.spokenDistance(toIncident)} ${incident.incident.spoken}.", interrupt = false)
            }
        } else {
            incidentAhead = null
            incidentDistance = null
        }

        val camera = r.cameras.firstOrNull { it.along > p.traveled - 5 }
        val distance = camera?.let { it.along - p.traveled }
        if (camera != null && distance != null && distance < 1500) {
            cameraAhead = camera
            cameraDistance = max(0.0, distance)
            val speed = speedMps ?: 0.0
            if (distance < max(300.0, speed * 20) && announced.add("camera-${(camera.point.lat * 1e5).roundToInt()},${(camera.point.lon * 1e5).roundToInt()}")) {
                speak("Pozor, radar" + (camera.limit?.let { ", rýchlosť $it." } ?: "."), interrupt = false)
            }
        } else {
            cameraAhead = null
            cameraDistance = null
        }

        val now = SystemClock.elapsedRealtime()
        if (overSpeed && now - lastSpeedWarningAt > 60_000) {
            lastSpeedWarningAt = now
            speak("Prekračujete rýchlosť.", interrupt = false)
        }
    }

    private fun announce(r: Route, p: Progress, speedMps: Double) {
        val step = r.steps[p.nextStepIndex]
        val speed = max(speedMps, 8.0)
        val far = (speed * 25).coerceIn(250.0, 1500.0)
        val near = (speed * 7).coerceIn(40.0, 200.0)
        val d = p.distanceToNextStep
        val key = "${(step.location.lat * 1e5).roundToInt()},${(step.location.lon * 1e5).roundToInt()}"
        if (d <= near && step.type != "arrive" && announced.add("near-$key")) {
            announced.add("far-$key")
            var text = Instructions.title(step)
            val then = r.steps.getOrNull(p.nextStepIndex + 1)
            if (then != null && then.type != "arrive" && step.distance < 150) {
                text += ", potom " + Instructions.phrase(then)
            }
            speak("$text.")
        } else if (d in near * 1.6..far && announced.add("far-$key")) {
            speak("O ${Instructions.spokenDistance(d)} ${Instructions.phrase(step)}.")
        }
    }

    private fun speak(text: String, interrupt: Boolean = true) {
        if (voiceEnabled) voice.speak(text, interrupt)
    }
}
