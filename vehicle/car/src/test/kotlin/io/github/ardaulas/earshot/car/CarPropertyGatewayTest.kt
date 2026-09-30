package io.github.ardaulas.earshot.car

import io.github.ardaulas.earshot.core.policy.DrivingState
import io.github.ardaulas.earshot.core.requirements.Verifies
import io.github.ardaulas.earshot.core.time.MonotonicClock
import io.github.ardaulas.earshot.core.vehicle.ClimateProperty
import io.github.ardaulas.earshot.core.vehicle.DrivingStateResolver
import io.github.ardaulas.earshot.core.vehicle.Gear
import io.github.ardaulas.earshot.core.vehicle.ReadResult
import io.github.ardaulas.earshot.core.vehicle.SimulatedVehicleGateway
import io.github.ardaulas.earshot.core.vehicle.WriteResult
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class CarPropertyGatewayTest {
    private var nowMs = 0L
    private val clock = MonotonicClock { nowMs * 1_000_000 }
    private val car = FakeCarProperties()

    private fun gateway(realClimate: Boolean) = CarPropertyGateway(car, clock, realClimate, SimulatedVehicleGateway(clock))

    private fun drive(
        speedMs: Float?,
        gear: Int?,
    ) {
        car.floats[CarIds.PERF_VEHICLE_SPEED to 0] = speedMs
        car.ints[CarIds.GEAR_SELECTION to 0] = gear
    }

    /** Polls the gateway into a resolver the way the app does, every 200 ms. */
    private fun stateAfter(
        gw: CarPropertyGateway,
        ms: Long,
    ): DrivingState {
        val resolver = DrivingStateResolver()
        val end = nowMs + ms
        while (nowMs <= end) {
            resolver.update(gw.latestSignals())
            nowMs += 200
        }
        return resolver.current(nowMs)
    }

    @Test
    @Verifies("SR-22")
    fun `speed and gear from the car API drive the resolver`() {
        val gw = gateway(realClimate = false)
        drive(0f, CarIds.GEAR_PARK)
        stateAfter(gw, 1_000) shouldBe DrivingState.PARKED
        drive(13.9f, CarIds.GEAR_DRIVE)
        stateAfter(gw, 400) shouldBe DrivingState.MOVING
        gw.latestSignals()?.speedKmh shouldBe (13.9f * 3.6)
        gw.latestSignals()?.gear shouldBe Gear.DRIVE
    }

    @Test
    @Verifies("SR-22")
    fun `speed wins over a conflicting park gear`() {
        val gw = gateway(realClimate = false)
        drive(5f, CarIds.GEAR_PARK)
        stateAfter(gw, 400) shouldBe DrivingState.MOVING
    }

    @Test
    @Verifies("SR-22")
    fun `unreadable signals age out to unknown`() {
        val gw = gateway(realClimate = false)
        drive(0f, CarIds.GEAR_PARK)
        val resolver = DrivingStateResolver()
        resolver.update(gw.latestSignals())
        drive(null, null)
        repeat(8) {
            nowMs += 200
            resolver.update(gw.latestSignals())
        }
        resolver.current(nowMs) shouldBe DrivingState.UNKNOWN
    }

    @Test
    @Verifies("SR-22", "SR-13")
    fun `a disconnected car service gives no signals and unavailable climate`() =
        runTest {
            val gw = gateway(realClimate = true)
            drive(0f, CarIds.GEAR_PARK)
            car.connected = false
            gw.isAvailable shouldBe false
            gw.latestSignals() shouldBe null
            gw.read(ClimateProperty.CABIN_TEMPERATURE_C) shouldBe ReadResult.Unavailable
            gw.write(ClimateProperty.CABIN_TEMPERATURE_C, 21) shouldBe WriteResult.Unavailable
        }

    @Test
    @Verifies("SR-22")
    fun `gear falls back to CURRENT_GEAR when GEAR_SELECTION is unreadable`() {
        val gw = gateway(realClimate = false)
        car.ints[CarIds.CURRENT_GEAR to 0] = CarIds.GEAR_REVERSE
        gw.latestSignals()?.gear shouldBe Gear.REVERSE
    }

    @Test
    fun `without the climate permission, climate goes to the simulated vehicle and nothing is written to the car`() =
        runTest {
            val gw = gateway(realClimate = false)
            gw.write(ClimateProperty.CABIN_TEMPERATURE_C, 23) shouldBe WriteResult.Ok
            gw.read(ClimateProperty.CABIN_TEMPERATURE_C) shouldBe ReadResult.Value(23)
            car.writes.isEmpty() shouldBe true
        }

    @Test
    @Verifies("SR-17")
    fun `real climate writes go to every seat area and read back from the car`() =
        runTest {
            car.areas[CarIds.HVAC_TEMPERATURE_SET] = intArrayOf(49, 68)
            val gw = gateway(realClimate = true)
            gw.write(ClimateProperty.CABIN_TEMPERATURE_C, 22) shouldBe WriteResult.Ok
            car.writes.map { it.second } shouldBe listOf(49, 68)
            gw.read(ClimateProperty.CABIN_TEMPERATURE_C) shouldBe ReadResult.Value(22)
            gw.write(ClimateProperty.FRONT_DEFROST, 1) shouldBe WriteResult.Ok
            gw.read(ClimateProperty.FRONT_DEFROST) shouldBe ReadResult.Value(1)
        }

    @Test
    @Verifies("SR-14", "SR-17")
    fun `rejected, unsupported and out-of-range real writes are reported as rejected`() =
        runTest {
            val gw = gateway(realClimate = true)
            gw.write(ClimateProperty.FAN_LEVEL, 3) shouldBe WriteResult.Rejected
            car.areas[CarIds.HVAC_FAN_SPEED] = intArrayOf(1)
            gw.write(ClimateProperty.FAN_LEVEL, 9) shouldBe WriteResult.Rejected
            car.rejectWrites = true
            gw.write(ClimateProperty.FAN_LEVEL, 3) shouldBe WriteResult.Rejected
            gw.read(ClimateProperty.AC) shouldBe ReadResult.Unavailable
        }
}
