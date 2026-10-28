package online.virivky.amap.car

import android.graphics.Rect
import android.location.Location
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import online.virivky.amap.location.LocationTracker
import online.virivky.amap.map.MapFollower
import online.virivky.amap.map.MapRenderer
import online.virivky.amap.map.TileCache
import online.virivky.amap.nav.NavigationController
import online.virivky.amap.util.Bg
import kotlin.math.ln

/** Kreslenie mapy na plochu, ktorú poskytuje Android Auto. */
class CarMapSurface(private val carContext: CarContext) : SurfaceCallback {
    val renderer = MapRenderer()
    val follower = MapFollower(renderer)

    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var renderPosted = false

    private val tileListener: () -> Unit = { requestRender() }
    private val locationListener: (Location) -> Unit = { requestRender() }
    private val navigationListener = NavigationController.Listener { requestRender() }

    fun attach() {
        renderer.darkMode = carContext.isDarkMode
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        TileCache.addListener(tileListener)
        LocationTracker.addListener(locationListener)
        NavigationController.addListener(navigationListener)
    }

    fun detach() {
        TileCache.removeListener(tileListener)
        LocationTracker.removeListener(locationListener)
        NavigationController.removeListener(navigationListener)
        surface = null
    }

    fun setDarkMode(dark: Boolean) {
        renderer.darkMode = dark
        requestRender()
    }

    fun zoomIn() {
        renderer.zoomBy(1.0)
        requestRender()
    }

    fun zoomOut() {
        renderer.zoomBy(-1.0)
        requestRender()
    }

    fun recenter() {
        follower.recenter()
        requestRender()
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        surface = surfaceContainer.surface
        width = surfaceContainer.width
        height = surfaceContainer.height
        renderer.pixelScale = (surfaceContainer.dpi / 160f * 1.15f).coerceIn(1f, 3f)
        requestRender()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        renderer.viewport.set(visibleArea)
        requestRender()
    }

    override fun onStableAreaChanged(stableArea: Rect) {}

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        surface = null
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        follower.follow = false
        renderer.panBy(distanceX, distanceY)
        requestRender()
    }

    override fun onFling(velocityX: Float, velocityY: Float) {}

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (scaleFactor <= 0f) return
        val delta = ln(scaleFactor.toDouble()) / ln(2.0)
        if (follower.follow || focusX < 0 || focusY < 0) renderer.zoomBy(delta)
        else renderer.zoomBy(delta, focusX, focusY)
        requestRender()
    }

    override fun onClick(x: Float, y: Float) {}

    fun requestRender() {
        if (renderPosted) return
        renderPosted = true
        Bg.main.post {
            renderPosted = false
            render()
        }
    }

    private fun render() {
        val s = surface ?: return
        if (!s.isValid || width == 0 || height == 0) return
        renderer.setSize(width, height)
        follower.sync(fitPaddingPx = 32f * renderer.pixelScale)
        val canvas = try {
            s.lockCanvas(null)
        } catch (e: Exception) {
            return
        } ?: return
        try {
            renderer.draw(canvas, width, height)
        } finally {
            s.unlockCanvasAndPost(canvas)
        }
    }
}
