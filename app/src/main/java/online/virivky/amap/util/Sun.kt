package online.virivky.amap.util

import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/** Poloha slnka (približný vzorec NOAA, presnosť na pár minút). */
object Sun {
    /** Výška slnka nad obzorom v stupňoch. */
    fun elevation(lat: Double, lon: Double, time: ZonedDateTime): Double {
        val utc = time.withZoneSameInstant(ZoneOffset.UTC)
        val hour = utc.hour + utc.minute / 60.0 + utc.second / 3600.0
        val g = 2 * PI / 365 * (utc.dayOfYear - 1 + (hour - 12) / 24)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) +
            0.000907 * sin(2 * g) - 0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val trueSolarMinutes = hour * 60 + eqTime + 4 * lon
        val hourAngle = Math.toRadians(trueSolarMinutes / 4 - 180)
        val la = Math.toRadians(lat)
        val cosZenith = (sin(la) * sin(decl) + cos(la) * cos(decl) * cos(hourAngle)).coerceIn(-1.0, 1.0)
        return 90 - Math.toDegrees(acos(cosZenith))
    }

    /** Je tma? (Slnko pod obzorom viac ako 3°, teda v polovici občianskeho súmraku.) */
    fun isNight(lat: Double, lon: Double, time: ZonedDateTime = ZonedDateTime.now()): Boolean =
        elevation(lat, lon, time) < -3
}
