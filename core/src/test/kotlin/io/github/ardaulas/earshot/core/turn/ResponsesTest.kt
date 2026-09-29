package io.github.ardaulas.earshot.core.turn

import io.github.ardaulas.earshot.core.command.Bounds
import io.github.ardaulas.earshot.core.command.Command
import io.github.ardaulas.earshot.core.command.Window
import io.github.ardaulas.earshot.core.policy.Policy
import io.github.ardaulas.earshot.core.requirements.Verifies
import io.github.ardaulas.earshot.core.vehicle.Gear
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Every reply the engine can speak while moving must fit in [Policy.MAX_WORDS_WHILE_MOVING] words
 * (SR-5). [Responses.HELP_FULL] is exempt: it is only ever spoken while parked.
 */
@Verifies("SR-5")
class ResponsesTest {
    private fun words(text: String) =
        text
            .trim()
            .split(Regex("""\s+"""))
            .filter { it.isNotEmpty() }
            .size

    private fun assertWithinBudget(
        label: String,
        text: String,
    ) {
        val n = words(text)
        withClue(label, text, n) { (n <= Policy.MAX_WORDS_WHILE_MOVING) shouldBe true }
    }

    private inline fun withClue(
        vararg values: Any?,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: AssertionError) {
            throw AssertionError("${values.toList()}: ${e.message}", e)
        }
    }

    @Test
    fun `every voice-only constant fits the word budget`() {
        val constants =
            mapOf(
                "REPROMPT" to Responses.REPROMPT,
                "STOP_UNCLEAR" to Responses.STOP_UNCLEAR,
                "CANCELLED" to Responses.CANCELLED,
                "OUT_OF_DOMAIN" to Responses.OUT_OF_DOMAIN,
                "NOTHING_PENDING" to Responses.NOTHING_PENDING,
                "CONFIRMATION_EXPIRED" to Responses.CONFIRMATION_EXPIRED,
                "DECLINED" to Responses.DECLINED,
                "STALE" to Responses.STALE,
                "CONTROLS_UNAVAILABLE" to Responses.CONTROLS_UNAVAILABLE,
                "SPEED_UNAVAILABLE" to Responses.SPEED_UNAVAILABLE,
                "GEAR_UNAVAILABLE" to Responses.GEAR_UNAVAILABLE,
                "HELP_SHORT" to Responses.HELP_SHORT,
                "SHOWING_CLIMATE" to Responses.SHOWING_CLIMATE,
                "SCREEN_WHILE_MOVING" to Responses.SCREEN_WHILE_MOVING,
            )
        for ((name, text) in constants) {
            assertWithinBudget(name, text)
        }
    }

    @Test
    fun `HELP_FULL is exempt - it is parked-only and may exceed the budget`() {
        (words(Responses.HELP_FULL) > Policy.MAX_WORDS_WHILE_MOVING) shouldBe true
    }

    @Test
    fun `temperatureNow fits the budget for every in-bounds value`() {
        for (c in Bounds.TEMP_C) {
            assertWithinBudget("temperatureNow($c)", Responses.temperatureNow(c))
        }
    }

    @Test
    fun `temperatureAtLimit fits the budget at both bounds`() {
        assertWithinBudget("temperatureAtLimit(min)", Responses.temperatureAtLimit(Bounds.TEMP_C.first))
        assertWithinBudget("temperatureAtLimit(max)", Responses.temperatureAtLimit(Bounds.TEMP_C.last))
    }

    @Test
    fun `fanNow fits the budget for every in-bounds level`() {
        for (level in Bounds.FAN_LEVEL) {
            assertWithinBudget("fanNow($level)", Responses.fanNow(level))
        }
    }

    @Test
    fun `defrostNow fits the budget for every window and state`() {
        for (window in Window.entries) {
            for (on in listOf(true, false)) {
                assertWithinBudget("defrostNow($window,$on)", Responses.defrostNow(window, on))
            }
        }
    }

    @Test
    fun `acNow fits the budget`() {
        assertWithinBudget("acNow(true)", Responses.acNow(true))
        assertWithinBudget("acNow(false)", Responses.acNow(false))
    }

    @Test
    fun `speed fits the budget across the plausible range`() {
        for (kmh in 0..250 step 10) {
            assertWithinBudget("speed($kmh)", Responses.speed(kmh.toDouble()))
        }
    }

    @Test
    fun `gear fits the budget for every gear`() {
        for (gear in Gear.entries) {
            assertWithinBudget("gear($gear)", Responses.gear(gear))
        }
    }

    @Test
    fun `cabin fits the budget across temp, fan and ac combinations`() {
        for (c in Bounds.TEMP_C) {
            for (fan in Bounds.FAN_LEVEL) {
                for (acOn in listOf(true, false)) {
                    assertWithinBudget("cabin($c,$fan,$acOn)", Responses.cabin(c, fan, acOn))
                }
            }
        }
    }

    @Test
    fun `screenRefusedWithSummary with a short summary fits the budget for every temp and fan`() {
        for (c in Bounds.TEMP_C) {
            for (fan in Bounds.FAN_LEVEL) {
                val text = Responses.screenRefusedWithSummary(Responses.shortSummary(c, fan))
                assertWithinBudget("screenRefusedWithSummary($c,$fan)", text)
            }
        }
    }

    @Test
    fun `screenRefusedWithSummary with no summary fits the budget`() {
        assertWithinBudget("screenRefusedWithSummary(null)", Responses.screenRefusedWithSummary(null))
    }

    @Test
    fun `confirmQuestion fits the budget for every command the policy can confirm`() {
        val commands = mutableListOf<Command>()
        for (c in Bounds.TEMP_C) commands += Command.SetTemp(c)
        for (d in Bounds.TEMP_DELTA) {
            commands += Command.AdjustTemp(d)
            commands += Command.AdjustTemp(-d)
        }
        for (level in Bounds.FAN_LEVEL) commands += Command.SetFan(level)
        for (window in Window.entries) {
            for (on in listOf(true, false)) commands += Command.SetDefrost(window, on)
        }
        commands += Command.SetAc(true)
        commands += Command.SetAc(false)
        commands += Command.QuerySpeed
        commands += Command.QueryGear
        commands += Command.QueryCabin

        for (command in commands) {
            assertWithinBudget("confirmQuestion($command)", Responses.confirmQuestion(command))
        }
    }
}
