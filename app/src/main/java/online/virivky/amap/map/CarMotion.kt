package online.virivky.amap.map

import online.virivky.amap.geo.Geo
import online.virivky.amap.geo.GeoPoint
import online.virivky.amap.nav.Route
import kotlin.math.abs
import kotlin.math.exp

/**
 * Plynulý pohyb auta na mape. Poloha z GPS prichádza raz za sekundu; auto sa medzi dvoma polohami
 * posúva rovnomerne po snímkach (na trase po jej tvare, mimo trasy priamo) a otáča sa postupne,
 * takže neskáče. Zobrazená poloha je o jeden interval GPS pozadu, aby auto nikdy necúvalo.
 */
class CarMotion {
    private var route: Route? = null
    private var fromAlong = 0.0
    private var toAlong = 0.0
    private var from: GeoPoint? = null
    private var to: GeoPoint? = null
    private var target: GeoPoint? = null
    private var targetHeading: Double? = null
    private var startMs = 0L
    private var durationMs = 0L
    private var lastTargetMs = 0L
    private var lastStepMs = 0L

    /** Zobrazená poloha auta. */
    var point: GeoPoint? = null
        private set

    /** Zobrazený smer auta v stupňoch (null = zatiaľ neznámy). */
    var bearing: Double? = null
        private set

    /** Zobrazená vzdialenosť od začiatku trasy, ak auto ide po trase [onRoute]. */
    var along: Double? = null
        private set

    val onRoute: Route?
        get() = if (along != null) route else null

    /**
     * Nová poloha. [route] a [routeAlong] = poloha na trase (počas navigácie), inak null.
     * Vráti true, ak sa začal nový pohyb (treba kresliť snímky).
     */
    fun moveTo(now: Long, point: GeoPoint, heading: Double?, route: Route?, routeAlong: Double?): Boolean {
        targetHeading = heading
        if (point.lat == target?.lat && point.lon == target?.lon && route === this.route) return false
        target = point
        val shown = this.point
        val interval = (now - lastTargetMs).coerceIn(MIN_MS, MAX_MS)
        lastTargetMs = now
        val shownAlong = along
        if (route != null && routeAlong != null) {
            if (route === this.route && shownAlong != null && abs(routeAlong - shownAlong) < JUMP_M) {
                // Po trase; malý posun dozadu (nepresnosť GPS) ignorujeme, auto len zastane.
                fromAlong = shownAlong
                toAlong = maxOf(routeAlong, shownAlong)
            } else {
                // Nová trasa alebo veľký skok: hneď na miesto.
                fromAlong = routeAlong
                toAlong = routeAlong
            }
            this.route = route
            along = fromAlong
            from = null
            to = null
        } else {
            this.route = null
            along = null
            from = if (shown != null && Geo.distance(shown, point) < JUMP_M) shown else point
            to = point
        }
        startMs = now
        durationMs = interval
        lastStepMs = now
        step(now)
        return true
    }

    /** Posunie zobrazenie na čas [now]; vráti true, kým sa auto ešte hýbe alebo otáča. */
    fun step(now: Long): Boolean {
        val t = if (durationMs <= 0) 1.0 else ((now - startMs).toDouble() / durationMs).coerceIn(0.0, 1.0)
        val r = route
        val wanted: Double?
        if (r != null && along != null) {
            val a = fromAlong + (toAlong - fromAlong) * t
            along = a
            point = r.pointAt(a)
            // Smer z tvaru trasy okolo auta (bez skokov na zlomoch čiary).
            val back = r.pointAt(a - 6)
            val ahead = r.pointAt(a + 8)
            wanted = if (Geo.distance(back, ahead) > 1) Geo.bearing(back, ahead) else targetHeading
        } else {
            val f = from
            val g = to
            point = if (f != null && g != null) Geo.interpolate(f, g, t) else g
            wanted = targetHeading
        }
        val dt = (now - lastStepMs).coerceAtLeast(0L)
        lastStepMs = now
        val current = bearing
        var turning = false
        if (wanted != null) {
            if (current == null) {
                bearing = wanted
            } else {
                val diff = ((wanted - current + 540) % 360) - 180
                val k = 1 - exp(-dt / TURN_MS)
                bearing = (current + diff * k + 360) % 360
                turning = abs(diff) > 0.5
            }
        }
        return t < 1 || turning
    }

    private companion object {
        const val MIN_MS = 250L
        const val MAX_MS = 1500L
        /** Väčší skok (nová trasa, prvá poloha) sa neanimuje. */
        const val JUMP_M = 400.0
        /** Časová konštanta otáčania auta. */
        const val TURN_MS = 220.0
    }
}
