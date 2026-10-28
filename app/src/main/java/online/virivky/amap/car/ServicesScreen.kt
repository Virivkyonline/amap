package online.virivky.amap.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import online.virivky.amap.R
import online.virivky.amap.data.PoiCategory
import online.virivky.amap.data.Services
import online.virivky.amap.map.PoiIcons
import online.virivky.amap.nav.NavigationController
import online.virivky.amap.nav.NavigationController.Phase

/** Výber služby: nabíjačky, pumpy, parkovanie, … (počas navigácie pozdĺž trasy). */
class ServicesScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val nav = NavigationController
        val list = ItemList.Builder()
        if (nav.stops.isNotEmpty()) {
            list.addItem(
                GridItem.Builder()
                    .setTitle(if (nav.stops.size == 1) "Zrušiť zastávku" else "Zrušiť zastávky")
                    .setText(nav.stops.joinToString(", ") { it.name.ifBlank { "Zastávka" } })
                    .setImage(carContext.carIcon(R.drawable.ic_close), GridItem.IMAGE_TYPE_ICON)
                    .setOnClickListener {
                        nav.removeStop()
                        screenManager.pop()
                    }
                    .build()
            )
        }
        PoiCategory.entries.forEach { category ->
            list.addItem(
                GridItem.Builder()
                    .setTitle(category.label)
                    .setImage(CarIcon.Builder(IconCompat.createWithBitmap(PoiIcons.badge(category, 128))).build())
                    .setOnClickListener {
                        Services.show(category)
                        screenManager.push(PoiListScreen(carContext))
                    }
                    .build()
            )
        }
        return GridTemplate.Builder()
            .setTitle(if (nav.phase == Phase.NAVIGATING) "Služby pozdĺž trasy" else "Služby v okolí")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}
