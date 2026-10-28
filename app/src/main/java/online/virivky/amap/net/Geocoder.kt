package online.virivky.amap.net

import online.virivky.amap.data.Place
import online.virivky.amap.data.SearchArea
import online.virivky.amap.data.SearchOptions
import online.virivky.amap.data.SearchResult
import online.virivky.amap.data.SearchSort
import online.virivky.amap.data.Settings
import online.virivky.amap.geo.Geo
import online.virivky.amap.geo.GeoPoint
import online.virivky.amap.util.OpeningHours
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.time.LocalDateTime

/**
 * Vyhľadávanie adries a miest. S kľúčom TomTom (presnejšie, s otváracími hodinami),
 * inak cez OpenStreetMap Nominatim. Výsledky sa zoradia podľa vzdialenosti alebo zhody.
 */
object Geocoder {
    private val COORDINATES = Regex("""^\s*(-?\d{1,2}(?:\.\d+)?)\s*[,;\s]\s*(-?\d{1,3}(?:\.\d+)?)\s*$""")

    /** Polomer hľadania „v okolí“. */
    private const val NEARBY_RADIUS = 50_000

    /**
     * Vyhľadá miesta podľa textu a kritérií. [typeahead] = rýchle návrhy počas písania
     * (len s kľúčom TomTom, Nominatim ich nepovoľuje).
     */
    fun search(query: String, near: GeoPoint?, options: SearchOptions = SearchOptions(), typeahead: Boolean = false): SearchResult {
        val text = query.trim()
        COORDINATES.matchEntire(text)?.let { m ->
            val lat = m.groupValues[1].toDouble()
            val lon = m.groupValues[2].toDouble()
            if (lat in -90.0..90.0 && lon in -180.0..180.0) {
                return SearchResult(listOf(Place("$lat, $lon", "Súradnice", GeoPoint(lat, lon))))
            }
        }
        val area = if (near == null && options.area == SearchArea.NEARBY) SearchArea.WORLD else options.area
        var places = find(text, near, area, typeahead)
        var note: String? = null
        // V okolí nič – skúsime ďalej (napr. hľadanie iného mesta).
        if (places.isEmpty() && area == SearchArea.NEARBY && !typeahead) {
            places = find(text, near, SearchArea.WORLD, false)
            if (places.isNotEmpty()) note = "V okolí 50 km sa nič nenašlo, výsledky sú z väčšej diaľky."
        }
        if (options.openNow) {
            val open = places.filter { it.open == true }
            if (open.isEmpty() && places.isNotEmpty()) note = "Nič otvorené (alebo chýbajú otváracie hodiny)."
            places = open
        }
        return SearchResult(order(places, text, near, options.sort).take(30), note)
    }

    /** Adresa bodu na mape (napr. po podržaní prsta). */
    fun reverse(point: GeoPoint): Place? {
        Settings.tomtomKey?.let { key ->
            try {
                val url = "https://api.tomtom.com/search/2/reverseGeocode/${point.lat},${point.lon}.json?key=" +
                    URLEncoder.encode(key, "UTF-8") + "&language=sk-SK"
                val address = JSONObject(Http.getString(url)).optJSONArray("addresses")?.optJSONObject(0)
                    ?.optJSONObject("address")
                val free = address?.optString("freeformAddress").orEmpty()
                if (free.isNotBlank()) return Place(free.substringBefore(',').trim(), free.substringAfter(',', "").trim(), point)
            } catch (e: Exception) {
                // Skúsime OpenStreetMap.
            }
        }
        val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&addressdetails=1&accept-language=sk" +
            "&lat=${point.lat}&lon=${point.lon}"
        val o = JSONObject(Http.getString(url))
        if (o.has("error")) return null
        val (title, address) = nominatimTitle(o)
        return Place(title, address, point)
    }

    private fun find(text: String, near: GeoPoint?, area: SearchArea, typeahead: Boolean): List<Place> {
        Settings.tomtomKey?.let { key ->
            try {
                return tomtom(key, text, near, area, typeahead)
            } catch (e: Exception) {
                // TomTom nedostupný – použijeme OpenStreetMap.
            }
        }
        if (typeahead) return emptyList()
        return nominatim(text, near, area)
    }

