package online.virivky.amap.util

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Stav otváracích hodín: otvorené/zatvorené a text na zobrazenie. */
class HoursStatus(val open: Boolean, val label: String)

/**
 * Otváracie hodiny z OpenStreetMap (formát „Mo-Fr 08:00-18:00; Sa 08:00-12:00“).
 * Pokrýva bežné zápisy; zložité pravidlá (sviatky, mesiace) vráti ako neznáme.
 */
object OpeningHours {
    private val DAYS = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
    private const val DAY = "(?:Mo|Tu|We|Th|Fr|Sa|Su|PH)"
    private val RULE = Regex(
        """($DAY(?:\s*[-,]\s*$DAY)*)?\s*((?:\d{1,2}:\d{2}\s*-\s*\d{1,2}:\d{2}\+?(?:\s*,\s*(?=\d))?)+|off|closed)"""
    )
    private val RANGE = Regex("""(\d{1,2}):(\d{2})\s*-\s*(\d{1,2}):(\d{2})""")
    private val TIME = DateTimeFormatter.ofPattern("H:mm")

    fun status(spec: String?, now: LocalDateTime = LocalDateTime.now()): HoursStatus? {
        val s = spec?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (s == "24/7") return HoursStatus(true, "Otvorené nonstop")
        val today = now.dayOfWeek.value - 1
        val minute = now.hour * 60 + now.minute
        var anyRule = false
        var todayTimes: String? = null
        for (m in RULE.findAll(s)) {
            anyRule = true
            val days = m.groupValues[1]
            val set = if (days.isBlank()) (0..6).toSet() else parseDays(days) ?: continue
            // Neskoršie pravidlo pre ten istý deň prepisuje skoršie.
            if (today in set) todayTimes = m.groupValues[2]
        }
        if (!anyRule) return null
        val times = todayTimes ?: return HoursStatus(false, "Dnes zatvorené")
        if (times == "off" || times == "closed") return HoursStatus(false, "Dnes zatvorené")
        var nextOpen: Int? = null
        for (r in RANGE.findAll(times)) {
            val start = r.groupValues[1].toInt() * 60 + r.groupValues[2].toInt()
            val end = r.groupValues[3].toInt() * 60 + r.groupValues[4].toInt()
            val open = if (end <= start) minute >= start || minute < end else minute in start until end
            if (open) return HoursStatus(true, "Otvorené do " + label(end))
            if (start > minute && (nextOpen == null || start < nextOpen)) nextOpen = start
        }
        return HoursStatus(false, nextOpen?.let { "Zatvorené · otvára o " + label(it) } ?: "Zatvorené")
    }

    /** Stav z časových úsekov TomTom (presné dátumy a časy na 7 dní). */
    fun status(ranges: List<Pair<LocalDateTime, LocalDateTime>>, now: LocalDateTime = LocalDateTime.now()): HoursStatus? {
        if (ranges.isEmpty()) return null
        ranges.firstOrNull { !now.isBefore(it.first) && now.isBefore(it.second) }?.let { (_, end) ->
            return HoursStatus(true, if (end.toLocalDate() == now.toLocalDate()) "Otvorené do " + end.format(TIME) else "Otvorené")
        }
        val next = ranges.map { it.first }.filter { it.isAfter(now) }.minOrNull()
        return HoursStatus(
            false,
            if (next != null && next.toLocalDate() == now.toLocalDate()) "Zatvorené · otvára o " + next.format(TIME) else "Zatvorené",
        )
    }

    private fun label(minutes: Int): String =
        if (minutes >= 24 * 60) "polnoci" else LocalTime.of(minutes / 60 % 24, minutes % 60).format(TIME)

    /** „Mo-Fr,Su“ → {0,1,2,3,4,6}; null, ak ide len o sviatky. */
    private fun parseDays(text: String): Set<Int>? {
        val result = HashSet<Int>()
        for (part in text.split(',').map { it.trim() }) {
            if (part.isEmpty() || part == "PH") continue
            val bounds = part.split('-').map { it.trim() }
            val a = DAYS.indexOf(bounds.first())
            val b = DAYS.indexOf(bounds.last())
            if (a < 0 || b < 0) continue
            var d = a
            while (true) {
                result.add(d)
                if (d == b) break
                d = (d + 1) % 7
            }
        }
        return result.ifEmpty { null }
    }
}
