package online.virivky.amap.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import online.virivky.amap.R
import online.virivky.amap.nav.Instructions
import online.virivky.amap.nav.NavigationController
import online.virivky.amap.nav.NavigationController.Phase

/** Hlavná obrazovka v aute: mapa, a počas navigácie pokyny a čas príchodu. */
class MapScreen(carContext: CarContext, private val map: CarMapSurface) : Screen(carContext) {
    private val nav = NavigationController
    private val listener = NavigationController.Listener { invalidate() }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) = nav.addListener(listener)
            override fun onDestroy(owner: LifecycleOwner) = nav.removeListener(listener)
        })
    }

    override fun onGetTemplate(): Template {
        val builder = NavigationTemplate.Builder()
            .setActionStrip(actionStrip())
            .setMapActionStrip(mapActionStrip())
            .setPanModeListener { inPanMode -> if (inPanMode) map.follower.follow = false }

        when (nav.phase) {
            Phase.NAVIGATING -> {
                val route = nav.route
                val p = nav.progress
                if (route == null || p == null || nav.rerouting) {
                    builder.setNavigationInfo(RoutingInfo.Builder().setLoading(true).build())
                } else {
                    val step = route.steps[p.nextStepIndex]
                    val info = RoutingInfo.Builder()
                        .setCurrentStep(CarUi.step(step), CarUi.distance(p.distanceToNextStep))
                    val then = route.steps.getOrNull(p.nextStepIndex + 1)
                    if (then != null && step.distance < 300) info.setNextStep(CarUi.step(then))
                    builder.setNavigationInfo(info.build())
                    builder.setDestinationTravelEstimate(CarUi.estimate(p.remainingDistance, p.remainingSeconds))
                }
            }
            Phase.ARRIVED -> builder.setNavigationInfo(
                MessageInfo.Builder("Ste v cieli")
                    .setText(nav.destination?.name?.ifBlank { null } ?: "Cieľ")
                    .setImage(carContext.carIcon(R.drawable.ic_place))
                    .build()
            )
            Phase.ROUTING -> builder.setNavigationInfo(
                MessageInfo.Builder("Hľadám trasu…")
                    .setText(nav.destination?.name?.ifBlank { null } ?: "Cieľ")
                    .build()
            )
            Phase.PREVIEW -> {
                val route = nav.route
                val text = if (route != null) {
                    "${Instructions.displayDistance(route.distance)} · ${Instructions.displayDuration(route.duration)}"
                } else {
                    nav.error ?: "Trasa nie je dostupná"
                }
                builder.setNavigationInfo(
                    MessageInfo.Builder(nav.destination?.name?.ifBlank { null } ?: "Cieľ")
                        .setText(text)
                        .setImage(carContext.carIcon(R.drawable.ic_place))
                        .build()
                )
            }
            Phase.IDLE -> Unit
        }
        return builder.build()
    }

    private fun actionStrip(): ActionStrip {
        val strip = ActionStrip.Builder()
        when (nav.phase) {
            Phase.NAVIGATING, Phase.ARRIVED -> {
                strip.addAction(
                    Action.Builder()
                        .setTitle("Ukončiť")
                        .setOnClickListener { nav.stop() }
                        .build()
                )
                strip.addAction(
                    Action.Builder()
                        .setIcon(carContext.carIcon(if (nav.voiceEnabled) R.drawable.ic_volume_up else R.drawable.ic_volume_off))
                        .setOnClickListener {
                            nav.voiceEnabled = !nav.voiceEnabled
                            invalidate()
                        }
                        .build()
                )
            }
            Phase.PREVIEW, Phase.ROUTING -> {
                if (nav.route != null) {
                    strip.addAction(
                        Action.Builder()
                            .setTitle("Štart")
                            .setOnClickListener { nav.start() }
                            .build()
                    )
                } else if (nav.phase == Phase.PREVIEW) {
                    strip.addAction(
                        Action.Builder()
                            .setTitle("Znova")
                            .setOnClickListener { nav.retry() }
                            .build()
                    )
                }
                strip.addAction(
                    Action.Builder()
                        .setIcon(carContext.carIcon(R.drawable.ic_close))
                        .setOnClickListener { nav.cancelPreview() }
                        .build()
                )
                strip.addAction(searchAction(withTitle = false))
            }
            Phase.IDLE -> strip.addAction(searchAction(withTitle = true))
        }
        return strip.build()
    }

    private fun searchAction(withTitle: Boolean): Action {
        val action = Action.Builder()
            .setIcon(carContext.carIcon(R.drawable.ic_search))
            .setOnClickListener { screenManager.push(SearchScreen(carContext)) }
        if (withTitle) action.setTitle("Hľadať")
        return action.build()
    }

    private fun mapActionStrip(): ActionStrip = ActionStrip.Builder()
        .addAction(Action.PAN)
        .addAction(
            Action.Builder()
                .setIcon(carContext.carIcon(R.drawable.ic_zoom_in))
                .setOnClickListener { map.zoomIn() }
                .build()
        )
        .addAction(
            Action.Builder()
                .setIcon(carContext.carIcon(R.drawable.ic_zoom_out))
                .setOnClickListener { map.zoomOut() }
                .build()
        )
        .addAction(
            Action.Builder()
                .setIcon(carContext.carIcon(R.drawable.ic_my_location))
                .setOnClickListener { map.recenter() }
                .build()
        )
        .build()
}
