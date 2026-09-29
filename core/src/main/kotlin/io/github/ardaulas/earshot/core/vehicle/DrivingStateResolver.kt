package io.github.ardaulas.earshot.core.vehicle

import io.github.ardaulas.earshot.core.policy.DrivingState

/**
 * Derives the driving state from signal readings. Rules, first match wins:
 * 1. No reading, or the latest is older than [staleAfterMs]: UNKNOWN (handled as moving, SG-4).
 * 2. Speed above zero: MOVING.
 * 3. Gear in drive or reverse: MOVING, even when stopped (fail-safe; see the ADR on fail-safe defaults).
 * 4. Gear in park: PARKED.
 * 5. Speed zero for more than [parkedAfterMs]: PARKED. Zero for less: MOVING (it was just moving).
 * 6. Otherwise: UNKNOWN.
 *
 * Stateful only in remembering when the speed last became zero; not thread-safe, call from one thread.
 */
class DrivingStateResolver(
    private val staleAfterMs: Long = 1_000,
    private val parkedAfterMs: Long = 2_000,
) {
    private var latest: SignalSample? = null
    private var zeroSinceMs: Long? = null

    fun update(sample: SignalSample?) {
        if (sample == null) return
        latest = sample
        zeroSinceMs =
            when {
                sample.speedKmh == null || sample.speedKmh > 0.0 -> null
                else -> zeroSinceMs ?: sample.atMs
            }
    }

    fun current(nowMs: Long): DrivingState {
        val s = latest ?: return DrivingState.UNKNOWN
        if (nowMs - s.atMs > staleAfterMs) return DrivingState.UNKNOWN
        val speed = s.speedKmh
        if (speed != null && (speed.isNaN() || speed > 0.0)) return DrivingState.MOVING
        return when (s.gear) {
            Gear.DRIVE, Gear.REVERSE -> {
                DrivingState.MOVING
            }

            Gear.PARK -> {
                DrivingState.PARKED
            }

            Gear.NEUTRAL, null -> {
                val zeroSince = zeroSinceMs
                when {
                    speed == null || zeroSince == null -> DrivingState.UNKNOWN
                    s.atMs - zeroSince > parkedAfterMs -> DrivingState.PARKED
                    else -> DrivingState.MOVING
                }
            }
        }
    }
}
