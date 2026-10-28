package online.virivky.amap.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Http {
    // OpenStreetMap služby vyžadujú, aby sa aplikácia identifikovala.
    const val USER_AGENT = "amap-android/1.0 (personal navigation app; github.com/Virivkyonline/amap)"

    fun get(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept-Language", "sk,cs;q=0.8,en;q=0.5")
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return connection.inputStream.use { it.readBytes() }
        } catch (e: IOException) {
            connection.disconnect()
            throw e
        }
    }

    fun getString(url: String) = String(get(url), Charsets.UTF_8)
}
