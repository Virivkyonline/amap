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

    /** Hlasové upozornenia: radary a úsekové merania, dopravné udalosti, prekročenie rýchlosti. */
    var warnCameras: Boolean
        get() = prefs.getBoolean("warn_cameras", true)
        set(value) = prefs.edit().putBoolean("warn_cameras", value).apply()

    var warnIncidents: Boolean
        get() = prefs.getBoolean("warn_incidents", true)
        set(value) = prefs.edit().putBoolean("warn_incidents", value).apply()

    var warnSpeed: Boolean
        get() = prefs.getBoolean("warn_speed", true)
        set(value) = prefs.edit().putBoolean("warn_speed", value).apply()

    /** O koľko km/h smie rýchlosť prekročiť limit; -1 = automaticky (10 %, aspoň 3 km/h). */
    var speedTolerance: Int
        get() = prefs.getInt("speed_tolerance", -1)
        set(value) = prefs.edit().putInt("speed_tolerance", value).apply()

    /** Pohľad na mapu počas navigácie (3D, 2D v smere jazdy, 2D sever hore). */
    var mapPerspective: MapPerspective
        get() = MapPerspective.entries.firstOrNull { it.name == prefs.getString("map_view", null) } ?: MapPerspective.THREE_D
        set(value) {
            prefs.edit().putString("map_view", value.name).apply()
            listeners.forEach { it() }
        }

    /** Denné či nočné farby mapy. */
    var mapTheme: MapTheme
        get() = MapTheme.entries.firstOrNull { it.name == prefs.getString("map_theme", null) } ?: MapTheme.AUTO
        set(value) {
            prefs.edit().putString("map_theme", value.name).apply()
            listeners.forEach { it() }
        }

    /** Ikona polohy na mape: tvar a farba. */
    var vehicleModel: VehicleModel
        get() = VehicleModel.entries.firstOrNull { it.name == prefs.getString("vehicle_model", null) } ?: VehicleModel.SEDAN
        set(value) {
            prefs.edit().putString("vehicle_model", value.name).apply()
            listeners.forEach { it() }
        }

    var vehicleColor: VehicleColor
        get() = VehicleColor.entries.firstOrNull { it.name == prefs.getString("vehicle_color", null) } ?: VehicleColor.WHITE
        set(value) {
            prefs.edit().putString("vehicle_color", value.name).apply()
            listeners.forEach { it() }
        }

    /** Čomu sa vyhýbať pri výpočte trasy. */
    var routeOptions: RouteOptions
        get() = RouteOptions(
            avoidMotorways = prefs.getBoolean("avoid_motorways", false),
            avoidTolls = prefs.getBoolean("avoid_tolls", false),
            avoidFerries = prefs.getBoolean("avoid_ferries", false),
        )
        set(value) {
            prefs.edit()
                .putBoolean("avoid_motorways", value.avoidMotorways)
                .putBoolean("avoid_tolls", value.avoidTolls)
                .putBoolean("avoid_ferries", value.avoidFerries)
                .apply()
        }

    /** Naposledy zvolené kritériá hľadania (oblasť a poradie). */
    var searchOptions: SearchOptions
        get() = SearchOptions(
            area = SearchArea.entries.firstOrNull { it.name == prefs.getString("search_area", null) } ?: SearchArea.NEARBY,
            sort = SearchSort.entries.firstOrNull { it.name == prefs.getString("search_sort", null) } ?: SearchSort.NEAREST,
        )
        set(value) {
            prefs.edit().putString("search_area", value.area.name).putString("search_sort", value.sort.name).apply()
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
