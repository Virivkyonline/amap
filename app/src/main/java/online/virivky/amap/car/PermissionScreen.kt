package online.virivky.amap.car

import android.Manifest
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Template
import online.virivky.amap.location.LocationTracker

/** Žiadosť o prístup k polohe (potvrdzuje sa v telefóne). */
@Suppress("DEPRECATION")
class PermissionScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val allow = Action.Builder()
            .setTitle("Povoliť")
            .setOnClickListener(ParkedOnlyOnClickListener.create {
                carContext.requestPermissions(
                    listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                ) { granted, _ ->
                    if (granted.isNotEmpty()) {
                        LocationTracker.refresh()
                        finish()
                    } else {
                        CarToast.makeText(carContext, "Bez polohy navigácia nefunguje", CarToast.LENGTH_LONG).show()
                    }
                }
            })
            .build()
        return MessageTemplate.Builder(
            "amap potrebuje prístup k polohe, aby ukázal, kde ste, a mohol navigovať. Povolenie potvrďte v telefóne."
        )
            .setTitle("Povolenie polohy")
            .setHeaderAction(Action.APP_ICON)
            .addAction(allow)
            .build()
    }
}
