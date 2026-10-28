package online.virivky.amap.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Poloha z GPS (a siete ako záloha). Beží, kým ju niekto drží – aktivita v telefóne,
 * relácia v aute alebo navigačná služba.
 */
object LocationTracker {
    private lateinit var app: Context
    private val owners = HashSet<Any>()
    private val listeners = CopyOnWriteArraySet<(Location) -> Unit>()
    private var running = false
    private var lastGpsAt = 0L

    var last: Location? = null
        private set

    /** Smer jazdy v stupňoch, ak je známy. */
    var heading: Float? = null
        private set

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun addListener(listener: (Location) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Location) -> Unit) {
        listeners.remove(listener)
    }

    fun acquire(owner: Any) {
        owners.add(owner)
        start()
    }

    fun release(owner: Any) {
        owners.remove(owner)
        if (owners.isEmpty()) stop()
    }

    /** Po udelení povolenia treba odber polohy spustiť znova. */
    fun refresh() {
        stop()
        if (owners.isNotEmpty()) start()
    }

    @SuppressLint("MissingPermission")
    private fun start() {
        if (running || !hasPermission(app)) return
        val manager = app.getSystemService(LocationManager::class.java) ?: return
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                manager.getLastKnownLocation(provider)?.let { known ->
                    if (last == null || known.time > last!!.time) last = known
                }
            } catch (e: Exception) {
                // Poskytovateľ nie je dostupný alebo chýba presná poloha.
            }
        }
        var any = false
        try {
            if (manager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
                any = true
            }
        } catch (e: Exception) {
        }
        try {
            if (manager.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 0f, listener, Looper.getMainLooper())
                any = true
            }
        } catch (e: Exception) {
        }
        running = any
        last?.let { location -> listeners.forEach { it(location) } }
    }

    private fun stop() {
        if (!running) return
        app.getSystemService(LocationManager::class.java)?.removeUpdates(listener)
        running = false
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = handle(location)

        override fun onProviderEnabled(provider: String) {}

        override fun onProviderDisabled(provider: String) {}

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private fun handle(location: Location) {
        val now = SystemClock.elapsedRealtime()
        if (location.provider == LocationManager.GPS_PROVIDER) {
            lastGpsAt = now
        } else if (now - lastGpsAt < 10_000) {
            return // máme čerstvé GPS, sieťová poloha je horšia
        }
        val previous = last
        if (location.hasBearing() && location.hasSpeed() && location.speed > 1.5f) {
            heading = location.bearing
        } else if (previous != null && location.accuracy < 30f && previous.distanceTo(location) > 8f) {
            heading = (previous.bearingTo(location) + 360f) % 360f
        }
        last = location
        listeners.forEach { it(location) }
    }
}
