package io.github.ardaulas.earshot.core.vehicle

import io.github.ardaulas.earshot.core.requirements.Verifies
import io.github.ardaulas.earshot.core.time.MonotonicClock
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private class FakeClock(
    var nowMs: Long = 0L,
) : MonotonicClock {
    override fun nanoTime(): Long = nowMs * 1_000_000
}

class SimulatedVehicleGatewayTest {
    @Test
    fun `starts from the default climate snapshot`() {
        val gateway = SimulatedVehicleGateway(FakeClock())
        gateway.snapshot() shouldBe SimulatedVehicleGateway.DEFAULT_CLIMATE
    }

    @Test
    fun `a valid write changes state and reads back`() =
        runTest {
            val gateway = SimulatedVehicleGateway(FakeClock())
            gateway.write(ClimateProperty.CABIN_TEMPERATURE_C, 19) shouldBe WriteResult.Ok
            gateway.read(ClimateProperty.CABIN_TEMPERATURE_C) shouldBe ReadResult.Value(19)
        }

    @Test
    @Verifies("SR-3")
    fun `rejects an out-of-range write and leaves the value unchanged`() =
        runTest {
            val gateway = SimulatedVehicleGateway(FakeClock())
            gateway.write(ClimateProperty.CABIN_TEMPERATURE_C, 50) shouldBe WriteResult.Rejected
            gateway.read(ClimateProperty.CABIN_TEMPERATURE_C) shouldBe
                ReadResult.Value(SimulatedVehicleGateway.DEFAULT_CLIMATE.getValue(ClimateProperty.CABIN_TEMPERATURE_C))
        }

    @Test
    @Verifies("SR-17")
    fun `boolean properties accept only 0 or 1`() =
        runTest {
            val gateway = SimulatedVehicleGateway(FakeClock())
            gateway.write(ClimateProperty.FRONT_DEFROST, 1) shouldBe WriteResult.Ok
            gateway.write(ClimateProperty.FRONT_DEFROST, 2) shouldBe WriteResult.Rejected
            gateway.read(ClimateProperty.FRONT_DEFROST) shouldBe ReadResult.Value(1)
        }

    @Test
    @Verifies("SR-13")
    fun `disconnected reads and writes are unavailable`() =
        runTest {
            val gateway = SimulatedVehicleGateway(FakeClock())
            gateway.setConnected(false)
            gateway.isAvailable shouldBe false
            gateway.read(ClimateProperty.CABIN_TEMPERATURE_C) shouldBe ReadResult.Unavailable
            gateway.write(ClimateProperty.CABIN_TEMPERATURE_C, 20) shouldBe WriteResult.Unavailable
        }

    @Test
    @Verifies("SR-13")
    fun `disconnected reports no driving signals`() {
        val gateway = SimulatedVehicleGateway(FakeClock(), DrivingScenario.CITY_DRIVE)
        gateway.setConnected(false)
        gateway.latestSignals() shouldBe null
    }

    @Test
    fun `latest signals follow the playing scenario from the moment it started`() {
        val clock = FakeClock(nowMs = 1_000)
        val gateway = SimulatedVehicleGateway(clock, DrivingScenario.PARKED)
        gateway.latestSignals() shouldBe SignalSample(0.0, Gear.PARK, 1_000)

        clock.nowMs = 4_000
        gateway.play(DrivingScenario.CITY_DRIVE)
        clock.nowMs = 4_000 + 3_000
        gateway.latestSignals() shouldBe SignalSample(0.0, Gear.DRIVE, clock.nowMs)
    }

    @Test
    @Verifies("SR-17")
    fun `validRange covers every allowlisted climate property`() {
        for (property in ClimateProperty.entries) {
            SimulatedVehicleGateway.validRange(property).isEmpty() shouldBe false
        }
    }
}
