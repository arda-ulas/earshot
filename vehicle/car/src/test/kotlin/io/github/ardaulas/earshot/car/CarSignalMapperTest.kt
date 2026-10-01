package io.github.ardaulas.earshot.car

import io.github.ardaulas.earshot.core.requirements.Verifies
import io.github.ardaulas.earshot.core.vehicle.Gear
import io.github.ardaulas.earshot.core.vehicle.SignalSample
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

@Verifies("SR-22")
class CarSignalMapperTest {
    @Test
    fun `speed is converted from metres per second and made positive`() {
        CarSignalMapper.speedKmh(10f) shouldBe 36.0
        CarSignalMapper.speedKmh(-2.5f) shouldBe 9.0
        CarSignalMapper.speedKmh(0f) shouldBe 0.0
    }

    @Test
    fun `unreadable speed is missing, nonsense speed stays visibly invalid`() {
        CarSignalMapper.speedKmh(null) shouldBe null
        CarSignalMapper.speedKmh(Float.NaN)!!.isNaN() shouldBe true
        CarSignalMapper.speedKmh(Float.POSITIVE_INFINITY)!!.isNaN() shouldBe true
    }

    @Test
    fun `gears map to core gears, numbered forward gears count as drive`() {
        CarSignalMapper.gear(CarIds.GEAR_PARK) shouldBe Gear.PARK
        CarSignalMapper.gear(CarIds.GEAR_REVERSE) shouldBe Gear.REVERSE
        CarSignalMapper.gear(CarIds.GEAR_NEUTRAL) shouldBe Gear.NEUTRAL
        CarSignalMapper.gear(CarIds.GEAR_DRIVE) shouldBe Gear.DRIVE
        for (g in listOf(16, 32, 64, 128, 256, 512, 1024, 2048, 4096)) CarSignalMapper.gear(g) shouldBe Gear.DRIVE
    }

    @Test
    fun `unknown or invalid gear values are missing`() {
        for (g in listOf(null, CarIds.GEAR_UNKNOWN, 3, 12, 48, 8192, -1)) CarSignalMapper.gear(g) shouldBe null
    }

    @Test
    fun `a reading needs at least one readable value`() {
        CarSignalMapper.sample(null, null, 5) shouldBe null
        CarSignalMapper.sample(null, CarIds.GEAR_UNKNOWN, 5) shouldBe null
        CarSignalMapper.sample(5f, null, 7) shouldBe SignalSample(18.0, null, 7)
        CarSignalMapper.sample(null, CarIds.GEAR_PARK, 9) shouldBe SignalSample(null, Gear.PARK, 9)
    }
}
