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
        resolver.update(SignalSample(0.0, Gear.PARK, atMs = 0))
        resolver.current(1_000) shouldBe DrivingState.PARKED
        resolver.current(1_001) shouldBe DrivingState.UNKNOWN
    }

    @Test
    fun `speed above zero is moving regardless of gear`() {
        val resolver = DrivingStateResolver()
        resolver.update(SignalSample(5.0, Gear.PARK, atMs = 0))
        resolver.current(0) shouldBe DrivingState.MOVING
    }

    @Test
    fun `drive or reverse at a standstill is moving, fail-safe`() {
        val resolver = DrivingStateResolver()
        resolver.update(SignalSample(0.0, Gear.DRIVE, atMs = 0))
        resolver.current(0) shouldBe DrivingState.MOVING

        val resolver2 = DrivingStateResolver()
        resolver2.update(SignalSample(0.0, Gear.REVERSE, atMs = 0))
        resolver2.current(0) shouldBe DrivingState.MOVING
    }

    @Test
    fun `park at any speed reading of zero is parked`() {
        val resolver = DrivingStateResolver()
        resolver.update(SignalSample(0.0, Gear.PARK, atMs = 0))
        resolver.current(0) shouldBe DrivingState.PARKED
    }

    @Test
    fun `neutral at zero for more than the parked grace period is parked, less is moving`() {
        val resolver = DrivingStateResolver(parkedAfterMs = 2_000)
        resolver.update(SignalSample(0.0, Gear.NEUTRAL, atMs = 0))
        resolver.update(SignalSample(0.0, Gear.NEUTRAL, atMs = 1_000))
        resolver.current(1_000) shouldBe DrivingState.MOVING

        resolver.update(SignalSample(0.0, Gear.NEUTRAL, atMs = 2_500))
        resolver.current(2_500) shouldBe DrivingState.PARKED
    }

    @Test
    fun `NaN speed is moving, even in park`() {
        val resolver = DrivingStateResolver()
        resolver.update(SignalSample(Double.NaN, Gear.PARK, atMs = 0))
        resolver.current(0) shouldBe DrivingState.MOVING
    }

    @Test
    fun `a null sample update leaves the resolver unchanged`() {
        val resolver = DrivingStateResolver()
        resolver.update(SignalSample(0.0, Gear.PARK, atMs = 0))
        resolver.update(null)
        resolver.current(0) shouldBe DrivingState.PARKED
    }

    @Test
    fun `missing speed and gear with no elapsed zero time is unknown`() {
        val resolver = DrivingStateResolver()
        resolver.update(SignalSample(null, null, atMs = 0))
        resolver.current(0) shouldBe DrivingState.UNKNOWN
    }
}
