package online.virivky.amap.data

import android.content.Context
import android.content.SharedPreferences
import online.virivky.amap.geo.Geo
import org.json.JSONArray

/** Naposledy použité ciele – v aute sa počas jazdy nedá písať, takže sú dôležité. */
object Recents {
    private const val KEY = "recents"
    private const val MAX = 15
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("amap", Context.MODE_PRIVATE)
    }

    fun all(): List<Place> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { Place.fromJson(array.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(place: Place) {
        val list = all().filterNot { Geo.distance(it.point, place.point) < 50 }.toMutableList()
        list.add(0, place)
        val array = JSONArray()
        list.take(MAX).forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    fun remove(place: Place) {
        val array = JSONArray()
        all().filterNot { it == place }.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }
}
