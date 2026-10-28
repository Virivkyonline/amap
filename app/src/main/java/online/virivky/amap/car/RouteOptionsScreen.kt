package online.virivky.amap.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import online.virivky.amap.data.RouteOptions
import online.virivky.amap.data.Settings
import online.virivky.amap.nav.NavigationController

/** Čomu sa má trasa vyhnúť; po návrate sa trasa prepočíta. */
class RouteOptionsScreen(carContext: CarContext) : Screen(carContext) {
    private val original = Settings.routeOptions

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                if (Settings.routeOptions != original) NavigationController.retry()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val options = Settings.routeOptions
        val list = ItemList.Builder()
            .addItem(row("Vyhnúť sa diaľniciam", options.avoidMotorways) { options.copy(avoidMotorways = it) })
            .addItem(row("Vyhnúť sa spoplatneným úsekom", options.avoidTolls) { options.copy(avoidTolls = it) })
            .addItem(row("Vyhnúť sa trajektom", options.avoidFerries) { options.copy(avoidFerries = it) })
            .build()
        return ListTemplate.Builder()
            .setTitle("Možnosti trasy")
            .setHeaderAction(Action.BACK)
            .setSingleList(list)
            .build()
    }

    private fun row(title: String, checked: Boolean, change: (Boolean) -> RouteOptions): Row = Row.Builder()
        .setTitle(title)
        .setToggle(
            Toggle.Builder { isChecked ->
                Settings.routeOptions = change(isChecked)
                invalidate()
            }.setChecked(checked).build()
        )
        .build()
}
