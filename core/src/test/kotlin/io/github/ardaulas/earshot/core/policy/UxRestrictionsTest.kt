package io.github.ardaulas.earshot.core.policy

import io.github.ardaulas.earshot.core.requirements.Verifies
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

@Verifies("SR-23")
class UxRestrictionsTest {
    @Test
    fun `restrictions make parked behave as moving`() {
        DrivingState.PARKED.withUxRestrictions(true) shouldBe DrivingState.MOVING
    }

    @Test
    fun `restrictions never relax a moving or unknown state`() {
        DrivingState.MOVING.withUxRestrictions(false) shouldBe DrivingState.MOVING
        DrivingState.UNKNOWN.withUxRestrictions(false) shouldBe DrivingState.UNKNOWN
        DrivingState.UNKNOWN.withUxRestrictions(true) shouldBe DrivingState.UNKNOWN
        DrivingState.MOVING.withUxRestrictions(true) shouldBe DrivingState.MOVING
    }

    @Test
    fun `no restrictions service (a phone) changes nothing`() {
        for (s in DrivingState.entries) s.withUxRestrictions(null) shouldBe s
        DrivingState.PARKED.withUxRestrictions(false) shouldBe DrivingState.PARKED
    }
}
