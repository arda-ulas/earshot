package io.github.ardaulas.earshot.core.vehicle

import io.github.ardaulas.earshot.core.policy.DrivingState
import io.github.ardaulas.earshot.core.requirements.Verifies
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

@Verifies("SR-6")
class DrivingStateResolverTest {
    @Test
    fun `no sample at all is unknown`() {
        val resolver = DrivingStateResolver()
        resolver.current(0) shouldBe DrivingState.UNKNOWN
    }

    @Test
    fun `a stale reading is unknown`() {
        val resolver = DrivingStateResolver(staleAfterMs = 1_000)
        resolver.update(SignalSample(0.0, Gear.PARK, atMs = 0), 0)
        resolver.current(1_000) shouldBe DrivingState.PARKED
        resolver.current(1_001) shouldBe DrivingState.UNKNOWN
    }

    @Test
    fun `speed above zero is moving regardless of gear`() {
        val resolver = DrivingStateResolver()
        resolver.update(SignalSample(5.0, Gear.PARK, atMs = 0), 0)
        resolver.current(0) shouldBe DrivingState.MOVING
    }

    @Test
    fun `drive or reverse at a standstill is moving, fail-safe`() {
        val resolver = DrivingStateResolver()
        resolver.update(SignalSample(0.0, Gear.DRIVE, atMs = 0), 0)
        resolver.current(0) shouldBe DrivingState.MOVING

        val resolver2 = DrivingStateResolver()
        resolver2.update(SignalSample(0.0, Gear.REVERSE, atMs = 0), 0)
        resolver2.current(0) shouldBe DrivingState.MOVING
    }

    @Test
    fun `park at any speed reading of zero is parked`() {
        val resolver = DrivingStateResolver()
        resolver.update(SignalSample(0.0, Gear.PARK, atMs = 0), 0)
        resolver.current(0) shouldBe DrivingState.PARKED
    }

    @Test
    fun `neutral at zero for more than the parked grace period is parked, less is moving`() {
        val resolver = DrivingStateResolver(parkedAfterMs = 2_000)
        for (t in 0L..1_000L step 500) resolver.update(SignalSample(0.0, Gear.NEUTRAL, atMs = t), t)
        resolver.current(1_000) shouldBe DrivingState.MOVING

        for (t in 1_500L..2_500L step 500) resolver.update(SignalSample(0.0, Gear.NEUTRAL, atMs = t), t)
        resolver.current(2_500) shouldBe DrivingState.PARKED
    }

    @Test
    fun `NaN speed is unknown (handled as moving), even in park`() {
        val resolver = DrivingStateResolver()
        resolver.update(SignalSample(Double.NaN, Gear.PARK, atMs = 0), 0)
        resolver.current(0) shouldBe DrivingState.UNKNOWN
        resolver.current(0).effective shouldBe DrivingState.MOVING
    }

    @Test
    fun `losing every signal is unknown at once, not the last state`() {
        val resolver = DrivingStateResolver()
        resolver.update(SignalSample(0.0, Gear.PARK, atMs = 0), 0)
        resolver.update(null, 0)
        resolver.current(0) shouldBe DrivingState.UNKNOWN
    }

    @Test
    fun `missing speed and gear with no elapsed zero time is unknown`() {
        val resolver = DrivingStateResolver()
        resolver.update(SignalSample(null, null, atMs = 0), 0)
        resolver.current(0) shouldBe DrivingState.UNKNOWN
    }
}
