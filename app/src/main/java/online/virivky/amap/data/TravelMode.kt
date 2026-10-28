package online.virivky.amap.data

/** Ako sa ide k cieľu: autom, alebo pešo (iné trasy, priblíženie mapy a upozornenia). */
enum class TravelMode(val label: String) {
    CAR("Autom"),
    FOOT("Pešo"),
}
