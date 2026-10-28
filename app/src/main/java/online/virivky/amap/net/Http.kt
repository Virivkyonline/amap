package online.virivky.amap.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Chyba HTTP so stavovým kódom (napr. 403 pri neplatnom kľúči alebo vyčerpanom limite). */
class HttpException(val code: Int) : IOException("HTTP $code")

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
            if (code !in 200..299) throw HttpException(code)
            return connection.inputStream.use { it.readBytes() }
        } catch (e: IOException) {
            connection.disconnect()
            throw e
        }
    }

    fun getString(url: String) = String(get(url), Charsets.UTF_8)

    /** POST formulára (application/x-www-form-urlencoded). */
    fun post(url: String, form: String): String = post(url, form, "application/x-www-form-urlencoded")

    fun post(url: String, body: String, contentType: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 90_000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Content-Type", contentType)
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) throw HttpException(code)
            return connection.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } catch (e: IOException) {
            connection.disconnect()
            throw e
        }
    }
}
