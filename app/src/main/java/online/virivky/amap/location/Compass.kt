package online.virivky.amap.location

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Kompas telefónu: kam je telefón natočený (horný okraj obrazovky, pri telefóne na výšku zadná strana),
 * v stupňoch od skutočného severu. Pri chôdzi ukazuje smer lepšie ako GPS, ktoré pri malej rýchlosti
 * smer nevie. Beží, len kým ho niekto počúva.
 */
object Compass : SensorEventListener {
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private var sensors: SensorManager? = null
    private var window: WindowManager? = null
    private val matrix = FloatArray(9)
    /** Vyhladený smer ako jednotkový vektor (východ, sever). */
    private var east = 0.0
    private var north = 0.0
    private var notifiedAt = 0L
    private var declination = 0f
    private var declinationAt = -60_000L

    /** Smer telefónu v stupňoch, null = kompas nebeží alebo ho telefón nemá. */
    var heading: Double? = null
        private set

    fun addListener(context: Context, listener: () -> Unit) {
        listeners.add(listener)
        if (sensors != null) return
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return
        window = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        sensors = manager
        east = 0.0
        north = 0.0
        manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
        if (listeners.isNotEmpty()) return
        sensors?.unregisterListener(this)
        sensors = null
        window = null
        heading = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        // Stĺpce matice = osi telefónu vo svete (východ, sever, hore). Smer = horný okraj obrazovky
        // a zadná strana telefónu premietnuté do vodorovnej roviny – funguje naležato aj nastojato.
        @Suppress("DEPRECATION")
        val rotation = window?.defaultDisplay?.rotation
        val (upEast, upNorth) = when (rotation) {
            Surface.ROTATION_90 -> -matrix[0] to -matrix[3]
            Surface.ROTATION_180 -> -matrix[1] to -matrix[4]
            Surface.ROTATION_270 -> matrix[0] to matrix[3]
            else -> matrix[1] to matrix[4]
        }
        val e = upEast - matrix[2].toDouble()
        val n = upNorth - matrix[5].toDouble()
        val length = hypot(e, n)
        if (length < 0.2) return
        val k = if (east == 0.0 && north == 0.0) 1.0 else SMOOTHING
        east += (e / length - east) * k
        north += (n / length - north) * k
        val magnetic = Math.toDegrees(atan2(east, north))
        val now = SystemClock.uptimeMillis()
        // Magnetická odchýlka (na Slovensku ~5°) sa mení pomaly – stačí raz za minútu.
        if (now - declinationAt > 60_000) {
            LocationTracker.last?.let {
                declination = GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), it.altitude.toFloat(), System.currentTimeMillis()).declination
                declinationAt = now
            }
        }
        val value = (magnetic + declination + 360) % 360
        val previous = heading
        val change = if (previous == null) 360.0 else abs(((value - previous + 540) % 360) - 180)
        if (change < 1.5 || now - notifiedAt < 80) return
        heading = value
        notifiedAt = now
        listeners.forEach { it() }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    /** Podiel novej hodnoty pri vyhladení (snímač ~16 × za sekundu). */
    private const val SMOOTHING = 0.25
}
