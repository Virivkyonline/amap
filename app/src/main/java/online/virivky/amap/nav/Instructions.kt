package online.virivky.amap.nav

import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** Slovenské texty pokynov, vzdialeností a časov. */
object Instructions {
    private val SK = Locale.forLanguageTag("sk-SK")

    private fun isLeft(modifier: String?) = modifier?.contains("left") == true
    private fun isRight(modifier: String?) = modifier?.contains("right") == true

    private fun side(modifier: String?) = when {
        isLeft(modifier) -> "vľavo"
        isRight(modifier) -> "vpravo"
        else -> null
    }

    private fun turn(modifier: String?): String = when (modifier) {
        "uturn" -> "Otočte sa"
        "sharp right" -> "Odbočte ostro vpravo"
        "right" -> "Odbočte vpravo"
        "slight right" -> "Odbočte mierne vpravo"
        "sharp left" -> "Odbočte ostro vľavo"
        "left" -> "Odbočte vľavo"
        "slight left" -> "Odbočte mierne vľavo"
        else -> "Pokračujte rovno"
    }

    /** Pokyn na zobrazenie, napr. „Odbočte vpravo“. */
    fun title(step: RouteStep): String {
        val m = step.modifier
        return when (step.type) {
            "depart" -> "Vyrazte"
            "arrive" -> side(m)?.let { "Cieľ je $it" } ?: "Cieľ"
            "roundabout", "rotary" ->
                step.exit?.takeIf { it > 0 }?.let { "Na kruhovom objazde použite $it. výjazd" }
                    ?: "Vojdite na kruhový objazd"
            "roundabout turn" -> "Na kruhovom objazde " + turn(m).replaceFirstChar { it.lowercase(SK) }
            "exit roundabout", "exit rotary" -> "Opustite kruhový objazd"
            "fork" -> side(m)?.let { "Na rozdvojení sa držte $it" } ?: "Na rozdvojení pokračujte rovno"
            "merge" -> side(m)?.let { "Zaraďte sa $it" } ?: "Zaraďte sa"
            "on ramp" -> side(m)?.let { "Odbočte $it na privádzač" } ?: "Pokračujte na privádzač"
            "off ramp" -> side(m)?.let { "Použite výjazd $it" } ?: "Použite výjazd"
            "end of road" -> "Na konci cesty " + turn(m).replaceFirstChar { it.lowercase(SK) }
            "continue", "new name", "notification", "use lane" -> when (m) {
                "slight left" -> "Držte sa vľavo"
                "slight right" -> "Držte sa vpravo"
                null, "straight" -> "Pokračujte rovno"
                else -> turn(m)
            }
            else -> turn(m)
        }
    }

    /** Pokyn do hlasovej navigácie (malým písmenom, aby sa dal spojiť so vzdialenosťou). */
    fun phrase(step: RouteStep): String =
        if (step.type == "arrive") "budete v cieli"
        else title(step).replaceFirstChar { it.lowercase(SK) }

    /** Názov cesty, na ktorú sa odbočuje. */
    fun road(step: RouteStep): String? {
        val parts = listOf(step.ref, step.name).filter { it.isNotBlank() }.distinct()
        if (parts.isNotEmpty()) return parts.joinToString(" · ")
        return step.destinations.takeIf { it.isNotBlank() }?.let { "smer $it" }
    }

    fun displayDistance(meters: Double): String = when {
        meters < 1000 -> "${roundMeters(meters)} m"
        meters < 10_000 -> String.format(SK, "%.1f km", meters / 1000)
        else -> "${(meters / 1000).roundToInt()} km"
    }

    fun roundMeters(meters: Double): Int = when {
        meters < 300 -> max(10, (meters / 10).roundToInt() * 10)
        else -> (meters / 50).roundToInt() * 50
    }

    fun spokenDistance(meters: Double): String {
        if (meters < 950) return "${max(50, (meters / 50).roundToInt() * 50)} metrov"
        val tenths = if (meters < 10_000) (meters / 100).roundToInt() else (meters / 1000).roundToInt() * 10
        if (tenths % 10 != 0) return String.format(SK, "%.1f kilometra", tenths / 10.0)
        return when (val km = tenths / 10) {
            1 -> "1 kilometer"
            in 2..4 -> "$km kilometre"
            else -> "$km kilometrov"
        }
    }

    fun displayDuration(seconds: Double): String {
        val minutes = max(1, (seconds / 60).roundToInt())
        return if (minutes < 60) "$minutes min" else String.format(SK, "%d h %02d min", minutes / 60, minutes % 60)
    }
}
