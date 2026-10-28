package online.virivky.amap.data

/** Tvar ikony polohy na mape. */
enum class VehicleModel(val label: String) {
    SEDAN("Sedan"),
    SUV("SUV"),
    SPORT("Športové"),
    MOTO("Motorka"),
    ARROW("Šípka"),
}

/** Farba ikony polohy na mape. */
enum class VehicleColor(val label: String, val argb: Int) {
    WHITE("Biela", 0xFFF2F4F7.toInt()),
    GREEN("Kawasaki zelená", 0xFF69BE28.toInt()),
    BLACK("Čierna", 0xFF2A2E36.toInt()),
    SILVER("Strieborná", 0xFFAEB5BF.toInt()),
    RED("Červená", 0xFFD62E36.toInt()),
    BLUE("Modrá", 0xFF2F6FE0.toInt()),
}
