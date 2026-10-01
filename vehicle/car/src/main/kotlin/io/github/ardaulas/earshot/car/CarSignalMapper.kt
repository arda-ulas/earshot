package io.github.ardaulas.earshot.car

import io.github.ardaulas.earshot.core.vehicle.Gear
import io.github.ardaulas.earshot.core.vehicle.SignalSample
import kotlin.math.abs

/**
 * Maps raw car-API values to core types. Pure: no platform classes. Anything it cannot interpret
 * becomes null, which the driving-state resolver treats as missing (unknown, handled as moving).
 */
object CarSignalMapper {
    /**
     * PERF_VEHICLE_SPEED is metres per second and negative when reversing. An unreadable value is null;
     * a nonsense value (NaN, infinite) is passed on as NaN so the resolver sees an invalid reading and
     * returns UNKNOWN, instead of losing the distinction (audit re-check N1).
     */
    fun speedKmh(metresPerSecond: Float?): Double? {
        if (metresPerSecond == null) return null
        if (metresPerSecond.isNaN() || metresPerSecond.isInfinite()) return Double.NaN
        return abs(metresPerSecond.toDouble()) * MS_TO_KMH
    }

    /** VehicleGear is a bit flag: park, reverse, neutral, drive, or a numbered forward gear. */
    fun gear(vehicleGear: Int?): Gear? =
        when {
            vehicleGear == null || vehicleGear == CarIds.GEAR_UNKNOWN -> {
                null
            }

            vehicleGear == CarIds.GEAR_PARK -> {
                Gear.PARK
            }

            vehicleGear == CarIds.GEAR_REVERSE -> {
                Gear.REVERSE
            }

            vehicleGear == CarIds.GEAR_NEUTRAL -> {
                Gear.NEUTRAL
            }

            // Drive and every numbered forward gear (GEAR_FIRST = 16 ... GEAR_NINTH = 4096).
            vehicleGear == CarIds.GEAR_DRIVE || (vehicleGear >= FIRST_GEAR && vehicleGear <= NINTH_GEAR && isPowerOfTwo(vehicleGear)) -> {
                Gear.DRIVE
            }

            else -> {
                null
            }
        }

    /**
     * One reading, stamped with the time it was taken. With neither value readable there is no
     * reading at all (null), so the resolver's last one ages out and the state becomes unknown.
     */
    fun sample(
        metresPerSecond: Float?,
        vehicleGear: Int?,
        nowMs: Long,
    ): SignalSample? {
        val speed = speedKmh(metresPerSecond)
        val gear = gear(vehicleGear)
        if (speed == null && gear == null) return null
        return SignalSample(speed, gear, nowMs)
    }

    private fun isPowerOfTwo(v: Int) = v > 0 && (v and (v - 1)) == 0

    private const val MS_TO_KMH = 3.6
    private const val FIRST_GEAR = 16
    private const val NINTH_GEAR = 4096
}
