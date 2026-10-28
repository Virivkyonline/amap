package online.virivky.amap.car

import android.content.Intent
import android.content.res.Configuration
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Trip
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import online.virivky.amap.data.Place
import online.virivky.amap.location.LocationTracker
import online.virivky.amap.nav.Instructions
import online.virivky.amap.nav.NavigationController
import online.virivky.amap.nav.NavigationController.Phase
import online.virivky.amap.util.CarLog
import online.virivky.amap.util.GeoUri
import kotlin.math.max

/** Jedno pripojenie k autu. Drží mapu, polohu a hlási navigáciu systému Android Auto. */
class AmapSession : Session() {
    private lateinit var map: CarMapSurface
    private var navigationReported = false
    private val navigationListener = NavigationController.Listener { onNavigationChanged() }

    private val navigationManager: NavigationManager
        get() = carContext.getCarService(NavigationManager::class.java)

    private val screenManager: ScreenManager
        get() = carContext.getCarService(ScreenManager::class.java)

    override fun onCreateScreen(intent: Intent): Screen {
        CarLog.add("Obrazovka v aute (API hostiteľa ${carContext.carAppApiLevel})")
        map = CarMapSurface(carContext)
        navigationManager.setNavigationManagerCallback(object : NavigationManagerCallback {
            // Napr. keď používateľ spustí navigáciu v inej aplikácii.
            override fun onStopNavigation() = NavigationController.stop()
        })
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                LocationTracker.acquire(this@AmapSession)
                map.attach()
                NavigationController.addListener(navigationListener)
                onNavigationChanged(popScreens = false)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                NavigationController.removeListener(navigationListener)
                map.detach()
                LocationTracker.release(this@AmapSession)
            }
        })

        val root = MapScreen(carContext, map)
        if (!LocationTracker.hasPermission(carContext)) {
            screenManager.push(root)
            return PermissionScreen(carContext)
        }
        val target = navigationTarget(intent) ?: return root
        screenManager.push(root)
        return target
    }

    override fun onNewIntent(intent: Intent) {
        val target = navigationTarget(intent) ?: return
        screenManager.popToRoot()
        screenManager.push(target)
    }

    override fun onCarConfigurationChanged(newConfiguration: Configuration) {
        map.setDarkMode(carContext.isDarkMode)
    }

    /** Spracuje „naviguj do…“ (napr. z Google Asistenta alebo kontaktov). */
    private fun navigationTarget(intent: Intent): Screen? {
        if (intent.action != CarContext.ACTION_NAVIGATE) return null
        val target = intent.data?.let { GeoUri.parse(it) } ?: return null
        val point = target.point
        if (point != null) {
            NavigationController.preview(Place(target.label ?: "Cieľ", "", point))
            return RoutePreviewScreen(carContext)
        }
        return target.query?.let { SearchScreen(carContext, it) }
    }

    private fun onNavigationChanged(popScreens: Boolean = true) {
        val navigating = NavigationController.phase == Phase.NAVIGATING
        if (navigating && !navigationReported) {
            try {
                navigationManager.navigationStarted()
                navigationReported = true
            } catch (e: Exception) {
                // Bez spätného volania nemožno navigáciu ohlásiť; mapa funguje aj tak.
            }
            if (popScreens) screenManager.popToRoot()
        } else if (!navigating && navigationReported) {
            navigationManager.navigationEnded()
            navigationReported = false
        }
        if (navigating && navigationReported) updateTrip()
    }

    /** Pokyny pre panel prístrojov a lištu Android Auto, keď je na obrazovke iná aplikácia. */
    private fun updateTrip() {
        val nav = NavigationController
        val route = nav.route
        val p = nav.progress
        try {
            val trip = Trip.Builder()
            if (route == null || p == null) {
                trip.setLoading(true)
            } else {
                val step = route.steps[p.nextStepIndex]
                trip.addStep(CarUi.step(step), CarUi.estimate(p.distanceToNextStep, p.secondsToNextStep))
                Instructions.road(route.steps[max(0, p.nextStepIndex - 1)])?.let { trip.setCurrentRoad(it) }
                // Zastávky ako prvé ciele, každá s vlastným časom príchodu.
                nav.stops.forEachIndexed { i, stop ->
                    val stopStep = nav.stopStepIndex(route, i)
                    if (stopStep >= p.nextStepIndex) {
                        var seconds = p.secondsToNextStep
                        for (k in p.nextStepIndex until stopStep) seconds += route.steps[k].duration
                        trip.addDestination(
                            Destination.Builder().setName(stop.name.ifBlank { "Zastávka" }).build(),
                            CarUi.estimate(route.stepAlong(stopStep) - p.traveled, seconds),
                        )
                    }
                }
                trip.addDestination(
                    Destination.Builder().setName(nav.destination?.name?.ifBlank { null } ?: "Cieľ").build(),
                    CarUi.estimate(p.remainingDistance, p.remainingSeconds),
                )
            }
            navigationManager.updateTrip(trip.build())
        } catch (e: Exception) {
            // Neplatný stav pre hostiteľa nesmie zhodiť aplikáciu.
        }
    }
}
