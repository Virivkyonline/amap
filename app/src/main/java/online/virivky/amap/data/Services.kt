package online.virivky.amap.data

import online.virivky.amap.geo.GeoPoint
import online.virivky.amap.location.LocationTracker
import online.virivky.amap.nav.NavigationController
import online.virivky.amap.nav.NavigationController.Phase
import online.virivky.amap.net.Pois
import online.virivky.amap.util.Bg
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Služby na mape (nabíjačky, pumpy, …) – spoločný stav pre telefón aj Android Auto.
 * Počas navigácie sa hľadá pozdĺž trasy, inak v okolí.
 */
object Services {
    fun interface Listener {
        fun onServicesChanged()
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private var request = 0
    private var lastPhase = Phase.IDLE

    /** Zobrazená kategória, null = služby sú skryté. */
    var category: PoiCategory? = null
        private set
    var results: List<Poi> = emptyList()
        private set
    var loading = false
        private set
    var error: String? = null
        private set

    /** Výsledky sú pozdĺž trasy (vzdialenosť sa meria po trase). */
    var alongRoute = false
        private set

    fun init() {
        NavigationController.addListener {
            val phase = NavigationController.phase
            // Po štarte navigácie k vybranému miestu zoznam zavrieme.
            if (phase == Phase.NAVIGATING && lastPhase != Phase.NAVIGATING) clear()
            lastPhase = phase
        }
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        listeners.forEach { it.onServicesChanged() }
    }

    /** Vyhľadá služby danej kategórie okolo [near] (inak okolo polohy) alebo pozdĺž trasy. */
    fun show(category: PoiCategory, near: GeoPoint? = null) {
        val id = ++request
        val nav = NavigationController
        val route = nav.route
        val traveled = nav.progress?.traveled
        val along = nav.phase == Phase.NAVIGATING && route != null && traveled != null
        val center = near ?: LocationTracker.last?.let { GeoPoint(it) }
        val key = Settings.tomtomKey
        this.category = category
        results = emptyList()
        alongRoute = along
        error = null
        if (!along && center == null) {
            loading = false
            error = "Čakám na polohu GPS…"
            notifyChanged()
            return
        }
        loading = true
        notifyChanged()
        Bg.run({
            if (along) Pois.alongRoute(key, category, route!!, traveled!!)
            else Pois.search(key, category, center!!, category.radius)
        }) { result ->
            if (id != request) return@run
            loading = false
            result.onSuccess { found ->
                results = found
                if (found.isEmpty()) error = if (along) "Pozdĺž trasy sa nič nenašlo." else "V okolí sa nič nenašlo."
            }.onFailure {
                error = "Služby sa nepodarilo načítať. Skontrolujte internet."
            }
            notifyChanged()
            if (key != null && category == PoiCategory.EV) loadAvailability(id, key, results)
        }
    }

    /** Živá obsadenosť najbližších nabíjačiek (TomTom). */
    private fun loadAvailability(id: Int, key: String, pois: List<Poi>) {
        val targets = pois.filter { it.availabilityId != null }.take(10)
        if (targets.isEmpty()) return
        Bg.run({ targets.forEach { runCatching { Pois.loadAvailability(key, it) } } }) {
            if (id == request) notifyChanged()
        }
    }

    fun clear() {
        if (category == null && results.isEmpty() && !loading) return
        request++
        category = null
        results = emptyList()
        loading = false
        error = null
        alongRoute = false
        notifyChanged()
    }
}
