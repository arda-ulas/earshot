package io.github.ardaulas.earshot.core.interpret

import io.github.ardaulas.earshot.core.command.Command
import io.github.ardaulas.earshot.core.command.Window
import io.github.ardaulas.earshot.core.interpret.LmWireFormat.Intent
import io.github.ardaulas.earshot.core.policy.Category
import io.github.ardaulas.earshot.core.policy.category
import io.github.ardaulas.earshot.core.requirements.Verifies
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@Verifies("SR-9")
class LmWireFormatTest {
    @Test
    fun `every example assistant string parses`() {
        for (example in LmWireFormat.EXAMPLES) {
            LmWireFormat.parse(example.assistant) shouldNotBe null
        }
    }

    @Test
    fun `each label maps to exactly one fixed command`() {
        LmWireFormat.parse("""{"intent":"warmer"}""") shouldBe Command.AdjustTemp(+2)
        LmWireFormat.parse("""{"intent":"cooler"}""") shouldBe Command.AdjustTemp(-2)
        LmWireFormat.parse("""{"intent":"ac_on"}""") shouldBe Command.SetAc(true)
        LmWireFormat.parse("""{"intent":"ac_off"}""") shouldBe Command.SetAc(false)
        LmWireFormat.parse("""{"intent":"defrost_front_on"}""") shouldBe Command.SetDefrost(Window.FRONT, true)
        LmWireFormat.parse("""{"intent":"defrost_rear_on"}""") shouldBe Command.SetDefrost(Window.REAR, true)
        LmWireFormat.parse("""{"intent":"query_speed"}""") shouldBe Command.QuerySpeed
        LmWireFormat.parse("""{"intent":"query_gear"}""") shouldBe Command.QueryGear
        LmWireFormat.parse("""{"intent":"query_cabin"}""") shouldBe Command.QueryCabin
        LmWireFormat.parse("""{"intent":"out_of_domain"}""") shouldBe Command.OutOfDomain
        Intent.entries
            .map { it.label }
            .toSet()
            .size shouldBe Intent.entries.size
    }

    @Test
    @Verifies("SR-8", "SR-10")
    fun `no label can produce a conversation, screen or visibility-reducing command`() {
        for (intent in Intent.entries) {
            val category = intent.command.category(frontDefrostOn = true)
            (category in setOf(Category.COMFORT, Category.QUERY, Category.OUT_OF_DOMAIN)) shouldBe true
        }
    }

    @Test
    fun `rejects whitespace inside the object`() {
        LmWireFormat.parse("""{"intent": "warmer"}""") shouldBe null
        LmWireFormat.parse("""{ "intent":"warmer"}""") shouldBe null
        LmWireFormat.parse("""{"intent":"warmer" }""") shouldBe null
    }

    @Test
    fun `tolerates surrounding whitespace only`() {
        LmWireFormat.parse(" {\"intent\":\"warmer\"}\n") shouldBe Command.AdjustTemp(+2)
    }

    @Test
    @Verifies("SR-10")
    fun `rejects unknown labels, including ones for commands the model must not produce`() {
        for (label in listOf("cancel", "help", "yes", "no", "show_climate", "defrost_front_off", "fan_off", "set_temp", "open_trunk", "")) {
            LmWireFormat.parse("""{"intent":"$label"}""") shouldBe null
        }
    }

    @Test
    fun `rejects the old command shape, extra keys, trailing text and multiple objects`() {
        LmWireFormat.parse("""{"cmd":"adjust_temp","delta":2}""") shouldBe null
        LmWireFormat.parse("""{"intent":"warmer","delta":4}""") shouldBe null
        LmWireFormat.parse("""{"intent":"warmer"}x""") shouldBe null
        LmWireFormat.parse("""{"intent":"warmer"}{"intent":"cooler"}""") shouldBe null
        LmWireFormat.parse("""{"intent":"WARMER"}""") shouldBe null
        LmWireFormat.parse("") shouldBe null
    }

    @Test
    fun `grammar lists every label and nothing else`() {
        val intentLine = LmWireFormat.GRAMMAR.lines().first { it.startsWith("intent ::= ") }
        val labels = Regex("\"([a-z_]+)\"").findAll(intentLine).map { it.groupValues[1] }.toList()
        labels shouldBe Intent.entries.map { it.label }
    }

    @Test
    fun `system prompt names every label`() {
        for (intent in Intent.entries) {
            LmWireFormat.SYSTEM_PROMPT.contains(intent.label) shouldBe true
        }
    }

    @Test
    fun `grammar has one rule per line, since llama cpp ends a top-level rule at a newline`() {
        val lines = LmWireFormat.GRAMMAR.trim().lines()
        lines.map { it.substringBefore(" ::= ") } shouldBe listOf("root", "intent")
        lines.all { " ::= " in it } shouldBe true
    }

    @Test
    fun `parse never throws on arbitrary strings`() =
        runTest {
            checkAll(Arb.string(0, 80)) { s -> LmWireFormat.parse(s) }
        }

    @Test
    fun `parse accepts exactly the wire form of each label`() =
        runTest {
            checkAll(Arb.element(Intent.entries)) { intent ->
                LmWireFormat.parse(LmWireFormat.wire(intent)) shouldBe intent.command
            }
        }
}
