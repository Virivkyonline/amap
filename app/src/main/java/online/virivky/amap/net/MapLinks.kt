package online.virivky.amap.net

import online.virivky.amap.geo.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/** Miesto zdieľané z inej aplikácie: názov, poloha (ak sa dala zistiť), inak text na hľadanie. */
class SharedPlace(val name: String?, val point: GeoPoint?, val query: String?)

/**
 * Odkazy z Google Máp (a iných máp) poslané cez „Zdieľať“: v texte nájde odkaz, skrátený
 * (maps.app.goo.gl) rozbalí presmerovaniami a vytiahne súradnice miesta.
 */
object MapLinks {
    private val LINK = Regex("""https?://\S+""")
    /** Špendlík miesta v odkaze Google Máp (presnejší ako stred mapy). */
    private val PIN = Regex("""!3d(-?\d+\.\d+)!4d(-?\d+\.\d+)""")
    private val PARAM = Regex("""[?&](?:q|query|ll|daddr|destination|center)=(-?\d+\.\d+)(?:,|%2C)\s*\+?(-?\d+\.\d+)""")
    private val AT = Regex("""@(-?\d+\.\d+),(-?\d+\.\d+)""")
    /** Obrázok náhľadu na stránke miesta: center=šírka%2Cdĺžka. */
    private val PAGE_CENTER = Regex("""center=(-?\d+\.\d+)(?:%2C|,)(-?\d+\.\d+)""")
    private val PLACE_NAME = Regex("""/maps/place/([^/@?]+)""")
    private val QUERY = Regex("""[?&](?:q|query)=([^&]+)""")
    private val COORDINATES = Regex("""(-?\d{1,2}\.\d{3,})\s*,\s*(-?\d{1,3}\.\d{3,})""")
    private const val BROWSER = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    /** Rozbor zdieľaného textu. Môže čítať z internetu – volať na pozadí. */
    fun resolve(text: String): SharedPlace? {
        val link = LINK.find(text)?.value?.trimEnd('.', ',', ')', ']')
        // Google Mapy zdieľajú „Názov\nAdresa\nodkaz“ – názov je prvý riadok pred odkazom.
        val label = (if (link != null) text.substringBefore(link) else text).lines()
            .map { it.trim() }.firstOrNull { it.isNotBlank() && COORDINATES.find(it)?.value != it }
        if (link == null) {
            val m = COORDINATES.find(text) ?: return null
            return SharedPlace(label, GeoPoint(m.groupValues[1].toDouble(), m.groupValues[2].toDouble()), null)
        }
        val expanded = expand(link)
        val full = decode(expanded)
        val point = pointIn(full) ?: pageCenter(expanded)
        val name = label ?: PLACE_NAME.find(full)?.groupValues?.get(1)?.replace('+', ' ')?.trim()
        val query = if (point == null) QUERY.find(full)?.groupValues?.get(1)?.replace('+', ' ')?.trim() ?: name else null
        return SharedPlace(name, point, query)
    }

    /** Súradnice priamo v odkaze (špendlík, parameter, stred mapy). */
    fun pointIn(url: String): GeoPoint? {
        val m = PIN.find(url) ?: PARAM.find(url) ?: AT.find(url) ?: return null
        val lat = m.groupValues[1].toDouble()
        val lon = m.groupValues[2].toDouble()
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return GeoPoint(lat, lon)
    }

    /** Skrátený odkaz rozbalí presmerovaniami (najviac 6), kým v ňom nie sú súradnice. */
    private fun expand(link: String): String {
        var current = link
        repeat(6) {
            if (pointIn(decode(current)) != null) return current
            val connection = try {
                URL(current).openConnection() as HttpURLConnection
            } catch (e: Exception) {
                return current
            }
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.setRequestProperty("User-Agent", BROWSER)
                val code = connection.responseCode
                val location = connection.getHeaderField("Location")
                if (code !in 300..399 || location == null) return current
                current = URL(URL(current), location).toString()
            } catch (e: Exception) {
                return current
            } finally {
                connection.disconnect()
            }
        }
        return current
    }

    /**
     * Odkaz bez súradníc (len názov miesta): poloha zo stránky Google Máp – náhľadový obrázok
     * má stred mapy na mieste. Súhlas s cookies (EÚ) sa preskočí.
     */
    private fun pageCenter(url: String): GeoPoint? {
        if (!url.contains("google.")) return null
        // Stránka súhlasu s cookies v EÚ nesie pôvodný (zakódovaný) odkaz v parametri continue.
        val target = Regex("""[?&]continue=([^&]+)""").find(url)?.groupValues?.get(1)?.let { decode(it) } ?: url
        pointIn(decode(target))?.let { return it }
        return try {
            val connection = URL(target).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", BROWSER)
            connection.setRequestProperty("Cookie", "CONSENT=YES+; SOCS=CAESEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg")
            val html = connection.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
            connection.disconnect()
            val m = PAGE_CENTER.find(html) ?: return null
            GeoPoint(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
        } catch (e: Exception) {
            null
        }
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) {
        s
    }
}
