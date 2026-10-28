package online.virivky.amap

import android.app.Application
import online.virivky.amap.data.Favorites
import online.virivky.amap.data.Recents
import online.virivky.amap.data.SavedPlaces
import online.virivky.amap.data.Services
import online.virivky.amap.data.Settings
import online.virivky.amap.location.LocationTracker
import online.virivky.amap.nav.NavigationController
import online.virivky.amap.util.CarLog
import org.maplibre.android.MapLibre

class AmapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CarLog.init(this)
        MapLibre.getInstance(this)
        Recents.init(this)
        Favorites.init(this)
        SavedPlaces.init(this)
        Settings.init(this)
        LocationTracker.init(this)
        NavigationController.init(this)
        Services.init()
    }
}
