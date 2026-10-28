package online.virivky.amap.nav

import android.content.Context
import android.location.Location
import android.os.SystemClock
import online.virivky.amap.data.Place
import online.virivky.amap.data.Recents
import online.virivky.amap.geo.Geo
import online.virivky.amap.geo.GeoPoint
import online.virivky.amap.location.LocationTracker
import online.virivky.amap.net.Router
import online.virivky.amap.util.Bg
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.max

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
    private var offRouteCount = 0
    private var lastRerouteAt = 0L

    var phase = Phase.IDLE
        private set
    var destination: Place? = null
        private set
    var route: Route? = null
        private set
    var progress: Progress? = null
        private set
    var error: String? = null
        private set
    var rerouting = false
        private set

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
        route = null
        progress = null
        error = null
        phase = Phase.ROUTING
        Recents.add(place)
        notifyChanged()
        computeRoute(reroute = false)
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
        phase = Phase.NAVIGATING
        announced.clear()
        offRouteCount = 0
        progress = null
        NavigationService.start(app)
        LocationTracker.last?.let { updateProgress(it) }
        val p = progress
        val step = p?.let { route?.steps?.getOrNull(it.nextStepIndex) }
        val first = if (p != null && step != null) {
            announced.add("far-${step.startIndex}")
            ". O ${Instructions.spokenDistance(p.distanceToNextStep)} ${Instructions.phrase(step)}."
        } else {
            "."
        }
        if (phase == Phase.NAVIGATING) speak("Navigácia spustená$first")
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
        route = null
        progress = null
        error = null
        rerouting = false
        voice.stop()
        NavigationService.stop(app)
    }

    private fun computeRoute(reroute: Boolean) {
        val target = destination ?: return
        val here = LocationTracker.last
        if (here == null) {
            error = "Čakám na polohu GPS…"
            if (phase == Phase.ROUTING) phase = Phase.PREVIEW
            notifyChanged()
            return
        }
        val id = ++request
        rerouting = reroute
        val heading = if (reroute) LocationTracker.heading else null
        Bg.run({ Router.route(GeoPoint(here), target.point, heading) }) { result ->
            if (id != request) return@run
            rerouting = false
            result.onSuccess { newRoute ->
                route = newRoute
                error = null
                if (phase == Phase.NAVIGATING) {
                    progress = null
                    announced.clear()
                    LocationTracker.last?.let { updateProgress(it) }
                } else {
                    phase = Phase.PREVIEW
                }
            }.onFailure {
                error = "Trasu sa nepodarilo vypočítať. Skontrolujte internet."
                if (phase == Phase.ROUTING) phase = Phase.PREVIEW
            }
            notifyChanged()
        }
    }

    private fun onLocation(location: Location) {
        if (phase != Phase.NAVIGATING) return
        updateProgress(location)
        notifyChanged()
    }

    private fun updateProgress(location: Location) {
        val r = route ?: return
        val here = GeoPoint(location)
        val p = r.locate(here, progress?.segmentIndex ?: 0)
        progress = p

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
        if (!rerouting) announce(r, p, location)
    }

    private fun announce(r: Route, p: Progress, location: Location) {
        val step = r.steps[p.nextStepIndex]
        val speed = max(if (location.hasSpeed()) location.speed.toDouble() else 0.0, 8.0)
        val far = (speed * 25).coerceIn(250.0, 1500.0)
        val near = (speed * 7).coerceIn(40.0, 200.0)
        val d = p.distanceToNextStep
        val key = step.startIndex
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

    private fun speak(text: String) {
        if (voiceEnabled) voice.speak(text)
    }
}
