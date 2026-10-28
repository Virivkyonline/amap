package online.virivky.amap.net

import online.virivky.amap.geo.GeoPoint
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Predpoveď v cieli v čase príchodu. */
class WeatherInfo(val temperature: Int, val code: Int, val rainChance: Int?, val localTime: LocalDateTime) {
    /** Napr. „okolo 14:00: 12 °C, dážď (60 %)“. */
    val label: String
        get() {
            val chance = rainChance?.takeIf { it >= 20 }?.let { " ($it %)" }.orEmpty()
            return "okolo ${localTime.format(DateTimeFormatter.ofPattern("H:mm"))}: $temperature °C, ${Weather.describe(code)}$chance"
        }
}

/** Počasie z Open-Meteo (bez kľúča). */
object Weather {
    fun at(point: GeoPoint, arrival: Instant): WeatherInfo? {
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                "&hourly=temperature_2m,weather_code,precipitation_probability&forecast_days=3&timezone=auto",
            point.lat, point.lon,
        )
        return parse(JSONObject(Http.getString(url)), arrival)
    }

    fun parse(json: JSONObject, arrival: Instant): WeatherInfo? {
        val hourly = json.optJSONObject("hourly") ?: return null
        val times = hourly.optJSONArray("time") ?: return null
        val temperatures = hourly.optJSONArray("temperature_2m") ?: return null
        val codes = hourly.optJSONArray("weather_code") ?: return null
        val chances = hourly.optJSONArray("precipitation_probability")
        // Časy sú miestne v cieli; príchod prepočítame do toho istého pásma.
        val offset = ZoneOffset.ofTotalSeconds(json.optInt("utc_offset_seconds", 0))
        val local = LocalDateTime.ofInstant(arrival, offset)
        var best = -1
        var bestGap = Long.MAX_VALUE
        for (i in 0 until times.length()) {
            val t = try {
                LocalDateTime.parse(times.getString(i))
            } catch (e: Exception) {
                continue
            }
            val gap = abs(java.time.Duration.between(local, t).toMinutes())
            if (gap < bestGap) {
                bestGap = gap
                best = i
            }
        }
        if (best < 0 || bestGap > 90) return null
        if (temperatures.isNull(best) || codes.isNull(best)) return null
        return WeatherInfo(
            temperature = temperatures.getDouble(best).roundToInt(),
            code = codes.getInt(best),
            rainChance = if (chances == null || chances.isNull(best)) null else chances.getInt(best),
            localTime = LocalDateTime.parse(times.getString(best)),
        )
    }

    /** Kód počasia WMO po slovensky. */
    fun describe(code: Int): String = when (code) {
        0 -> "jasno"
        1 -> "prevažne jasno"
        2 -> "polooblačno"
        3 -> "zamračené"
        45, 48 -> "hmla"
        51, 53, 55 -> "mrholenie"
        56, 57 -> "mrznúce mrholenie"
        61 -> "slabý dážď"
        63 -> "dážď"
        65 -> "silný dážď"
        66, 67 -> "mrznúci dážď"
        71 -> "slabé sneženie"
        73 -> "sneženie"
        75 -> "silné sneženie"
        77 -> "snehové zrná"
        80, 81, 82 -> "prehánky"
        85, 86 -> "snehové prehánky"
        95 -> "búrka"
        96, 99 -> "búrka s krupobitím"
        else -> "počasie neznáme"
    }
}
