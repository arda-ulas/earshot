package io.github.ardaulas.earshot.core.interpret

import io.github.ardaulas.earshot.core.command.Bounds
import io.github.ardaulas.earshot.core.command.Command
import io.github.ardaulas.earshot.core.command.Window
import io.github.ardaulas.earshot.core.requirements.Verifies
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class RuleInterpreterTest {
    private val interpreter = RuleInterpreter()

    @Test
    @Verifies("U1")
    fun `set the temperature to 21`() {
        interpreter.interpret("Set the temperature to 21.") shouldBe RuleResult.Matched(Command.SetTemp(21))
    }

    @Test
    @Verifies("U1")
    fun `set the temperature to twenty one degrees`() {
        interpreter.interpret("Set the temperature to twenty one degrees") shouldBe RuleResult.Matched(Command.SetTemp(21))
    }

    @Test
    @Verifies("U2")
    fun `turn on the front defrost`() {
        interpreter.interpret("Turn on the front defrost.") shouldBe
            RuleResult.Matched(Command.SetDefrost(Window.FRONT, on = true))
    }

    @Test
    @Verifies("U3")
    fun `how fast am I going`() {
        interpreter.interpret("How fast am I going?") shouldBe RuleResult.Matched(Command.QuerySpeed)
    }

    @Test
    @Verifies("U4")
    fun `show me my climate settings`() {
        interpreter.interpret("Show me my climate settings.") shouldBe RuleResult.Matched(Command.ShowClimate)
    }

    @Test
    @Verifies("U5")
    fun `make it warmer`() {
        interpreter.interpret("Make it warmer.") shouldBe RuleResult.Matched(Command.AdjustTemp(1))
    }

    @Test
    @Verifies("U6")
    fun `order me a pizza is not understood`() {
        interpreter.interpret("Order me a pizza.") shouldBe RuleResult.NoMatch
    }

    @Test
    @Verifies("U8")
    fun `never mind cancels`() {
        interpreter.interpret("Never mind.") shouldBe RuleResult.Matched(Command.Cancel)
    }

    @Test
    @Verifies("U9")
    fun `turn off the defrost`() {
        interpreter.interpret("Turn off the defrost.") shouldBe
            RuleResult.Matched(Command.SetDefrost(Window.FRONT, on = false))
    }

    @Test
    fun `fan to 3`() {
        interpreter.interpret("fan to 3") shouldBe RuleResult.Matched(Command.SetFan(3))
    }

    @Test
    fun `fan off`() {
        interpreter.interpret("fan off") shouldBe RuleResult.Matched(Command.SetFan(0))
    }

    @Test
    fun `turn on the A C`() {
        interpreter.interpret("turn on the A/C") shouldBe RuleResult.Matched(Command.SetAc(true))
    }

    @Test
    fun `A C off`() {
        interpreter.interpret("AC off") shouldBe RuleResult.Matched(Command.SetAc(false))
    }

    @Test
    fun `down two degrees`() {
        interpreter.interpret("down two degrees") shouldBe RuleResult.Matched(Command.AdjustTemp(-2))
    }

    @Test
    fun `what gear am I in`() {
        interpreter.interpret("what gear am I in") shouldBe RuleResult.Matched(Command.QueryGear)
    }

    @Test
    fun `whats the temperature queries the cabin, it does not set it`() {
        interpreter.interpret("what's the temperature") shouldBe RuleResult.Matched(Command.QueryCabin)
    }

    @Test
    fun `yes and no are answers`() {
        interpreter.interpret("yes") shouldBe RuleResult.Matched(Command.Answer(yes = true))
        interpreter.interpret("no") shouldBe RuleResult.Matched(Command.Answer(yes = false))
    }

    @Test
    fun `help asks for the help command`() {
        interpreter.interpret("help") shouldBe RuleResult.Matched(Command.Help)
    }

    @Test
    @Verifies("SR-3")
    fun `set the temperature to 35 is out of range, never clamped`() {
        interpreter.interpret("set the temperature to 35") shouldBe RuleResult.OutOfRange("temperature", Bounds.TEMP_C)
    }

    @Test
    @Verifies("SR-3")
    fun `a temperature far larger than an Int is still reported out of range`() {
        interpreter.interpret("set temperature to 99999999999999") shouldBe RuleResult.OutOfRange("temperature", Bounds.TEMP_C)
    }

    @Test
    @Verifies("SR-3")
    fun `fan to 9 is out of range`() {
        interpreter.interpret("fan to 9") shouldBe RuleResult.OutOfRange("fan speed", Bounds.FAN_LEVEL)
    }

    @Test
    fun `a question about the AC never acts on it`() {
        val result = interpreter.interpret("is the AC on?")
        result shouldBe RuleResult.Rejected("question")
    }

    @Test
    fun `a question about the defrost never acts on it`() {
        interpreter.interpret("is the defrost on?") shouldBe RuleResult.Rejected("question")
    }

    // --- Property-based tests -------------------------------------------------------------

    private val vocabulary =
        listOf(
            "set",
            "the",
            "temperature",
            "to",
            "at",
            "degrees",
            "warmer",
            "cooler",
            "colder",
            "hotter",
            "fan",
            "speed",
            "level",
            "off",
            "on",
            "max",
            "maximum",
            "defrost",
            "front",
            "rear",
            "windshield",
            "ac",
            "air",
            "conditioning",
            "turn",
            "switch",
            "up",
            "down",
            "by",
            "how",
            "fast",
            "going",
            "what",
            "gear",
            "am",
            "i",
            "in",
            "show",
            "me",
            "my",
            "climate",
            "settings",
            "cancel",
            "nevermind",
            "yes",
            "no",
            "help",
            "please",
            "twenty",
            "one",
            "thirty",
            "forty",
            "a",
            "c",
            "temp",
        )

    private fun wordSoup(): Arb<String> =
        Arb
            .list(Arb.choice(Arb.of(vocabulary), Arb.int(-5..200).map(Int::toString)), 0..8)
            .map { it.joinToString(" ") }

    @Test
    fun `interpret never throws on arbitrary input`() =
        runTest {
            checkAll(Arb.string(0, 60)) { s ->
                interpreter.interpret(s)
            }
            checkAll(wordSoup()) { s ->
                interpreter.interpret(s)
            }
        }

    @Test
    @Verifies("SR-3")
    fun `any matched command is within bounds`() =
        runTest {
            checkAll(Arb.string(0, 60)) { s ->
                assertWithinBounds(interpreter.interpret(s))
            }
            checkAll(wordSoup()) { s ->
                assertWithinBounds(interpreter.interpret(s))
            }
        }

    private fun assertWithinBounds(result: RuleResult) {
        val command = (result as? RuleResult.Matched)?.command ?: return
        when (command) {
            is Command.SetTemp -> (command.celsius in Bounds.TEMP_C) shouldBe true
            is Command.AdjustTemp -> (kotlin.math.abs(command.delta) in Bounds.TEMP_DELTA) shouldBe true
            is Command.SetFan -> (command.level in Bounds.FAN_LEVEL) shouldBe true
            else -> Unit
        }
    }

    @Test
    @Verifies("SR-3")
    fun `set the temperature to n yields SetTemp iff n is in bounds`() =
        runTest {
            checkAll(Arb.int(0..1_000_000)) { n ->
                val result = interpreter.interpret("set the temperature to $n")
                if (n in Bounds.TEMP_C) {
                    result shouldBe RuleResult.Matched(Command.SetTemp(n))
                } else {
                    result shouldBe RuleResult.OutOfRange("temperature", Bounds.TEMP_C)
                }
            }
        }

    @Test
    @Verifies("SR-3")
    fun `signed and fractional temperatures are never turned into a valid different value`() =
        runTest {
            checkAll(Arb.int(-100, 100), Arb.int(0, 9)) { n, frac ->
                for (text in listOf("set the temperature to -$n", "set the temperature to $n.$frac", "set the temperature to minus $n")) {
                    val r = interpreter.interpret(text)
                    if (r is RuleResult.Matched) {
                        // Only a plain, in-range whole number may match.
                        (text.endsWith(" $n") && n in 16..28) shouldBe true
                    }
                }
            }
        }
}
