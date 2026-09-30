package io.github.ardaulas.earshot.core.vehicle

/**
 * The allowlist of vehicle properties the assistant may write: comfort functions only. Anything not
 * listed here cannot be expressed as a write, so it is refused by construction (deny by default).
 */
enum class ClimateProperty {
    CABIN_TEMPERATURE_C,
    FAN_LEVEL,
    FRONT_DEFROST,
    REAR_DEFROST,
    AC,
}

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE }

/** One reading of the driving signals. Either value may be missing. */
data class SignalSample(
    val speedKmh: Double?,
    val gear: Gear?,
    /** Monotonic time of the reading, in milliseconds. */
    val atMs: Long,
)

sealed interface ReadResult {
    data class Value(
        val value: Int,
    ) : ReadResult

    data object Unavailable : ReadResult
}

sealed interface WriteResult {
    data object Ok : WriteResult

    data object Rejected : WriteResult

    data object TimedOut : WriteResult

    data object Unavailable : WriteResult
}

/**
 * Access to the vehicle. Booleans are written and read as 1 / 0. Implementations: the simulated
 * vehicle in this module, and later the car property API on the automotive emulator.
 */
interface VehicleGateway {
    /** False when the vehicle connection is down; writes must then return [WriteResult.Unavailable]. */
    val isAvailable: Boolean

    suspend fun read(property: ClimateProperty): ReadResult

    /**
     * Writes one property. [notAfterMs] (monotonic ms) is the latest time the write may start; an
     * implementation that has preparation work of its own must check it again immediately before the
     * effect and return [WriteResult.TimedOut] if it has passed.
     */
    suspend fun write(
        property: ClimateProperty,
        value: Int,
        notAfterMs: Long = Long.MAX_VALUE,
    ): WriteResult

    /** The latest driving-signal reading, or null if none has arrived. */
    fun latestSignals(): SignalSample?
}
