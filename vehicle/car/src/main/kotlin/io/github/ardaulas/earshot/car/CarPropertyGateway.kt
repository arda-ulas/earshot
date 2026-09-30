package io.github.ardaulas.earshot.car

import io.github.ardaulas.earshot.core.time.MonotonicClock
import io.github.ardaulas.earshot.core.vehicle.ClimateProperty
import io.github.ardaulas.earshot.core.vehicle.ReadResult
import io.github.ardaulas.earshot.core.vehicle.SignalSample
import io.github.ardaulas.earshot.core.vehicle.SimulatedVehicleGateway
import io.github.ardaulas.earshot.core.vehicle.VehicleGateway
import io.github.ardaulas.earshot.core.vehicle.WriteResult
import kotlin.math.roundToInt

/**
 * Vehicle access through the car API. Speed and gear are always read from the car (polled on every
 * call, so a failing read shows up immediately as a missing signal). Climate goes to the car only
 * when [realClimate] is true, which needs the signature|privileged permission CONTROL_CAR_CLIMATE;
 * otherwise it goes to [simulatedClimate] and the app says so ("simulated climate writes").
 */
class CarPropertyGateway(
    private val car: CarProperties,
    private val clock: MonotonicClock,
    val realClimate: Boolean,
    private val simulatedClimate: SimulatedVehicleGateway,
) : VehicleGateway {
    override val isAvailable: Boolean get() = car.connected

    override fun latestSignals(): SignalSample? {
        if (!car.connected) return null
        val speed = car.readFloat(CarIds.PERF_VEHICLE_SPEED, CarIds.AREA_GLOBAL)
        val gear =
            car.readInt(CarIds.GEAR_SELECTION, CarIds.AREA_GLOBAL)
                ?: car.readInt(CarIds.CURRENT_GEAR, CarIds.AREA_GLOBAL)
        return CarSignalMapper.sample(speed, gear, clock.millis())
    }

    override suspend fun read(property: ClimateProperty): ReadResult {
        if (!car.connected) return ReadResult.Unavailable
        if (!realClimate) return simulatedClimate.read(property)
        val value =
            when (property) {
                ClimateProperty.CABIN_TEMPERATURE_C -> {
                    seatArea(CarIds.HVAC_TEMPERATURE_SET)?.let { car.readFloat(CarIds.HVAC_TEMPERATURE_SET, it) }?.roundToInt()
                }

                ClimateProperty.FAN_LEVEL -> {
                    seatArea(CarIds.HVAC_FAN_SPEED)?.let { car.readInt(CarIds.HVAC_FAN_SPEED, it) }
                }

                ClimateProperty.AC -> {
                    seatArea(CarIds.HVAC_AC_ON)?.let { car.readBoolean(CarIds.HVAC_AC_ON, it) }?.toInt()
                }

                ClimateProperty.FRONT_DEFROST -> {
                    car.readBoolean(CarIds.HVAC_DEFROSTER, CarIds.WINDOW_FRONT)?.toInt()
                }

                ClimateProperty.REAR_DEFROST -> {
                    car.readBoolean(CarIds.HVAC_DEFROSTER, CarIds.WINDOW_REAR)?.toInt()
                }
            }
        return value?.let { ReadResult.Value(it) } ?: ReadResult.Unavailable
    }

    override suspend fun write(
        property: ClimateProperty,
        value: Int,
    ): WriteResult {
        if (!car.connected) return WriteResult.Unavailable
        if (!realClimate) return simulatedClimate.write(property, value)
        if (value !in SimulatedVehicleGateway.validRange(property)) return WriteResult.Rejected
        val ok =
            when (property) {
                // Single-zone model: every seat area gets the same value.
                ClimateProperty.CABIN_TEMPERATURE_C -> {
                    writeAll(CarIds.HVAC_TEMPERATURE_SET) { area -> car.writeFloat(CarIds.HVAC_TEMPERATURE_SET, area, value.toFloat()) }
                }

                ClimateProperty.FAN_LEVEL -> {
                    writeAll(CarIds.HVAC_FAN_SPEED) { area -> car.writeInt(CarIds.HVAC_FAN_SPEED, area, value) }
                }

                ClimateProperty.AC -> {
                    writeAll(CarIds.HVAC_AC_ON) { area -> car.writeBoolean(CarIds.HVAC_AC_ON, area, value == 1) }
                }

                ClimateProperty.FRONT_DEFROST -> {
                    car.writeBoolean(CarIds.HVAC_DEFROSTER, CarIds.WINDOW_FRONT, value == 1)
                }

                ClimateProperty.REAR_DEFROST -> {
                    car.writeBoolean(CarIds.HVAC_DEFROSTER, CarIds.WINDOW_REAR, value == 1)
                }
            }
        return if (ok) WriteResult.Ok else WriteResult.Rejected
    }

    private fun seatArea(propertyId: Int): Int? = car.areaIds(propertyId).firstOrNull()

    private inline fun writeAll(
        propertyId: Int,
        write: (Int) -> Boolean,
    ): Boolean {
        val areas = car.areaIds(propertyId)
        if (areas.isEmpty()) return false
        var ok = true
        for (area in areas) ok = write(area) && ok
        return ok
    }

    private fun Boolean.toInt() = if (this) 1 else 0
}
