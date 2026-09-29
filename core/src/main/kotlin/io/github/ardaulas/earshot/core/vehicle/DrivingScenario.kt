package io.github.ardaulas.earshot.core.vehicle

/** A point on a scripted drive. `signal = false` means no reading arrives (sensor or bus loss). */
data class Keyframe(
    val atMs: Long,
    val speedKmh: Double,
    val gear: Gear,
    val signal: Boolean = true,
)

data class ScenarioSample(
    val speedKmh: Double,
    val gear: Gear,
)

/**
 * A scripted driving-state timeline. Speed is interpolated linearly between keyframes; gear and
 * signal hold from the earlier keyframe. The last keyframe holds forever.
 */
class DrivingScenario(
    val name: String,
    private val keyframes: List<Keyframe>,
) {
    init {
        require(keyframes.isNotEmpty())
        require(keyframes.zipWithNext().all { (a, b) -> a.atMs < b.atMs }) { "keyframes must be in time order" }
    }

    fun sampleAt(elapsedMs: Long): ScenarioSample? {
        val idx = keyframes.indexOfLast { it.atMs <= elapsedMs }.coerceAtLeast(0)
        val k = keyframes[idx]
        if (!k.signal) return null
        val next = keyframes.getOrNull(idx + 1)
        val speed =
            if (next == null || elapsedMs <= k.atMs) {
                k.speedKmh
            } else {
                val f = (elapsedMs - k.atMs).toDouble() / (next.atMs - k.atMs)
                k.speedKmh + f * (next.speedKmh - k.speedKmh)
            }
        return ScenarioSample(speed, k.gear)
    }

    override fun toString() = name

    companion object {
        val PARKED = DrivingScenario("Parked", listOf(Keyframe(0, 0.0, Gear.PARK)))

        /** Parked, shift to drive, pull away, cruise at 50 km/h. */
        val CITY_DRIVE =
            DrivingScenario(
                "City drive",
                listOf(
                    Keyframe(0, 0.0, Gear.PARK),
                    Keyframe(3_000, 0.0, Gear.DRIVE),
                    Keyframe(5_000, 0.0, Gear.DRIVE),
                    Keyframe(15_000, 50.0, Gear.DRIVE),
                ),
            )

        /** Cruising, then the driving signals stop arriving. */
        val SIGNAL_LOST =
            DrivingScenario(
                "Signal lost",
                listOf(
                    Keyframe(0, 50.0, Gear.DRIVE),
                    Keyframe(5_000, 50.0, Gear.DRIVE, signal = false),
                ),
            )

        val ALL = listOf(PARKED, CITY_DRIVE, SIGNAL_LOST)
    }
}
