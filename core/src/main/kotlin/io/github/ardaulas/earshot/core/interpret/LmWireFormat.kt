package io.github.ardaulas.earshot.core.interpret

import io.github.ardaulas.earshot.core.command.Bounds
import io.github.ardaulas.earshot.core.command.Command
import io.github.ardaulas.earshot.core.command.Window

/**
 * The only thing the language model may emit: one flat JSON object with no whitespace, e.g.
 * `{"cmd":"adjust_temp","delta":2}`. The model makes two free choices, the command name and at most
 * one small value; the grammar forces everything else.
 *
 * Cancel, help, yes/no and "show climate" have no wire form at all, so the model cannot produce them
 * (SG-7). The grammar is a sampling aid, not the trust boundary: [parse] re-checks everything.
 */
object LmWireFormat {
    const val MAX_TOKENS = 32

    val SYSTEM_PROMPT =
        """
        You turn a driver's spoken request into one JSON command for the cabin climate system. Output only the JSON, no spaces, no other text.
        Commands: set_temp (celsius ${Bounds.TEMP_C.first}-${Bounds.TEMP_C.last}), adjust_temp (delta -${Bounds.TEMP_DELTA.last} to ${Bounds.TEMP_DELTA.last}, not 0), set_fan (level ${Bounds.FAN_LEVEL.first}-${Bounds.FAN_LEVEL.last}), set_defrost (window front or rear, on true or false), set_ac (on true or false), query_speed, query_gear, query_cabin.
        If the request is not about cabin temperature, fan, air conditioning, defrost, speed, or gear, output {"cmd":"out_of_domain"}.
        """.trimIndent()

    /** Byte-identical to what the grammar admits, so the examples never fight the grammar. */
    val EXAMPLES =
        listOf(
            Example("I'm freezing", """{"cmd":"adjust_temp","delta":2}"""),
            Example("I can't see out the windshield", """{"cmd":"set_defrost","window":"front","on":true}"""),
            Example("what's the weather like", """{"cmd":"out_of_domain"}"""),
        )

    val GRAMMAR: String =
        buildString {
            appendLine("""root ::= "{\"cmd\":\"" cmd "}"""")
            appendLine("""cmd ::= "set_temp\",\"celsius\":" celsius""")
            appendLine("""  | "adjust_temp\",\"delta\":" delta""")
            appendLine("""  | "set_fan\",\"level\":" level""")
            appendLine("""  | "set_defrost\",\"window\":\"" window "\",\"on\":" bool""")
            appendLine("""  | "set_ac\",\"on\":" bool""")
            appendLine("""  | "query_speed\"" | "query_gear\"" | "query_cabin\"" | "out_of_domain\""""")
            appendLine("celsius ::= " + alternatives(Bounds.TEMP_C.toList()))
            appendLine("delta ::= " + alternatives(Bounds.TEMP_DELTA.map { -it }.reversed() + Bounds.TEMP_DELTA.toList()))
            appendLine("level ::= " + alternatives(Bounds.FAN_LEVEL.toList()))
            appendLine("""window ::= "front" | "rear"""")
            appendLine("""bool ::= "true" | "false"""")
        }

    private fun alternatives(values: List<Int>) = values.joinToString(" | ") { "\"$it\"" }

    private val SHAPE =
        Regex(
            """^\{"cmd":"([a-z_]+)"(?:,"(celsius|delta|level)":(-?\d{1,2})|,"window":"(front|rear)","on":(true|false)|,"on":(true|false))?}$""",
        )

    /**
     * Strictly parses model output. Returns null for anything that is not exactly one valid wire
     * object with in-bounds values; the caller treats null as "not understood" (no action).
     */
    fun parse(output: String): Command? {
        val m = SHAPE.matchEntire(output.trim()) ?: return null
        val (cmd, key, number, window, defrostOn, acOn) = m.destructured
        val n = number.toIntOrNull()
        return when {
            cmd == "set_temp" && key == "celsius" && n != null && n in Bounds.TEMP_C -> {
                Command.SetTemp(n)
            }

            cmd == "adjust_temp" && key == "delta" && n != null && n != 0 && kotlin.math.abs(n) in Bounds.TEMP_DELTA -> {
                Command.AdjustTemp(n)
            }

            cmd == "set_fan" && key == "level" && n != null && n in Bounds.FAN_LEVEL -> {
                Command.SetFan(n)
            }

            cmd == "set_defrost" && window.isNotEmpty() -> {
                Command.SetDefrost(if (window == "front") Window.FRONT else Window.REAR, defrostOn == "true")
            }

            cmd == "set_ac" && acOn.isNotEmpty() -> {
                Command.SetAc(acOn == "true")
            }

            cmd == "query_speed" && key.isEmpty() && window.isEmpty() && acOn.isEmpty() -> {
                Command.QuerySpeed
            }

            cmd == "query_gear" && key.isEmpty() && window.isEmpty() && acOn.isEmpty() -> {
                Command.QueryGear
            }

            cmd == "query_cabin" && key.isEmpty() && window.isEmpty() && acOn.isEmpty() -> {
                Command.QueryCabin
            }

            cmd == "out_of_domain" && key.isEmpty() && window.isEmpty() && acOn.isEmpty() -> {
                Command.OutOfDomain
            }

            else -> {
                null
            }
        }
    }
}
