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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class CarPropertyGatewayTest {
    private var nowMs = 0L
    private val clock = MonotonicClock { nowMs * 1_000_000 }
    private val car = FakeCarProperties()

    private fun gateway(realClimate: Boolean) =
        CarPropertyGateway(car, clock, realClimate, SimulatedVehicleGateway(clock), io = Dispatchers.Unconfined)

    private fun drive(
        speedMs: Float?,
        gear: Int?,
    ) {
        car.floats[CarIds.PERF_VEHICLE_SPEED to 0] = speedMs
        car.ints[CarIds.GEAR_SELECTION to 0] = gear
        car.nowNanos = nowMs * 1_000_000
    }

    /** Polls the gateway into a resolver the way the app does, every 200 ms. */
    private suspend fun stateAfter(
        gw: CarPropertyGateway,
        ms: Long,
    ): DrivingState {
        val resolver = DrivingStateResolver()
        val end = nowMs + ms
        while (nowMs <= end) {
            car.nowNanos = nowMs * 1_000_000
            gw.poll()
            resolver.update(gw.latestSignals(), nowMs)
            nowMs += 200
        }
        return resolver.current(nowMs - 200)
    }

    @Test
    @Verifies("SR-22")
    fun `speed and gear from the car API drive the resolver`() =
        runTest {
            val gw = gateway(realClimate = false)
            drive(0f, CarIds.GEAR_PARK)
            stateAfter(gw, 1_000) shouldBe DrivingState.PARKED
            drive(13.9f, CarIds.GEAR_DRIVE)
            stateAfter(gw, 400) shouldBe DrivingState.MOVING
            gw.latestSignals()?.gear shouldBe Gear.DRIVE
        }

    @Test
    @Verifies("SR-22")
    fun `speed wins over a conflicting park gear`() =
        runTest {
            val gw = gateway(realClimate = false)
            drive(5f, CarIds.GEAR_PARK)
            stateAfter(gw, 400) shouldBe DrivingState.MOVING
        }

    @Test
    @Verifies("SR-22", "SR-6")
    fun `NaN speed with a park gear is unknown, not parked (re-audit N1)`() =
        runTest {
            val gw = gateway(realClimate = false)
            drive(Float.NaN, CarIds.GEAR_PARK)
            stateAfter(gw, 400) shouldBe DrivingState.UNKNOWN
        }

    @Test
    @Verifies("SR-22", "SR-6")
    fun `an unreadable speed with a park gear is unknown, not parked`() =
        runTest {
            val gw = gateway(realClimate = false)
            drive(null, CarIds.GEAR_PARK)
            stateAfter(gw, 400) shouldBe DrivingState.UNKNOWN
        }

    @Test
    @Verifies("SR-22", "SR-6")
    fun `a speed value with an old vehicle timestamp counts as unreadable (re-audit N1)`() =
        runTest {
            val gw = gateway(realClimate = false)
            drive(0f, CarIds.GEAR_PARK)
            car.floatTimestamps[CarIds.PERF_VEHICLE_SPEED to 0] = 0L
            nowMs = 10_000
            stateAfter(gw, 400) shouldBe DrivingState.UNKNOWN
        }

    @Test
    @Verifies("SR-22")
    fun `without polling the cached reading ages out to unknown`() =
        runTest {
            val gw = gateway(realClimate = false)
            drive(0f, CarIds.GEAR_PARK)
            gw.poll()
            val resolver = DrivingStateResolver()
            resolver.update(gw.latestSignals(), nowMs)
            nowMs += 1_600
            resolver.update(gw.latestSignals(), nowMs)
            resolver.current(nowMs) shouldBe DrivingState.UNKNOWN
        }

    @Test
    @Verifies("SR-22", "SR-13")
    fun `a disconnected car service gives no signals and unavailable climate`() =
        runTest {
            val gw = gateway(realClimate = true)
            drive(0f, CarIds.GEAR_PARK)
            car.connected = false
            gw.poll()
            gw.isAvailable shouldBe false
            gw.latestSignals() shouldBe null
            gw.read(ClimateProperty.CABIN_TEMPERATURE_C) shouldBe ReadResult.Unavailable
            gw.write(ClimateProperty.CABIN_TEMPERATURE_C, 21) shouldBe WriteResult.Unavailable
        }

    @Test
    @Verifies("SR-22")
    fun `gear falls back to CURRENT_GEAR when GEAR_SELECTION is unreadable`() =
        runTest {
            val gw = gateway(realClimate = false)
            drive(0f, null)
            car.ints[CarIds.CURRENT_GEAR to 0] = CarIds.GEAR_REVERSE
            gw.poll()
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
    @Verifies("SR-3")
    fun `areas that disagree give no single-zone value, so a relative change cannot be computed (re-audit N8)`() =
        runTest {
            car.areas[CarIds.HVAC_TEMPERATURE_SET] = intArrayOf(1, 4)
            car.floats[CarIds.HVAC_TEMPERATURE_SET to 1] = 16f
            car.floats[CarIds.HVAC_TEMPERATURE_SET to 4] = 28f
            gateway(realClimate = true).read(ClimateProperty.CABIN_TEMPERATURE_C) shouldBe ReadResult.Unavailable
        }

    @Test
    fun `a NaN temperature from the car is unreadable, not a crash (re-audit N7)`() =
        runTest {
            car.areas[CarIds.HVAC_TEMPERATURE_SET] = intArrayOf(1)
            car.floats[CarIds.HVAC_TEMPERATURE_SET to 1] = Float.NaN
            gateway(realClimate = true).read(ClimateProperty.CABIN_TEMPERATURE_C) shouldBe ReadResult.Unavailable
        }

    @Test
    @Verifies("SR-7")
    fun `a write whose start deadline has passed never reaches the car (re-audit 8)`() =
        runTest {
            car.areas[CarIds.HVAC_TEMPERATURE_SET] = intArrayOf(1)
            val gw = gateway(realClimate = true)
            nowMs = 6_000
            gw.write(ClimateProperty.CABIN_TEMPERATURE_C, 22, notAfterMs = 5_000) shouldBe WriteResult.TimedOut
            car.writes.isEmpty() shouldBe true
        }

    @Test
    @Verifies("SR-14", "SR-17")
    fun `rejected, unsupported and out-of-range real writes are reported as rejected`() =
        runTest {
            gateway(realClimate = true).write(ClimateProperty.FAN_LEVEL, 3) shouldBe WriteResult.Rejected
            car.areas[CarIds.HVAC_FAN_SPEED] = intArrayOf(1)
            val gw = gateway(realClimate = true)
            gw.write(ClimateProperty.FAN_LEVEL, 9) shouldBe WriteResult.Rejected
            car.rejectWrites = true
            gw.write(ClimateProperty.FAN_LEVEL, 3) shouldBe WriteResult.Rejected
            gw.read(ClimateProperty.AC) shouldBe ReadResult.Unavailable
        }

    @Test
    @Verifies("SR-6", "SR-22")
    fun `zero speed with no readable gear is unknown, not parked after 2 s (pre-review F5)`() =
        runTest {
            val gw = gateway(realClimate = false)
            drive(0f, null)
            stateAfter(gw, 3_000) shouldBe DrivingState.UNKNOWN
        }

    @Test
    @Verifies("SR-14")
    fun `a caller's timeout stops the wait for a blocking car write (pre-review F7)`() {
        car.areas[CarIds.HVAC_TEMPERATURE_SET] = intArrayOf(1)
        car.writeBlockMs = 1_500
        val gw = CarPropertyGateway(car, clock, realClimate = true, simulatedClimate = SimulatedVehicleGateway(clock))
        val started = System.nanoTime()
        val result =
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(100) { gw.write(ClimateProperty.CABIN_TEMPERATURE_C, 22) }
            }
        val waitedMs = (System.nanoTime() - started) / 1_000_000
        result shouldBe null
        (waitedMs < 1_000) shouldBe true
    }

    @Test
    @Verifies("SR-22", "SR-6")
    fun `a speed value stamped in the future counts as unreadable (re-audit 3, N1)`() =
        runTest {
            val gw = gateway(realClimate = false)
            drive(0f, CarIds.GEAR_PARK)
            car.floatTimestamps[CarIds.PERF_VEHICLE_SPEED to 0] = Long.MAX_VALUE / 2
            stateAfter(gw, 3_000) shouldBe DrivingState.UNKNOWN
        }

    @Test
    @Verifies("SR-22")
    fun `a sample is stamped with the time before the reads, so a slow gear read ages it (re-audit 3, N1)`() =
        runTest {
            val gw = gateway(realClimate = false)
            drive(0f, CarIds.GEAR_PARK)
            nowMs = 1_000
            car.nowNanos = nowMs * 1_000_000
            car.floatTimestamps[CarIds.PERF_VEHICLE_SPEED to 0] = car.nowNanos
            car.onReadInt = { nowMs += 10_000 }
            gw.poll()
            gw.latestSignals()?.atMs shouldBe 1_000L
        }

    @Test
    @Verifies("SR-7", "SR-8")
    fun `a write whose guard fails when the worker reaches it never touches the car (re-audit 3, N12)`() =
        runTest {
            car.areas[CarIds.HVAC_DEFROSTER] = intArrayOf(CarIds.WINDOW_FRONT)
            val gw = gateway(realClimate = true)
            gw.write(ClimateProperty.FRONT_DEFROST, 0, Long.MAX_VALUE) { false } shouldBe WriteResult.Aborted
            car.writes.isEmpty() shouldBe true
        }

    @Test
    @Verifies("SR-8", "SR-14")
    fun `the guard is checked before every area, and stopping midway is reported as partial (re-audit 5, N22)`() =
        runTest {
            car.areas[CarIds.HVAC_FAN_SPEED] = intArrayOf(1, 2, 4)
            val gw = gateway(realClimate = true)
            var calls = 0
            // Allowed for the first area only: the car starts moving after the first platform call.
            gw.write(ClimateProperty.FAN_LEVEL, 0, Long.MAX_VALUE) { calls++ == 0 } shouldBe WriteResult.Partial
            car.writes.size shouldBe 1
        }

    @Test
    @Verifies("SR-14")
    fun `some areas accepting and others refusing is partial, not a plain failure (re-audit 5, N8)`() =
        runTest {
            car.areas[CarIds.HVAC_FAN_SPEED] = intArrayOf(1, 2)
            car.rejectArea = 2
            gateway(realClimate = true).write(ClimateProperty.FAN_LEVEL, 3) shouldBe WriteResult.Partial
        }
}
