package online.virivky.amap

import android.app.Application
import online.virivky.amap.data.Recents
import online.virivky.amap.data.Services
import online.virivky.amap.data.Settings
import online.virivky.amap.location.LocationTracker
import online.virivky.amap.nav.NavigationController
import org.maplibre.android.MapLibre

class AmapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        Recents.init(this)
        Settings.init(this)
        LocationTracker.init(this)
        NavigationController.init(this)
        Services.init()
    }
}
