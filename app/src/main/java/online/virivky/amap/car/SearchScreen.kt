package online.virivky.amap.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import online.virivky.amap.R
import online.virivky.amap.data.Place
import online.virivky.amap.data.Recents
import online.virivky.amap.geo.Geo
import online.virivky.amap.geo.GeoPoint
import online.virivky.amap.location.LocationTracker
import online.virivky.amap.nav.Instructions
import online.virivky.amap.nav.NavigationController
import online.virivky.amap.net.Geocoder
import online.virivky.amap.util.Bg

/** Vyhľadanie cieľa. Bez zadaného textu ukazuje nedávne ciele (počas jazdy sa nedá písať). */
class SearchScreen(carContext: CarContext, initialQuery: String? = null) : Screen(carContext) {
    private var query = initialQuery.orEmpty()
    private var results: List<Place> = Recents.all()
    private var showingRecents = true
    private var loading = false
    private var failed = false

    init {
        if (!initialQuery.isNullOrBlank()) search(initialQuery)
    }

    override fun onGetTemplate(): Template {
        val builder = SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) {
                query = searchText
            }

            override fun onSearchSubmitted(searchText: String) {
                search(searchText)
            }
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint("Kam idete?")
            .setShowKeyboardByDefault(results.isEmpty() && !loading)
        if (query.isNotEmpty()) builder.setInitialSearchText(query)

        if (loading) {
            builder.setLoading(true)
        } else {
            val list = ItemList.Builder()
            results.forEach { list.addItem(row(it)) }
            list.setNoItemsMessage(
                when {
                    failed -> "Vyhľadávanie zlyhalo. Skontrolujte internet."
                    showingRecents -> "Zatiaľ žiadne nedávne ciele"
                    else -> "Nič sa nenašlo"
                }
            )
            builder.setItemList(list.build())
        }
        return builder.build()
    }

    private fun row(place: Place): Row {
        val here = LocationTracker.last?.let { GeoPoint(it) }
        val distance = here?.let { Instructions.displayDistance(Geo.distance(it, place.point)) }
        val text = listOfNotNull(distance, place.address.ifBlank { null }).joinToString(" · ")
        val row = Row.Builder()
            .setTitle(place.name.ifBlank { "Bez názvu" })
            .setImage(carContext.carIcon(if (showingRecents) R.drawable.ic_history else R.drawable.ic_place))
            .setOnClickListener {
                NavigationController.preview(place)
                screenManager.push(RoutePreviewScreen(carContext))
            }
        if (text.isNotEmpty()) row.addText(text)
        return row.build()
    }

    private fun search(text: String) {
        if (text.isBlank()) return
        query = text
        loading = true
        failed = false
        invalidate()
        val near = LocationTracker.last?.let { GeoPoint(it) }
        Bg.run({ Geocoder.search(text, near) }) { result ->
            loading = false
            showingRecents = false
            results = result.getOrDefault(emptyList())
            failed = result.isFailure
            invalidate()
        }
    }
}
