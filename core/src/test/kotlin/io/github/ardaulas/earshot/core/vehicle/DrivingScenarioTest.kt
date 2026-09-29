package io.github.ardaulas.earshot.core.vehicle

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DrivingScenarioTest {
    @Test
    fun `a parked scenario always samples zero speed in park`() {
        DrivingScenario.PARKED.sampleAt(0) shouldBe ScenarioSample(0.0, Gear.PARK)
        DrivingScenario.PARKED.sampleAt(999_999) shouldBe ScenarioSample(0.0, Gear.PARK)
    }

    @Test
    fun `the city drive interpolates speed linearly between keyframes`() {
        val scenario = DrivingScenario.CITY_DRIVE
        scenario.sampleAt(0) shouldBe ScenarioSample(0.0, Gear.PARK)
        scenario.sampleAt(3_000) shouldBe ScenarioSample(0.0, Gear.DRIVE)
        scenario.sampleAt(10_000) shouldBe ScenarioSample(25.0, Gear.DRIVE)
    }

    @Test
    fun `the last keyframe holds forever`() {
        DrivingScenario.CITY_DRIVE.sampleAt(1_000_000) shouldBe ScenarioSample(50.0, Gear.DRIVE)
    }

    @Test
    fun `signal lost still reports a sample before it drops`() {
        DrivingScenario.SIGNAL_LOST.sampleAt(0) shouldBe ScenarioSample(50.0, Gear.DRIVE)
        DrivingScenario.SIGNAL_LOST.sampleAt(4_999) shouldBe ScenarioSample(50.0, Gear.DRIVE)
    }

    @Test
    fun `signal lost returns null from 5 seconds onward`() {
        DrivingScenario.SIGNAL_LOST.sampleAt(5_000) shouldBe null
        DrivingScenario.SIGNAL_LOST.sampleAt(20_000) shouldBe null
    }

    @Test
    fun `requires at least one keyframe`() {
        shouldThrow<IllegalArgumentException> {
            DrivingScenario("empty", emptyList())
        }
    }

    @Test
    fun `requires keyframes in strictly increasing time order`() {
        shouldThrow<IllegalArgumentException> {
            DrivingScenario(
                "out of order",
                listOf(Keyframe(1_000, 0.0, Gear.PARK), Keyframe(500, 0.0, Gear.PARK)),
            )
        }
        shouldThrow<IllegalArgumentException> {
            DrivingScenario(
                "duplicate timestamps",
                listOf(Keyframe(0, 0.0, Gear.PARK), Keyframe(0, 10.0, Gear.DRIVE)),
            )
        }
    }

    @Test
    fun `toString is the scenario name`() {
        DrivingScenario.CITY_DRIVE.toString() shouldBe "City drive"
    }

    @Test
    fun `ALL lists every built-in scenario`() {
        DrivingScenario.ALL shouldBe listOf(DrivingScenario.PARKED, DrivingScenario.CITY_DRIVE, DrivingScenario.SIGNAL_LOST)
    }
}
