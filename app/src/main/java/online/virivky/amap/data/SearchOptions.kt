package online.virivky.amap.data

/** Kde hľadať. */
enum class SearchArea(val label: String) {
    NEARBY("V okolí 50 km"),
    COUNTRY("Slovensko"),
    WORLD("Všade"),
}

/** Poradie výsledkov. */
enum class SearchSort(val label: String) {
    NEAREST("Najbližšie"),
    RELEVANCE("Najlepšia zhoda"),
}

/** Kritériá hľadania. */
data class SearchOptions(
    val area: SearchArea = SearchArea.NEARBY,
    val sort: SearchSort = SearchSort.NEAREST,
    /** Len miesta, ktoré sú práve otvorené (podľa známych otváracích hodín). */
    val openNow: Boolean = false,
)

/** Výsledky hľadania a prípadná poznámka (napr. že sa hľadalo ďalej). */
class SearchResult(val places: List<Place>, val note: String? = null)
