package online.virivky.amap.data

import android.content.Context
import android.content.SharedPreferences
import online.virivky.amap.geo.GeoPoint
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Moje miesta: body uložené tam, kde práve som (napr. v lese, kde nie je adresa), alebo vybrané
 * na mape – s názvom a časom uloženia. Ukazujú sa na mape a dá sa k nim navigovať autom aj pešo.
 */
object SavedPlaces {
    class Saved(val id: Long, val name: String, val point: GeoPoint, val savedAt: Long) {
        fun toPlace() = Place(name, "Moje miesto", point)
    }

    fun interface Listener {
        fun onSavedPlacesChanged()
    }

    private const val KEY = "saved_places"
    private lateinit var prefs: SharedPreferences
    private val listeners = CopyOnWriteArraySet<Listener>()

    /** Všetky miesta, najnovšie prvé. */
    var all: List<Saved> = emptyList()
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("amap", Context.MODE_PRIVATE)
        all = try {
            val array = JSONArray(prefs.getString(KEY, "[]"))
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Saved(o.getLong("id"), o.optString("name"), GeoPoint(o.getDouble("lat"), o.getDouble("lon")), o.optLong("at"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun add(name: String, point: GeoPoint): Saved {
        val now = System.currentTimeMillis()
        val saved = Saved(now, name.ifBlank { "Moje miesto" }, point, now)
        save(listOf(saved) + all)
        return saved
    }

    fun rename(id: Long, name: String) {
        save(all.map { if (it.id == id) Saved(it.id, name.ifBlank { it.name }, it.point, it.savedAt) else it })
    }

    fun remove(id: Long) {
        save(all.filter { it.id != id })
    }

    private fun save(places: List<Saved>) {
        all = places
        val array = JSONArray()
        places.forEach {
            array.put(JSONObject().put("id", it.id).put("name", it.name).put("lat", it.point.lat).put("lon", it.point.lon).put("at", it.savedAt))
        }
        prefs.edit().putString(KEY, array.toString()).apply()
        listeners.forEach { it.onSavedPlacesChanged() }
    }
}