    /**
     * Najbližšie: najprv miesta, ktoré obsahujú všetky hľadané slová (Tesco pri „tesco“),
     * zoradené podľa vzdialenosti, potom ostatné. Inak poradie podľa zhody zo služby.
     */
    private fun order(places: List<Place>, text: String, near: GeoPoint?, sort: SearchSort): List<Place> {
        if (sort == SearchSort.RELEVANCE || near == null) return places
        val words = normalize(text).split(' ').filter { it.length >= 2 }
        fun strong(p: Place): Boolean {
            val hay = normalize(p.name + " " + p.address + " " + p.kind)
            return words.all { hay.contains(it) }
        }
        return places.sortedWith(compareBy<Place>({ !strong(it) }, { Geo.distance(near, it.point) }))
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun tomtom(key: String, text: String, near: GeoPoint?, area: SearchArea, typeahead: Boolean): List<Place> {
        val url = StringBuilder("https://api.tomtom.com/search/2/search/")
            .append(URLEncoder.encode(text, "UTF-8").replace("+", "%20"))
            .append(".json?key=").append(URLEncoder.encode(key, "UTF-8"))
            .append("&language=sk-SK&openingHours=nextSevenDays&limit=").append(if (typeahead) 10 else 50)
        if (typeahead) url.append("&typeahead=true")
        if (near != null) {
            url.append("&lat=").append(near.lat).append("&lon=").append(near.lon)
            if (area == SearchArea.NEARBY) url.append("&radius=").append(NEARBY_RADIUS)
        }
        if (area == SearchArea.COUNTRY) url.append("&countrySet=SK")
        val results = JSONObject(Http.getString(url.toString())).optJSONArray("results") ?: return emptyList()
        val now = LocalDateTime.now()
        return (0 until results.length()).mapNotNull { i ->
            val r = results.getJSONObject(i)
            val position = r.optJSONObject("position") ?: return@mapNotNull null
            val point = GeoPoint(position.optDouble("lat"), position.optDouble("lon"))
            val address = r.optJSONObject("address")
            val free = address?.optString("freeformAddress").orEmpty()
            val country = address?.optString("countryCode").orEmpty()
            val foreign = if (country.isNotBlank() && country != "SK") address?.optString("country").orEmpty() else ""
            val poi = r.optJSONObject("poi")
            val type = r.optString("type")
            val title: String
            var place: String
            var kind = ""
            when {
                poi != null -> {
                    title = poi.optString("name").ifBlank { free.substringBefore(',') }
                    place = free
                    kind = poiKind(poi)
                }
                type == "Geography" -> {
                    title = address?.optString("municipality")?.ifBlank { null } ?: free.substringBefore(',').trim()
                    place = listOf(address?.optString("countrySubdivision").orEmpty()).filter { it.isNotBlank() && it != title }
                        .joinToString(", ")
                    kind = when (r.optString("entityType")) {
                        "Country" -> "Krajina"
                        "CountrySubdivision", "CountrySecondarySubdivision" -> "Región"
                        "MunicipalitySubdivision" -> "Časť obce"
                        else -> "Obec"
                    }
                }
                else -> {
                    title = free.substringBefore(',').trim()
                    place = free.substringAfter(',', "").trim()
                    kind = if (type == "Street") "Ulica" else "Adresa"
                }
            }
            if (foreign.isNotBlank() && !place.contains(foreign)) place = listOf(place, foreign).filter { it.isNotBlank() }.joinToString(", ")
            val hours = poi?.optJSONObject("openingHours")?.optJSONArray("timeRanges")?.let { ranges(it) }
                ?.let { OpeningHours.status(it, now) }
            Place(title.ifBlank { "Bez názvu" }, place, point, kind, hours?.label.orEmpty(), hours?.open)
        }
    }

    private fun poiKind(poi: JSONObject): String {
        val name = poi.optJSONArray("classifications")?.optJSONObject(0)?.optJSONArray("names")?.optJSONObject(0)
            ?.optString("name")?.ifBlank { null }
            ?: poi.optJSONArray("categories")?.optString(0)
            ?: return ""
        return name.replaceFirstChar { it.uppercase() }
    }

    private fun ranges(array: JSONArray): List<Pair<LocalDateTime, LocalDateTime>> =
        (0 until array.length()).mapNotNull { i ->
            val r = array.optJSONObject(i) ?: return@mapNotNull null
            val start = time(r.optJSONObject("startTime")) ?: return@mapNotNull null
            val end = time(r.optJSONObject("endTime")) ?: return@mapNotNull null
            start to end
        }

    private fun time(o: JSONObject?): LocalDateTime? = try {
        o?.let { LocalDateTime.parse(it.getString("date") + "T00:00").withHour(it.optInt("hour")).withMinute(it.optInt("minute")) }
    } catch (e: Exception) {
        null
    }

    private fun nominatim(text: String, near: GeoPoint?, area: SearchArea): List<Place> {
        val url = StringBuilder("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=40")
            .append("&addressdetails=1&extratags=1&accept-language=sk&q=").append(URLEncoder.encode(text, "UTF-8"))
        if (near != null) {
            // Okolie: ohraničené na ~50 km, inak len uprednostnené (cca 150 km).
            val dLat = if (area == SearchArea.NEARBY) 0.45 else 1.5
            val dLon = if (area == SearchArea.NEARBY) 0.65 else 1.5
            url.append("&viewbox=").append(near.lon - dLon).append(',').append(near.lat + dLat)
                .append(',').append(near.lon + dLon).append(',').append(near.lat - dLat)
            if (area == SearchArea.NEARBY) url.append("&bounded=1")
        }
        if (area == SearchArea.COUNTRY) url.append("&countrycodes=sk")
        val array = JSONArray(Http.getString(url.toString()))
        val now = LocalDateTime.now()
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val (title, address) = nominatimTitle(o)
            val hours = OpeningHours.status(o.optJSONObject("extratags")?.optString("opening_hours"), now)
            Place(
                title, address, GeoPoint(o.getString("lat").toDouble(), o.getString("lon").toDouble()),
                kind = osmKind(o.optString("type"), o.optString("addresstype")),
                hours = hours?.label.orEmpty(),
                open = hours?.open,
            )
        }
    }

