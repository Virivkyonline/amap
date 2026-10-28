package online.virivky.amap

import android.app.Application
import online.virivky.amap.data.Recents
import online.virivky.amap.location.LocationTracker
import online.virivky.amap.map.TileCache
import online.virivky.amap.nav.NavigationController

class AmapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TileCache.init(this)
        Recents.init(this)
        LocationTracker.init(this)
        NavigationController.init(this)
    }
}
