package online.virivky.amap.data

/** Pohľad na mapu počas navigácie. */
enum class MapPerspective(val label: String, val short: String) {
    THREE_D("3D pohľad", "3D"),
    HEADING_UP("2D v smere jazdy", "2D"),
    NORTH_UP("2D sever hore", "S"),
    ;

    fun next(): MapPerspective = entries[(ordinal + 1) % entries.size]
}

/** Farby mapy: podľa slnka (v aute podľa auta), stále denné alebo stále nočné. */
enum class MapTheme(val label: String) {
    AUTO("Automaticky (podľa slnka)"),
    DAY("Deň"),
    NIGHT("Noc"),
}
