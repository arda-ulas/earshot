package io.github.ardaulas.earshot.core.vehicle

import io.github.ardaulas.earshot.core.command.Bounds
import io.github.ardaulas.earshot.core.time.MonotonicClock
import java.util.concurrent.ConcurrentHashMap

/**
 * A simulated cabin and driving-signal source. Climate writes change in-memory state; driving signals
 * come from a [DrivingScenario] played against the monotonic clock. Shown as simulated in the UI.
 */
class SimulatedVehicleGateway(
    private val clock: MonotonicClock,
    scenario: DrivingScenario = DrivingScenario.PARKED,
) : VehicleGateway {
    private val climate = ConcurrentHashMap(DEFAULT_CLIMATE)

    @Volatile private var scenario: DrivingScenario = scenario

    @Volatile private var scenarioStartMs: Long = clock.millis()

    @Volatile private var connected: Boolean = true

    override val isAvailable: Boolean get() = connected

    /** Simulates the vehicle connection dropping or coming back. */
    fun setConnected(value: Boolean) {
        connected = value
    }

    /** Starts [next] from its beginning. */
    fun play(next: DrivingScenario) {
        scenario = next
        scenarioStartMs = clock.millis()
    }

    val currentScenario: DrivingScenario get() = scenario

    fun snapshot(): Map<ClimateProperty, Int> = HashMap(climate)

    override suspend fun read(property: ClimateProperty): ReadResult =
        if (connected) ReadResult.Value(climate.getValue(property)) else ReadResult.Unavailable

    override suspend fun write(
        property: ClimateProperty,
        value: Int,
    ): WriteResult {
        if (!connected) return WriteResult.Unavailable
        if (value !in validRange(property)) return WriteResult.Rejected
        climate[property] = value
        return WriteResult.Ok
    }

    override fun latestSignals(): SignalSample? {
        if (!connected) return null
        val now = clock.millis()
        return scenario.sampleAt(now - scenarioStartMs)?.let { SignalSample(it.speedKmh, it.gear, now) }
    }

    companion object {
        val DEFAULT_CLIMATE =
            mapOf(
                ClimateProperty.CABIN_TEMPERATURE_C to 21,
                ClimateProperty.FAN_LEVEL to 2,
                ClimateProperty.FRONT_DEFROST to 0,
                ClimateProperty.REAR_DEFROST to 0,
                ClimateProperty.AC to 0,
            )

        fun validRange(property: ClimateProperty): IntRange =
            when (property) {
                ClimateProperty.CABIN_TEMPERATURE_C -> Bounds.TEMP_C
                ClimateProperty.FAN_LEVEL -> Bounds.FAN_LEVEL
                ClimateProperty.FRONT_DEFROST, ClimateProperty.REAR_DEFROST, ClimateProperty.AC -> 0..1
            }
    }
}
