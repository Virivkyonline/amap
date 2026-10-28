package online.virivky.amap.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import online.virivky.amap.util.CarLog

/** Vstupný bod pre Android Auto. */
class AmapCarAppService : CarAppService() {
    // Aplikácia sa inštaluje mimo Google Play, preto povolíme všetkých hostiteľov
    // (Android Auto aj prípadné testovacie nástroje ako Desktop Head Unit).
    override fun createHostValidator(): HostValidator {
        CarLog.add("Android Auto sa pripája k lech-map")
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        CarLog.add("Relácia v aute vytvorená")
        return AmapSession()
    }
}
