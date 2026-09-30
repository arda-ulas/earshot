package io.github.ardaulas.earshot.core.vehicle

import io.github.ardaulas.earshot.core.policy.DrivingState

/**
 * Derives the driving state from signal readings. Rules, first match wins:
 * 1. No reading, the latest is older than [staleAfterMs], or it is from the future: UNKNOWN (handled
 *    as moving, SG-4).
 * 2. The latest reading is invalid (speed NaN, infinite or negative): UNKNOWN.
 * 3. Speed above zero: MOVING.
 * 4. Gear in drive or reverse: MOVING, even when stopped (fail-safe; see the ADR on fail-safe defaults).
 * 5. Gear in park with a known zero speed: PARKED. Park with no readable speed: UNKNOWN (audit
 *    re-check N1: a park gear alone is not enough).
 * 6. No gear reading: UNKNOWN, even at zero speed (a car stopped in drive looks the same).
 * 7. Neutral with speed zero, continuously, for more than [parkedAfterMs]: PARKED. Zero for less:
 *    MOVING (it was just moving).
 * 8. Otherwise: UNKNOWN.
 *
 * "Continuously" means consecutive valid zero-speed readings with no gap longer than [staleAfterMs]
 * between them; an invalid reading, a gap, or a reading older than the previous one resets it
 * (audit #6). Not thread-safe; call from one thread.
 */
class DrivingStateResolver(
    private val staleAfterMs: Long = 1_000,
    private val parkedAfterMs: Long = 2_000,
) {
    private var latest: SignalSample? = null
    private var latestValid = false
    private var zeroSinceMs: Long? = null

    /**
     * Takes one reading. A reading stamped after [nowMs] is dropped, not stored: kept, it would block
     * every later valid reading until the clock caught up with it (re-audit 4, #6).
     */
    fun update(
        sample: SignalSample?,
        nowMs: Long,
    ) {
        if (sample == null || sample.atMs > nowMs) return
        val previous = latest
        // Readings that go back in time are ignored; they cannot extend or restart anything.
        if (previous != null && sample.atMs < previous.atMs) return
        val speed = sample.speedKmh
        val valid = speed == null || (speed.isFinite() && speed >= 0.0)
        val gap = previous == null || sample.atMs - previous.atMs > staleAfterMs
        latest = sample
        latestValid = valid
        zeroSinceMs =
            when {
                !valid || speed == null || speed > 0.0 -> null
                gap || !wasZero(previous) -> sample.atMs
                else -> zeroSinceMs ?: sample.atMs
            }
    }

    private fun wasZero(previous: SignalSample?) = previous?.speedKmh == 0.0 && zeroSinceMs != null

    fun current(nowMs: Long): DrivingState {
        val s = latest ?: return DrivingState.UNKNOWN
        if (s.atMs > nowMs || nowMs - s.atMs > staleAfterMs) return DrivingState.UNKNOWN
        if (!latestValid) return DrivingState.UNKNOWN
        val speed = s.speedKmh
        if (speed != null && speed > 0.0) return DrivingState.MOVING
        return when (s.gear) {
            Gear.DRIVE, Gear.REVERSE -> {
                DrivingState.MOVING
            }

            Gear.PARK -> {
                if (speed == 0.0) DrivingState.PARKED else DrivingState.UNKNOWN
            }

            // No gear reading: a car stopped in drive looks exactly like this, so never parked
            // (pre-review F5).
            null -> {
                DrivingState.UNKNOWN
            }

            Gear.NEUTRAL -> {
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
