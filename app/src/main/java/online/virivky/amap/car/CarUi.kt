package online.virivky.amap.car

import androidx.annotation.DrawableRes
import androidx.car.app.CarContext
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.core.graphics.drawable.IconCompat
import online.virivky.amap.nav.Instructions
import online.virivky.amap.nav.ManeuverIcons
import online.virivky.amap.nav.RouteStep
import java.time.ZonedDateTime
import kotlin.math.max

fun CarContext.carIcon(@DrawableRes id: Int): CarIcon =
    CarIcon.Builder(IconCompat.createWithResource(this, id)).build()

/** Prevod údajov o trase na modely Android Auto. */
object CarUi {
    fun distance(meters: Double): Distance = when {
        meters < 1000 -> Distance.create(Instructions.roundMeters(meters).toDouble(), Distance.UNIT_METERS)
        meters < 10_000 -> Distance.create(meters / 1000.0, Distance.UNIT_KILOMETERS_P1)
        else -> Distance.create(meters / 1000.0, Distance.UNIT_KILOMETERS)
    }

    fun estimate(meters: Double, seconds: Double): TravelEstimate {
        val s = max(0L, seconds.toLong())
        return TravelEstimate.Builder(distance(meters), ZonedDateTime.now().plusSeconds(s))
            .setRemainingTimeSeconds(s)
            .build()
    }

    fun step(step: RouteStep): Step {
        val type = maneuverType(step)
        val maneuver = Maneuver.Builder(type)
        if (type == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW) {
            maneuver.setRoundaboutExitNumber(step.exit ?: 1)
        }
        maneuver.setIcon(CarIcon.Builder(IconCompat.createWithBitmap(ManeuverIcons.bitmap(step))).build())
        val builder = Step.Builder(Instructions.title(step)).setManeuver(maneuver.build())
        Instructions.road(step)?.let { builder.setRoad(it) }
        return builder.build()
    }

    private fun maneuverType(step: RouteStep): Int {
        val m = step.modifier
        val left = m?.contains("left") == true
        val right = m?.contains("right") == true
        return when (step.type) {
            "depart" -> Maneuver.TYPE_DEPART
            "arrive" -> when {
                left -> Maneuver.TYPE_DESTINATION_LEFT
                right -> Maneuver.TYPE_DESTINATION_RIGHT
                else -> Maneuver.TYPE_DESTINATION
            }
            // Na Slovensku sa jazdí vpravo, kruhové objazdy idú proti smeru hodinových ručičiek.
            "roundabout", "rotary" ->
                if ((step.exit ?: 0) >= 1) Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW
                else Maneuver.TYPE_ROUNDABOUT_ENTER_CCW
            "exit roundabout", "exit rotary" -> Maneuver.TYPE_ROUNDABOUT_EXIT_CCW
            "fork" -> if (left) Maneuver.TYPE_FORK_LEFT else Maneuver.TYPE_FORK_RIGHT
            "merge" -> when {
                left -> Maneuver.TYPE_MERGE_LEFT
                right -> Maneuver.TYPE_MERGE_RIGHT
                else -> Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED
            }
            "on ramp" -> when (m) {
                "slight left" -> Maneuver.TYPE_ON_RAMP_SLIGHT_LEFT
                "left", "sharp left" -> Maneuver.TYPE_ON_RAMP_NORMAL_LEFT
                "slight right" -> Maneuver.TYPE_ON_RAMP_SLIGHT_RIGHT
                else -> Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT
            }
            "off ramp" -> when (m) {
                "slight left" -> Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT
                "left", "sharp left" -> Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT
                "slight right" -> Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT
                else -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
            }
            else -> when (m) {
                "uturn" -> Maneuver.TYPE_U_TURN_LEFT
                "sharp right" -> Maneuver.TYPE_TURN_SHARP_RIGHT
                "right" -> Maneuver.TYPE_TURN_NORMAL_RIGHT
                "slight right" -> if (step.type == "turn") Maneuver.TYPE_TURN_SLIGHT_RIGHT else Maneuver.TYPE_KEEP_RIGHT
                "sharp left" -> Maneuver.TYPE_TURN_SHARP_LEFT
                "left" -> Maneuver.TYPE_TURN_NORMAL_LEFT
                "slight left" -> if (step.type == "turn") Maneuver.TYPE_TURN_SLIGHT_LEFT else Maneuver.TYPE_KEEP_LEFT
                else -> if (step.type == "new name") Maneuver.TYPE_NAME_CHANGE else Maneuver.TYPE_STRAIGHT
            }
        }
    }
}
