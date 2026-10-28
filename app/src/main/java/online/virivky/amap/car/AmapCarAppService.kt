package online.virivky.amap.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/** Vstupný bod pre Android Auto. */
class AmapCarAppService : CarAppService() {
    // Aplikácia sa inštaluje mimo Google Play, preto povolíme všetkých hostiteľov
    // (Android Auto aj prípadné testovacie nástroje ako Desktop Head Unit).
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = AmapSession()
}
