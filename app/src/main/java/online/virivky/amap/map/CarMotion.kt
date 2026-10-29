package online.virivky.amap.map

import online.virivky.amap.geo.Geo
import online.virivky.amap.geo.GeoPoint
import online.virivky.amap.nav.Route
import kotlin.math.abs
import kotlin.math.exp

/**
 * Plynulý pohyb auta na mape. Poloha z GPS prichádza raz za sekundu a je o chvíľu staršia; auto sa
 * po snímkach posúva rovnomerne tam, kde podľa rýchlosti bude pri ďalšej polohe (na trase po jej tvare,
 * mimo trasy priamo), takže neskáče ani nezaostáva. Dozadu sa posunie len postojačky (keď predpoveď
 * prestrelila pri brzdení), inak auto nikdy necúva.
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

    /** Pešo: smer podľa kompasu ([turnTo]) aj na trase, nie podľa tvaru trasy. */
    var followHeading = false

    /** Nový smer (kompas) bez novej polohy; vráti true, ak sa treba otáčať. */
    fun turnTo(heading: Double?): Boolean {
        if (heading == null || heading == targetHeading) return false
        targetHeading = heading
        return true
    }

    /**
     * Nová poloha. [route] a [routeAlong] = poloha na trase (počas navigácie), inak null. [speed] = rýchlosť
     * v m/s z GPS (0 = nepredpovedať), [ageMs] = ako dávno bola poloha zmeraná.
     * Vráti true, ak sa začal nový pohyb (treba kresliť snímky).
     */
    fun moveTo(
        now: Long, point: GeoPoint, heading: Double?, route: Route?, routeAlong: Double?,
        speed: Double = 0.0, ageMs: Long = 0,
    ): Boolean {
        targetHeading = heading
        if (point.lat == target?.lat && point.lon == target?.lon && route === this.route) return false
        target = point
        val shown = this.point
        val interval = (now - lastTargetMs).coerceIn(MIN_MS, MAX_MS)
        lastTargetMs = now
        // Koľko auto prejde od merania polohy po teraz a po ďalšiu polohu (o jeden interval).
        val moving = speed >= MIN_SPEED
        val nowAhead = if (moving) minOf(speed * ageMs / 1000.0, MAX_LEAD_M) else 0.0
        val nextAhead = if (moving) minOf(speed * (ageMs + interval) / 1000.0, MAX_LEAD_M) else 0.0
        val shownAlong = along
        if (route != null && routeAlong != null) {
            val wanted = minOf(routeAlong + nextAhead, route.distance)
            if (route === this.route && shownAlong != null && abs(routeAlong - shownAlong) < JUMP_M) {
                fromAlong = shownAlong
                // Malý posun dozadu (nepresnosť GPS) ignorujeme, auto len zastane; postojačky sa vráti na miesto.
                toAlong = if (wanted >= shownAlong || (!moving && shownAlong - wanted > BACK_M)) wanted else shownAlong
            } else {
                // Nová trasa alebo veľký skok: hneď na miesto.
                fromAlong = minOf(routeAlong + nowAhead, route.distance)
                toAlong = wanted
            }
            this.route = route
            along = fromAlong
            from = null
            to = null
        } else {
            this.route = null
            along = null
            val ahead = if (heading != null && nextAhead > 0) Geo.offset(point, heading - 90, nextAhead) else point
            from = if (shown != null && Geo.distance(shown, point) < JUMP_M) shown else point
            to = ahead
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
            wanted = if (followHeading && targetHeading != null) targetHeading
            else if (Geo.distance(back, ahead) > 1) Geo.bearing(back, ahead) else targetHeading
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
        /** Pod touto rýchlosťou (m/s) sa poloha nepredpovedá (šum GPS postojačky). */
        const val MIN_SPEED = 0.8
        /** Predpoveď najviac o toľko metrov (výpadok GPS, stará poloha). */
        const val MAX_LEAD_M = 60.0
        /** Postojačky sa auto vráti dozadu, ak ho predpoveď pri brzdení posunula ďalej o viac ako toľko metrov. */
        const val BACK_M = 1.5
        /** Časová konštanta otáčania auta. */
        const val TURN_MS = 220.0
    }
}
