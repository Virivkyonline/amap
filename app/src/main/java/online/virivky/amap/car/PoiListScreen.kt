package online.virivky.amap.car

import android.text.Spannable
import android.text.SpannableString
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.PlaceListNavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import online.virivky.amap.data.Poi
import online.virivky.amap.data.Services
import online.virivky.amap.map.PoiIcons
import online.virivky.amap.nav.NavigationController
import online.virivky.amap.nav.NavigationController.Phase

/**
 * Nájdené služby: zoznam vľavo, značky na našej mape. Počas navigácie sa vybrané
 * miesto pridá ako zastávka, inak sa zobrazí náhľad trasy k nemu.
 */
@Suppress("DEPRECATION")
class PoiListScreen(carContext: CarContext) : Screen(carContext) {
    private val listener = Services.Listener { invalidate() }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) = Services.addListener(listener)

            override fun onDestroy(owner: LifecycleOwner) {
                Services.removeListener(listener)
                Services.clear()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val category = Services.category
        val title = if (category == null) "Služby" else "${category.label} ${Services.areaLabel}"
        val builder = PlaceListNavigationTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.BACK)
        if (Services.loading) {
            builder.setLoading(true)
        } else {
            val list = ItemList.Builder()
            Services.results.take(MAX_ROWS).forEach { list.addItem(row(it)) }
            list.setNoItemsMessage(Services.error ?: "Nič sa nenašlo")
            builder.setItemList(list.build())
        }
        return builder.build()
    }

    private fun row(poi: Poi): Row {
        // Prvý znak nahradí Android Auto vzdialenosťou.
        val info = poi.info()
        val text = SpannableString(if (info.isEmpty()) " " else "  · " + info.joinToString(" · "))
        text.setSpan(DistanceSpan.create(CarUi.distance(poi.distance)), 0, 1, Spannable.SPAN_INCLUSIVE_INCLUSIVE)
        return Row.Builder()
            .setTitle(poi.name)
            .addText(text)
            .setImage(CarIcon.Builder(IconCompat.createWithBitmap(PoiIcons.badge(poi.category, 96))).build())
            .setOnClickListener { choose(poi) }
            .build()
    }

    private fun choose(poi: Poi) {
        val nav = NavigationController
        if (nav.phase == Phase.NAVIGATING && Services.atDestination) {
            // Parkovisko pri cieli: nový cieľ.
            nav.navigateTo(poi.toPlace())
            CarToast.makeText(carContext, "Cieľ: ${poi.name}", CarToast.LENGTH_SHORT).show()
            screenManager.popToRoot()
        } else if (nav.phase == Phase.NAVIGATING) {
            nav.addStop(poi.toPlace())
            CarToast.makeText(carContext, "Zastávka: ${poi.name}", CarToast.LENGTH_SHORT).show()
            screenManager.popToRoot()
        } else {
            nav.preview(poi.toPlace())
            screenManager.push(RoutePreviewScreen(carContext))
        }
    }

    private companion object {
        const val MAX_ROWS = 25
    }
}
