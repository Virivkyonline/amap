package online.virivky.amap.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** Uložené miesta Domov a Práca. */
object Favorites {
    enum class Kind(val key: String, val label: String) {
        HOME("fav_home", "Domov"),
        WORK("fav_work", "Práca"),
    }

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("amap", Context.MODE_PRIVATE)
    }

    fun get(kind: Kind): Place? = try {
        prefs.getString(kind.key, null)?.let { Place.fromJson(JSONObject(it)).copy(name = kind.label) }
    } catch (e: Exception) {
        null
    }

    fun set(kind: Kind, place: Place) {
        // Pôvodný názov ostane v adrese, aby bolo jasné, čo je uložené.
        val address = listOf(place.name, place.address).filter { it.isNotBlank() && it != kind.label }.joinToString(", ")
        prefs.edit().putString(kind.key, Place(kind.label, address, place.point).toJson().toString()).apply()
    }

    fun clear(kind: Kind) {
        prefs.edit().remove(kind.key).apply()
    }
}
