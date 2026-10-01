package io.github.ardaulas.earshot.core.interpret

import io.github.ardaulas.earshot.core.command.Bounds
import io.github.ardaulas.earshot.core.command.Command
import io.github.ardaulas.earshot.core.command.Window
import io.github.ardaulas.earshot.core.requirements.Verifies
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class CommandGrammarTest {
    private fun parse(text: String) = CommandGrammar.parse(TextNormalizer.normalize(text))

    @Test
    fun `the listed sentences give their command`() {
        parse("Set the temperature to 21") shouldBe RuleResult.Matched(Command.SetTemp(21))
        parse("Make it 24 degrees") shouldBe RuleResult.Matched(Command.SetTemp(24))
        parse("Turn the temperature down 3 degrees") shouldBe RuleResult.Matched(Command.AdjustTemp(-3))
        parse("make it a little bit warmer please") shouldBe RuleResult.Matched(Command.AdjustTemp(1))
        parse("Put the fan on level 2") shouldBe RuleResult.Matched(Command.SetFan(2))
        parse("Turn the fan to maximum") shouldBe RuleResult.Matched(Command.SetFan(Bounds.FAN_LEVEL.last))
        parse("Switch the air conditioning off") shouldBe RuleResult.Matched(Command.SetAc(false))
        parse("Turn on the rear defrost") shouldBe RuleResult.Matched(Command.SetDefrost(Window.REAR, true))
        parse("turn the defrost back on") shouldBe RuleResult.Matched(Command.SetDefrost(Window.FRONT, true))
        parse("Defrost the windshield") shouldBe RuleResult.Matched(Command.SetDefrost(Window.FRONT, true))
        parse("set the temperature to 35") shouldBe RuleResult.OutOfRange("temperature", Bounds.TEMP_C)
    }

    @Test
    fun `anything left over means no pattern fits`() {
        for (text in listOf(
            "turn on the ac in just a bit",
            "turn on the ac and the fan",
            "turn on the ac for the passenger",
            "turn down the ac by 2",
            "set the temperature to 21 22",
            "the ac",
            "",
        )) {
            parse(text) shouldBe null
        }
    }

    @Test
    @Verifies("SR-1")
    fun `adding any other word to a listed sentence stops it acting`() =
        runTest {
            val sentences = listOf("turn on the ac", "set the temperature to 21", "turn off the defrost", "fan off", "make it warmer")
            val extras =
                listOf("later", "maybe", "not", "twice", "then", "passenger", "seat", "if", "half", "off", "up", "5", "tomorrow", "quickly")
            checkAll(Arb.element(sentences), Arb.list(Arb.element(extras), 1..2).map { it.joinToString(" ") }) { sentence, extra ->
                (RuleInterpreter().interpret("$sentence $extra") is RuleResult.Matched) shouldBe false
                (RuleInterpreter().interpret("$extra $sentence") is RuleResult.Matched) shouldBe false
            }
        }
}
