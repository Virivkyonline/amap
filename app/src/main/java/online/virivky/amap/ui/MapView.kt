package online.virivky.amap.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import online.virivky.amap.geo.GeoPoint
import online.virivky.amap.map.MapFollower
import online.virivky.amap.map.MapRenderer
import kotlin.math.ln

/** Mapa v telefóne – rovnaké kreslenie ako v aute, ovládanie prstami. */
class MapView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    val renderer = MapRenderer().apply { pixelScale = resources.displayMetrics.density.coerceIn(1f, 3.5f) }
    val follower = MapFollower(renderer)

    /** Volá sa pri dlhom podržaní prsta na mape. */
    var onLongPress: ((GeoPoint) -> Unit)? = null

    /** Volá sa, keď používateľ mapou pohne (prestane sledovať polohu). */
    var onUserMoved: (() -> Unit)? = null

    private var scaling = false

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scaling) return true
            follower.follow = false
            renderer.panBy(distanceX, distanceY)
            onUserMoved?.invoke()
            invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (follower.follow) renderer.zoomBy(1.0) else renderer.zoomBy(1.0, e.x, e.y)
            invalidate()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            onLongPress?.invoke(renderer.screenToGeo(e.x, e.y))
        }
    })

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaling = true
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val delta = ln(detector.scaleFactor.toDouble()) / ln(2.0)
            if (follower.follow) renderer.zoomBy(delta) else renderer.zoomBy(delta, detector.focusX, detector.focusY)
            invalidate()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            scaling = false
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scale.onTouchEvent(event)
        gestures.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        renderer.setSize(width, height)
        follower.sync(fitPaddingPx = 32f * resources.displayMetrics.density)
        renderer.draw(canvas, width, height)
    }
}
