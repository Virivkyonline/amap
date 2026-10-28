package online.virivky.amap.car

import android.text.Spannable
import android.text.SpannableString
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.DurationSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import online.virivky.amap.R
import online.virivky.amap.data.Settings
import online.virivky.amap.nav.Instructions
import online.virivky.amap.nav.NavigationController
import online.virivky.amap.nav.NavigationController.Phase

/** Náhľad trasy na mape s tlačidlom Štart. */
@Suppress("DEPRECATION")
class RoutePreviewScreen(carContext: CarContext) : Screen(carContext) {
    private val nav = NavigationController
    private val listener = NavigationController.Listener { invalidate() }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) = nav.addListener(listener)

            override fun onDestroy(owner: LifecycleOwner) {
                nav.removeListener(listener)
                // Späť bez spustenia navigácie = zrušiť náhľad.
                nav.cancelPreview()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val destination = nav.destination
        val title = destination?.name?.ifBlank { null } ?: "Trasa"
        val route = nav.route

        if (nav.phase == Phase.PREVIEW && route == null) {
            return MessageTemplate.Builder(nav.error ?: "Trasu sa nepodarilo nájsť.")
                .setTitle(title)
                .setHeaderAction(Action.BACK)
                .addAction(
                    Action.Builder()
                        .setTitle("Skúsiť znova")
                        .setOnClickListener { nav.retry() }
                        .build()
                )
                .build()
        }

        val builder = RoutePreviewNavigationTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("Možnosti")
                            .setIcon(carContext.carIcon(R.drawable.ic_tune))
                            .setOnClickListener { screenManager.push(RouteOptionsScreen(carContext)) }
                            .build()
                    )
                    .build()
            )

        if (route == null || nav.phase != Phase.PREVIEW) {
            builder.setLoading(true)
        } else {
            // Varianty trasy; vybraný sa ukáže na mape. Prvý znak nahradí Android Auto časom jazdy.
            val routes = nav.routes.ifEmpty { listOf(route) }
            val fastest = routes.minOf { it.duration }
            val list = ItemList.Builder()
            routes.forEach { r ->
                val label = SpannableString("  · ${Instructions.displayDistance(r.distance)}")
                label.setSpan(DurationSpan.create(r.duration.toLong()), 0, 1, Spannable.SPAN_INCLUSIVE_INCLUSIVE)
                val row = Row.Builder().setTitle(label)
                val text = listOfNotNull(
                    r.mainRoad.ifBlank { null }?.let { "cez $it" },
                    "najrýchlejšia".takeIf { routes.size > 1 && r.duration == fastest },
                    Settings.routeOptions.label.ifBlank { null },
                ).joinToString(" · ")
                row.addText(text.ifBlank { destination?.address?.ifBlank { null } ?: "Trasa" })
                list.addItem(row.build())
            }
            builder.setItemList(
                list.setSelectedIndex(nav.selectedRouteIndex.coerceAtLeast(0))
                    .setOnSelectedListener { index -> nav.selectRoute(index) }
                    .build()
            )
            builder.setNavigateAction(
                Action.Builder()
                    .setTitle("Štart")
                    .setOnClickListener {
                        nav.start()
                        screenManager.popToRoot()
                    }
                    .build()
            )
        }
        return builder.build()
    }
}
