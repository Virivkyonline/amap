package online.virivky.amap.car

import android.app.Presentation
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import online.virivky.amap.location.LocationTracker
import online.virivky.amap.map.DriveHud
import online.virivky.amap.map.NavMap
import online.virivky.amap.nav.NavigationController
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView

/**
 * Mapa na obrazovke auta: Android Auto poskytne plochu (Surface), na ktorú cez
 * virtuálny displej zobrazíme rovnakú vektorovú mapu ako v telefóne.
 */
class CarMapSurface(private val carContext: CarContext) : SurfaceCallback {
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var mapView: MapView? = null
    private var hud: DriveHud? = null
    private var navMap: NavMap? = null
    private val visibleArea = Rect()
    private var width = 0
    private var height = 0
    private var dark = carContext.isDarkMode

    private val navigationListener = NavigationController.Listener { refresh() }
    private val locationListener: (Location) -> Unit = { refresh() }

    fun attach() {
        dark = carContext.isDarkMode
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        NavigationController.addListener(navigationListener)
        LocationTracker.addListener(locationListener)
    }

    fun detach() {
        NavigationController.removeListener(navigationListener)
        LocationTracker.removeListener(locationListener)
        release()
    }

    fun setDarkMode(dark: Boolean) {
        this.dark = dark
        navMap?.setDark(dark)
    }

    fun zoomIn() {
        navMap?.zoomIn()
    }

    fun zoomOut() {
        navMap?.zoomOut()
    }

    fun recenter() {
        navMap?.recenter()
    }

    /** Používateľ prepol do režimu posúvania mapy. */
    fun onPanMode() {
        navMap?.stopFollowing()
    }

    private fun refresh() {
        navMap?.update()
        hud?.invalidate()
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        release()
        val surface = surfaceContainer.surface ?: return
        width = surfaceContainer.width
        height = surfaceContainer.height
        if (width <= 0 || height <= 0) return
        val manager = carContext.getSystemService(DisplayManager::class.java) ?: return
        val virtual = manager.createVirtualDisplay(
            "amap-car", width, height, surfaceContainer.dpi, surface, 0,
        ) ?: return
        display = virtual

        val screen = Presentation(carContext, virtual.display)
        val context = screen.context
        val options = MapLibreMapOptions.createFromAttributes(context)
            .textureMode(true)
            .logoEnabled(false)
            .attributionEnabled(false)
            .compassEnabled(false)
        val map = MapView(context, options)
        map.onCreate(null)
        val overlay = DriveHud(context).apply { showAttribution = true }
        overlay.viewport.set(visibleArea)
        val root = FrameLayout(context)
        root.addView(map, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        screen.setContentView(root)
        try {
            screen.show()
        } catch (e: Exception) {
            map.onDestroy()
            virtual.release()
            display = null
            return
        }
        map.onStart()
        map.onResume()
        presentation = screen
        mapView = map
        hud = overlay
        map.getMapAsync { maplibre ->
            if (mapView !== map) return@getMapAsync
            navMap = NavMap(context, maplibre, dark).also {
                it.setViewport(effectiveArea(), width, height)
            }
        }
    }

    private fun effectiveArea() = if (visibleArea.isEmpty) Rect(0, 0, width, height) else Rect(visibleArea)

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea.set(visibleArea)
        hud?.viewport?.set(visibleArea)
        hud?.invalidate()
        navMap?.setViewport(effectiveArea(), width, height)
    }

    override fun onStableAreaChanged(stableArea: Rect) {}

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        release()
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        navMap?.scrollBy(distanceX, distanceY)
    }

    override fun onFling(velocityX: Float, velocityY: Float) {}

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        navMap?.scaleBy(focusX, focusY, scaleFactor)
    }

    override fun onClick(x: Float, y: Float) {}

    private fun release() {
        navMap = null
        mapView?.let {
            it.onPause()
            it.onStop()
            it.onDestroy()
        }
        mapView = null
        hud = null
        presentation?.dismiss()
        presentation = null
        display?.release()
        display = null
    }
}
