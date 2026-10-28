package online.virivky.amap.data

/** Čomu sa má trasa vyhnúť. */
data class RouteOptions(
    val avoidMotorways: Boolean = false,
    /** Spoplatnené úseky vrátane diaľnic s diaľničnou známkou. */
    val avoidTolls: Boolean = false,
    val avoidFerries: Boolean = false,
) {
    /** Krátky popis, napr. „bez diaľnic, bez trajektov“. */
    val label: String
        get() = listOfNotNull(
            "bez diaľnic".takeIf { avoidMotorways },
            "bez spoplatnených úsekov".takeIf { avoidTolls },
            "bez trajektov".takeIf { avoidFerries },
        ).joinToString(", ")
}
