package io.github.ardaulas.earshot.car

import io.github.ardaulas.earshot.core.time.MonotonicClock
import io.github.ardaulas.earshot.core.vehicle.ClimateProperty
import io.github.ardaulas.earshot.core.vehicle.ReadResult
import io.github.ardaulas.earshot.core.vehicle.SignalSample
import io.github.ardaulas.earshot.core.vehicle.SimulatedVehicleGateway
import io.github.ardaulas.earshot.core.vehicle.VehicleGateway
import io.github.ardaulas.earshot.core.vehicle.WriteResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Vehicle access through the car API.
 *
 * - Speed and gear are read by [poll] on [io] (the app calls it every 200 ms off the main thread) into
 *   a cached sample; [latestSignals] only returns the cache and never blocks. A speed value whose own
 *   vehicle timestamp is older than [speedFreshNs] counts as unreadable. If polling stops, the cached
 *   sample ages and the resolver's staleness rule makes the state unknown.
 * - Climate goes to the car only when [realClimate] is true (CONTROL_CAR_CLIMATE on a privileged
 *   emulator install), otherwise to [simulatedClimate]. Area ids are discovered once, at construction.
 *   Relative reads need every seat area to agree. The write deadline is checked right before the first
 *   platform write.
 * - Climate reads and writes run on their own scope and the caller only awaits them, so a caller's
 *   timeout really stops the wait even while a blocking car call carries on (pre-review F7). A write
 *   that is still running when the caller gives up may still take effect; the turn engine says so.
 */
class CarPropertyGateway(
    private val car: CarProperties,
    private val clock: MonotonicClock,
    val realClimate: Boolean,
    private val simulatedClimate: SimulatedVehicleGateway,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val speedFreshNs: Long = 2_000_000_000L,
) : VehicleGateway {
    private val tempAreas = car.areaIds(CarIds.HVAC_TEMPERATURE_SET)
    private val fanAreas = car.areaIds(CarIds.HVAC_FAN_SPEED)
    private val acAreas = car.areaIds(CarIds.HVAC_AC_ON)

    private val calls = CoroutineScope(SupervisorJob() + io)

    @Volatile private var cached: SignalSample? = null

    /** Runs a blocking car call off the caller; cancelling the caller stops the wait, not the call. */
    private suspend fun <T> offload(block: () -> T): T = calls.async { block() }.await()

    override val isAvailable: Boolean get() = car.connected

    /** Reads speed and gear from the car into the cache. Call off the main thread, periodically. */
    suspend fun poll() {
        cached =
            withContext(io) {
                if (!car.connected) return@withContext null
                // The sample is stamped with the time before the reads, so a slow read ages it rather
                // than making it look fresh (re-audit 3, N1).
                val readStartMs = clock.millis()
                val timed = car.readFloatTimed(CarIds.PERF_VEHICLE_SPEED, CarIds.AREA_GLOBAL)
                val gear =
                    car.readInt(CarIds.GEAR_SELECTION, CarIds.AREA_GLOBAL)
                        ?: car.readInt(CarIds.CURRENT_GEAR, CarIds.AREA_GLOBAL)
                // Fresh means neither older than the limit nor from the future, checked after both reads.
                val age = timed?.let { car.elapsedRealtimeNanos() - it.second }
                val fresh = timed?.takeIf { age != null && age in 0..speedFreshNs }?.first
                CarSignalMapper.sample(fresh, gear, readStartMs)
            }
    }

    override fun latestSignals(): SignalSample? = if (car.connected) cached else null

    override suspend fun read(property: ClimateProperty): ReadResult {
        if (!realClimate) return simulatedClimate.read(property)
        val value =
            offload {
                if (!car.connected) return@offload null
                when (property) {
                    ClimateProperty.CABIN_TEMPERATURE_C -> {
                        agreed(tempAreas.map { car.readFloat(CarIds.HVAC_TEMPERATURE_SET, it)?.takeIf(Float::isFinite)?.roundToInt() })
                    }

                    ClimateProperty.FAN_LEVEL -> {
                        agreed(fanAreas.map { car.readInt(CarIds.HVAC_FAN_SPEED, it) })
                    }

                    ClimateProperty.AC -> {
                        agreed(acAreas.map { car.readBoolean(CarIds.HVAC_AC_ON, it)?.toInt() })
                    }

                    ClimateProperty.FRONT_DEFROST -> {
                        car.readBoolean(CarIds.HVAC_DEFROSTER, CarIds.WINDOW_FRONT)?.toInt()
                    }

                    ClimateProperty.REAR_DEFROST -> {
                        car.readBoolean(CarIds.HVAC_DEFROSTER, CarIds.WINDOW_REAR)?.toInt()
                    }
                }
            }
        return value?.let { ReadResult.Value(it) } ?: ReadResult.Unavailable
    }

    override suspend fun write(
        property: ClimateProperty,
        value: Int,
        notAfterMs: Long,
        guard: () -> Boolean,
    ): WriteResult {
        if (!realClimate) return simulatedClimate.write(property, value, notAfterMs, guard)
        if (value !in SimulatedVehicleGateway.validRange(property)) return WriteResult.Rejected
        return offload {
            if (!car.connected) return@offload WriteResult.Unavailable
            val areas =
                when (property) {
                    ClimateProperty.CABIN_TEMPERATURE_C -> tempAreas
                    ClimateProperty.FAN_LEVEL -> fanAreas
                    ClimateProperty.AC -> acAreas
                    ClimateProperty.FRONT_DEFROST -> intArrayOf(CarIds.WINDOW_FRONT)
                    ClimateProperty.REAR_DEFROST -> intArrayOf(CarIds.WINDOW_REAR)
                }
            if (areas.isEmpty()) return@offload WriteResult.Rejected
            // The deadline and the guard (turn still waiting, driving state unchanged) are checked
            // before every area's platform call, not only the first (audit re-check #8, re-audit 3
            // N12, re-audit 5 N22). Stopping after some areas changed is reported as partial.
            var written = 0
            var rejected = 0
            for (area in areas) {
                if (clock.millis() > notAfterMs) {
                    return@offload if (written > 0) WriteResult.Partial else WriteResult.TimedOut
                }
                if (!guard()) return@offload if (written > 0) WriteResult.Partial else WriteResult.Aborted
                val ok =
                    when (property) {
                        ClimateProperty.CABIN_TEMPERATURE_C -> {
                            car.writeFloat(CarIds.HVAC_TEMPERATURE_SET, area, value.toFloat())
                        }

                        ClimateProperty.FAN_LEVEL -> {
                            car.writeInt(CarIds.HVAC_FAN_SPEED, area, value)
                        }

                        ClimateProperty.AC -> {
                            car.writeBoolean(CarIds.HVAC_AC_ON, area, value == 1)
                        }

                        ClimateProperty.FRONT_DEFROST, ClimateProperty.REAR_DEFROST -> {
                            car.writeBoolean(
                                CarIds.HVAC_DEFROSTER,
                                area,
                                value == 1,
                            )
                        }
                    }
                if (ok) written++ else rejected++
            }
            when {
                rejected == 0 -> WriteResult.Ok
                written == 0 -> WriteResult.Rejected
                else -> WriteResult.Partial
            }
        }
    }

    /** The single-zone value: only when every area reports the same value. */
    private fun agreed(values: List<Int?>): Int? = values.firstOrNull()?.takeIf { v -> values.all { it == v } }

    private fun Boolean.toInt() = if (this) 1 else 0
}
