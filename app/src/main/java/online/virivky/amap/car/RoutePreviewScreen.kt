package online.virivky.amap.car

import android.text.Spannable
import android.text.SpannableString
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.DurationSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
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

        if (route == null || nav.phase != Phase.PREVIEW) {
            builder.setLoading(true)
        } else {
            // Prvý znak nahradí Android Auto naformátovaným časom jazdy.
            val label = SpannableString("  · ${Instructions.displayDistance(route.distance)}")
            label.setSpan(DurationSpan.create(route.duration.toLong()), 0, 1, Spannable.SPAN_INCLUSIVE_INCLUSIVE)
            val row = Row.Builder().setTitle(label)
            destination?.address?.ifBlank { null }?.let { row.addText(it) }
            builder.setItemList(
                ItemList.Builder()
                    .addItem(row.build())
                    .setSelectedIndex(0)
                    .setOnSelectedListener { }
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
