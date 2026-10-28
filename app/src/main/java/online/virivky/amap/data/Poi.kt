package online.virivky.amap.data

import online.virivky.amap.geo.GeoPoint
import online.virivky.amap.nav.Instructions
import kotlin.math.roundToInt

/**
 * Druhy služieb na mape. [tomtom] = kódy kategórií TomTom, [osm] = filter pre OpenStreetMap.
 */
enum class PoiCategory(
    val key: String,
    val label: String,
    val singular: String,
    val tomtom: String,
    val osm: String,
    val color: Int,
) {
    EV("ev", "Nabíjačky", "Nabíjačka", "7309", """nwr["amenity"="charging_station"]""", 0xFF12B76A.toInt()),
    FUEL("fuel", "Pumpy", "Čerpacia stanica", "7311", """nwr["amenity"="fuel"]""", 0xFF2E6BE6.toInt()),
    PARKING(
        "parking", "Parkovanie", "Parkovisko", "7369,7313",
        """nwr["amenity"="parking"]["access"!~"^(private|customers|no)$"]""", 0xFF3D63DD.toInt(),
    ),
    REST("rest", "Odpočívadlá", "Odpočívadlo", "7395", """nwr["highway"~"^(rest_area|services)$"]""", 0xFF0E9F8E.toInt()),
    FOOD("food", "Jedlo", "Reštaurácia", "7315", """nwr["amenity"~"^(restaurant|fast_food)$"]""", 0xFFF07C1B.toInt()),
    CAFE("cafe", "Káva", "Kaviareň", "9376", """nwr["amenity"="cafe"]""", 0xFF8D5A3B.toInt()),
    SHOP("shop", "Potraviny", "Obchod", "7332", """nwr["shop"~"^(supermarket|convenience)$"]""", 0xFF8E44AD.toInt()),
    HOTEL("hotel", "Ubytovanie", "Ubytovanie", "7314", """nwr["tourism"~"^(hotel|motel|guest_house)$"]""", 0xFF5B4BDB.toInt()),
    PHARMACY("pharmacy", "Lekáreň", "Lekáreň", "7326", """nwr["amenity"="pharmacy"]""", 0xFF1E9E4A.toInt()),
    ATM("atm", "Bankomat", "Bankomat", "7397", """nwr["amenity"="atm"]""", 0xFF5F6B7A.toInt());

    val icon: String get() = "poi-$key"

    /** Polomer hľadania v okolí v metroch. */
    val radius: Int
        get() = when (this) {
            EV, FUEL -> 15_000
            REST, HOTEL -> 25_000
            PARKING -> 2_000
            else -> 5_000
        }
}

/** Konektor nabíjačky: typ, výkon a počet. */
class Connector(val type: String, val powerKw: Double, val count: Int)

/** Miesto zo služieb (nabíjačka, pumpa, …). */
class Poi(
    val category: PoiCategory,
    val name: String,
    val address: String,
    val point: GeoPoint,
    val distance: Double,
    val connectors: List<Connector> = emptyList(),
    /** Id pre živú obsadenosť nabíjačky (TomTom). */
    val availabilityId: String? = null,
) {
    /** Voľné a všetky nabíjacie body (ak sú známe). */
    @Volatile
    var available: Int? = null

    @Volatile
    var total: Int? = null

    fun toPlace() = Place(name, address, point)

    /** To isté miesto s inou vzdialenosťou (napr. meranou po trase). */
    fun at(distance: Double) = Poi(category, name, address, point, distance, connectors, availabilityId)

    /** Krátky popis: vzdialenosť, konektory, obsadenosť. */
    fun detail(): String = (listOf(Instructions.displayDistance(distance)) + info()).joinToString(" · ")

    /** Konektory, obsadenosť a adresa (bez vzdialenosti). */
    fun info(): List<String> {
        val parts = ArrayList<String>()
        if (connectors.isNotEmpty()) {
            parts.add(
                connectors.sortedByDescending { it.powerKw }.take(3).joinToString(", ") { c ->
                    val power = if (c.powerKw > 0) " ${c.powerKw.roundToInt()} kW" else ""
                    (if (c.count > 1) "${c.count}× " else "") + c.type + power
                }
            )
        }
        val free = available
        val all = total
        if (free != null && all != null && all > 0) parts.add("voľné $free/$all")
        if (address.isNotBlank() && connectors.isEmpty()) parts.add(address)
        return parts
    }
}
