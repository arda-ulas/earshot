package io.github.ardaulas.earshot.core.interpret

import io.github.ardaulas.earshot.core.command.Bounds
import io.github.ardaulas.earshot.core.command.Command
import io.github.ardaulas.earshot.core.command.Window
import io.github.ardaulas.earshot.core.requirements.Verifies
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
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
    fun `parses every valid set_temp shape`() {
        for (celsius in Bounds.TEMP_C) {
            LmWireFormat.parse("""{"cmd":"set_temp","celsius":$celsius}""") shouldBe Command.SetTemp(celsius)
        }
    }

    @Test
    fun `parses every valid adjust_temp shape`() {
        val deltas = Bounds.TEMP_DELTA.map { -it } + Bounds.TEMP_DELTA.toList()
        for (delta in deltas) {
            LmWireFormat.parse("""{"cmd":"adjust_temp","delta":$delta}""") shouldBe Command.AdjustTemp(delta)
        }
    }

    @Test
    fun `parses every valid set_fan shape`() {
        for (level in Bounds.FAN_LEVEL) {
            LmWireFormat.parse("""{"cmd":"set_fan","level":$level}""") shouldBe Command.SetFan(level)
        }
    }

    @Test
    fun `parses every valid set_defrost shape`() {
        for (window in listOf("front" to Window.FRONT, "rear" to Window.REAR)) {
            for (on in listOf(true, false)) {
                LmWireFormat.parse("""{"cmd":"set_defrost","window":"${window.first}","on":$on}""") shouldBe
                    Command.SetDefrost(window.second, on)
            }
        }
    }

    @Test
    fun `parses every valid set_ac shape`() {
        for (on in listOf(true, false)) {
            LmWireFormat.parse("""{"cmd":"set_ac","on":$on}""") shouldBe Command.SetAc(on)
        }
    }

    @Test
    fun `parses the no-argument commands`() {
        LmWireFormat.parse("""{"cmd":"query_speed"}""") shouldBe Command.QuerySpeed
        LmWireFormat.parse("""{"cmd":"query_gear"}""") shouldBe Command.QueryGear
        LmWireFormat.parse("""{"cmd":"query_cabin"}""") shouldBe Command.QueryCabin
        LmWireFormat.parse("""{"cmd":"out_of_domain"}""") shouldBe Command.OutOfDomain
    }

    @Test
    fun `rejects whitespace inside the object`() {
        LmWireFormat.parse("""{"cmd": "set_temp","celsius":21}""") shouldBe null
        LmWireFormat.parse("""{"cmd":"set_temp", "celsius":21}""") shouldBe null
        LmWireFormat.parse("""{ "cmd":"query_speed"}""") shouldBe null
        LmWireFormat.parse("""{"cmd":"query_speed" }""") shouldBe null
    }

    @Test
    fun `tolerates surrounding whitespace, it is trimmed before matching`() {
        LmWireFormat.parse(" {\"cmd\":\"query_speed\"}\n") shouldBe Command.QuerySpeed
    }

    @Test
    fun `rejects extra keys`() {
        LmWireFormat.parse("""{"cmd":"set_temp","celsius":21,"extra":1}""") shouldBe null
        LmWireFormat.parse("""{"cmd":"query_speed","extra":1}""") shouldBe null
    }

    @Test
    fun `rejects out-of-range values`() {
        LmWireFormat.parse("""{"cmd":"set_temp","celsius":40}""") shouldBe null
        LmWireFormat.parse("""{"cmd":"adjust_temp","delta":0}""") shouldBe null
        LmWireFormat.parse("""{"cmd":"adjust_temp","delta":5}""") shouldBe null
        LmWireFormat.parse("""{"cmd":"set_fan","level":6}""") shouldBe null
    }

    @Test
    fun `rejects the wrong key for a command`() {
        LmWireFormat.parse("""{"cmd":"set_temp","level":21}""") shouldBe null
        LmWireFormat.parse("""{"cmd":"set_fan","celsius":3}""") shouldBe null
    }

    @Test
    fun `rejects set_defrost without a window`() {
        LmWireFormat.parse("""{"cmd":"set_defrost","on":true}""") shouldBe null
    }

    @Test
    fun `rejects an unknown command`() {
        LmWireFormat.parse("""{"cmd":"open_trunk"}""") shouldBe null
    }

    @Test
    @Verifies("SR-10")
    fun `rejects cancel, help, yes-no and screen commands - they have no wire form`() {
        LmWireFormat.parse("""{"cmd":"cancel"}""") shouldBe null
        LmWireFormat.parse("""{"cmd":"help"}""") shouldBe null
        LmWireFormat.parse("""{"cmd":"yes"}""") shouldBe null
        LmWireFormat.parse("""{"cmd":"no"}""") shouldBe null
        LmWireFormat.parse("""{"cmd":"show_climate"}""") shouldBe null
    }

    @Test
    fun `rejects trailing text, two objects and the empty string`() {
        LmWireFormat.parse("""{"cmd":"query_speed"}x""") shouldBe null
        LmWireFormat.parse("""{"cmd":"query_speed"}{"cmd":"query_gear"}""") shouldBe null
        LmWireFormat.parse("") shouldBe null
    }

    @Test
    fun `grammar contains every in-bounds celsius value and excludes its neighbours`() {
        val celsiusLine = LmWireFormat.GRAMMAR.lines().first { it.startsWith("celsius ::=") }
        for (c in Bounds.TEMP_C) {
            celsiusLine shouldContainQuoted c
        }
        celsiusLine shouldNotContainQuoted (Bounds.TEMP_C.first - 1)
        celsiusLine shouldNotContainQuoted (Bounds.TEMP_C.last + 1)
    }

    @Test
    fun `grammar deltas exclude zero`() {
        val deltaLine = LmWireFormat.GRAMMAR.lines().first { it.startsWith("delta ::=") }
        deltaLine shouldNotContainQuoted 0
        for (d in Bounds.TEMP_DELTA) {
            deltaLine shouldContainQuoted d
            deltaLine shouldContainQuoted (-d)
        }
    }

    private infix fun String.shouldContainQuoted(value: Int) {
        (this.contains("\"$value\"")) shouldBe true
    }

    private infix fun String.shouldNotContainQuoted(value: Int) {
        (this.contains("\"$value\"")) shouldBe false
    }

    @Test
    @Verifies("SR-9")
    fun `parse never throws and any result is within bounds`() =
        runTest {
            checkAll(Arb.string(0, 80)) { s ->
                val command = LmWireFormat.parse(s)
                when (command) {
                    is Command.SetTemp -> (command.celsius in Bounds.TEMP_C) shouldBe true
                    is Command.AdjustTemp -> (kotlin.math.abs(command.delta) in Bounds.TEMP_DELTA) shouldBe true
                    is Command.SetFan -> (command.level in Bounds.FAN_LEVEL) shouldBe true
                    else -> Unit
                }
            }
        }
}