    /** Názov a krátka adresa (ulica, obec, krajina mimo Slovenska) z odpovede Nominatim. */
    private fun nominatimTitle(o: JSONObject): Pair<String, String> {
        val display = o.optString("display_name")
        val a = o.optJSONObject("address")
        val street = listOf(a?.optString("road").orEmpty(), a?.optString("house_number").orEmpty())
            .filter { it.isNotBlank() }.joinToString(" ")
        val town = listOf("city", "town", "village", "municipality", "hamlet").map { a?.optString(it).orEmpty() }
            .firstOrNull { it.isNotBlank() }.orEmpty()
        val country = if (a != null && a.optString("country_code") != "sk") a.optString("country") else ""
        val title = o.optString("name").ifBlank { street.ifBlank { display.substringBefore(',') } }.trim()
        val address = if (a == null) display.removePrefix(title).trimStart(',', ' ')
        else listOf(street, town, country).filter { it.isNotBlank() && it != title }.distinct().joinToString(", ")
        return title.ifBlank { "Bez názvu" } to address
    }

    private fun osmKind(type: String, addressType: String): String = when (type) {
        "supermarket" -> "Supermarket"
        "convenience" -> "Potraviny"
        "mall" -> "Obchodné centrum"
        "fuel" -> "Čerpacia stanica"
        "charging_station" -> "Nabíjačka"
        "parking" -> "Parkovisko"
        "restaurant" -> "Reštaurácia"
        "fast_food" -> "Rýchle občerstvenie"
        "cafe" -> "Kaviareň"
        "pub", "bar" -> "Bar"
        "pharmacy" -> "Lekáreň"
        "hospital" -> "Nemocnica"
        "doctors", "clinic" -> "Lekár"
        "hotel", "motel", "guest_house" -> "Ubytovanie"
        "bank" -> "Banka"
        "atm" -> "Bankomat"
        "post_office" -> "Pošta"
        "school" -> "Škola"
        "station" -> "Stanica"
        "bus_stop" -> "Zastávka"
        "city" -> "Mesto"
        "town" -> "Mesto"
        "village" -> "Obec"
        else -> when (addressType) {
            "road" -> "Ulica"
            "house", "building" -> "Adresa"
            "city", "town" -> "Mesto"
            "village" -> "Obec"
            else -> ""
        }
    }
}
