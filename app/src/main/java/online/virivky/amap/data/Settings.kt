package online.virivky.amap.data

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArraySet

/** Nastavenia aplikácie (kľúč TomTom pre živú premávku). */
object Settings {
    private lateinit var prefs: SharedPreferences
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("amap", Context.MODE_PRIVATE)
    }

    /** Kľúč k službám TomTom (premávka, trasa podľa premávky). */
    var tomtomKey: String?
        get() = prefs.getString("tomtom_key", null)?.trim()?.ifEmpty { null }
        set(value) {
            prefs.edit().putString("tomtom_key", value?.trim()).apply()
            listeners.forEach { it() }
        }

    /** Zobrazovať farby premávky na cestách. */
    var showTraffic: Boolean
        get() = prefs.getBoolean("show_traffic", true)
        set(value) {
            prefs.edit().putBoolean("show_traffic", value).apply()
            listeners.forEach { it() }
        }

    /** Kľúč, ak je premávka na mape zapnutá. */
    val trafficLayerKey: String?
        get() = if (showTraffic) tomtomKey else null

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }
}
