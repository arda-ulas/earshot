package io.github.ardaulas.earshot.core.interpret

import io.github.ardaulas.earshot.core.command.Bounds
import io.github.ardaulas.earshot.core.command.Command
import io.github.ardaulas.earshot.core.command.Window

/** What the rule interpreter made of one transcript. */
sealed interface RuleResult {
    data class Matched(
        val command: Command,
    ) : RuleResult

    /** The request was understood but a value is outside its bounds; re-prompt, never clamp (SG-2). */
    data class OutOfRange(
        val what: String,
        val range: IntRange,
    ) : RuleResult

    /** No rule matched. The caller may try the language model, or treat it as out of domain. */
    data object NoMatch : RuleResult
}

/**
 * Hand-written rules over the normalized transcript. First matching rule wins, in the order below:
 * short control phrases, then commands with values, then queries. Never throws.
 */
class RuleInterpreter {
    fun interpret(transcript: String): RuleResult {
        val t = TextNormalizer.normalize(transcript)
        if (t.isEmpty()) return RuleResult.NoMatch
        return control(t)
            ?: temperature(t)
            ?: fan(t)
            ?: defrost(t)
            ?: ac(t)
            ?: query(t)
            ?: RuleResult.NoMatch
    }

    private fun control(t: String): RuleResult? {
        val cmd =
            when {
                t matches CANCEL -> Command.Cancel
                t matches YES -> Command.Answer(yes = true)
                t matches NO -> Command.Answer(yes = false)
                t matches HELP -> Command.Help
                t matches SHOW_CLIMATE -> Command.ShowClimate
                else -> null
            }
        return cmd?.let(RuleResult::Matched)
    }

    private fun temperature(t: String): RuleResult? {
        if ("fan" in t) return null
        STEP.find(t)?.let { m ->
            val direction = if (m.groupValues[1] == "up") 1 else -1
            return bounded(m.groupValues[2], Bounds.TEMP_DELTA, "temperature change") { Command.AdjustTemp(direction * it) }
        }
        val mentionsTemp = "temperature" in t || "degrees" in t || "heat" in t || "thermostat" in t
        if (mentionsTemp && !QUESTION.containsMatchIn(t)) {
            val target = SET_TO.find(t) ?: DEGREES.find(t)
            if (target != null) {
                return bounded(target.groupValues[1], Bounds.TEMP_C, "temperature") { Command.SetTemp(it) }
            }
        }
        return when {
            t.containsAny(WARMER) -> RuleResult.Matched(Command.AdjustTemp(+1))
            t.containsAny(COOLER) -> RuleResult.Matched(Command.AdjustTemp(-1))
            else -> null
        }
    }

    private fun fan(t: String): RuleResult? {
        if ("fan" !in t) return null
        FAN_LEVEL.find(t)?.let { m ->
            return bounded(m.groupValues[1], Bounds.FAN_LEVEL, "fan speed") { Command.SetFan(it) }
        }
        return when {
            FAN_OFF.containsMatchIn(t) -> RuleResult.Matched(Command.SetFan(0))
            FAN_MAX.containsMatchIn(t) -> RuleResult.Matched(Command.SetFan(Bounds.FAN_LEVEL.last))
            else -> null
        }
    }

    private fun defrost(t: String): RuleResult? {
        if ("defrost" !in t || QUESTION.containsMatchIn(t)) return null
        val window = if (t.containsAny(listOf("rear", "back"))) Window.REAR else Window.FRONT
        // "defrost the windshield" has no on/off word but is plainly a request to turn it on.
        val on = onOff(t) ?: if (t.startsWith("defrost")) true else return null
        return RuleResult.Matched(Command.SetDefrost(window, on))
    }

    private fun ac(t: String): RuleResult? {
        if (!AC.containsMatchIn(t) || QUESTION.containsMatchIn(t)) return null
        val on = onOff(t) ?: return null
        return RuleResult.Matched(Command.SetAc(on))
    }

    private fun query(t: String): RuleResult? {
        val cmd =
            when {
                SPEED.containsMatchIn(t) -> Command.QuerySpeed
                GEAR.containsMatchIn(t) -> Command.QueryGear
                CABIN.containsMatchIn(t) -> Command.QueryCabin
                else -> null
            }
        return cmd?.let(RuleResult::Matched)
    }

    /** "on" / "off" from phrasings like "turn on the ac", "ac off", "switch the defrost off". */
    private fun onOff(t: String): Boolean? {
        val on = ON.containsMatchIn(t)
        val off = OFF.containsMatchIn(t)
        return when {
            on && !off -> true
            off && !on -> false
            else -> null
        }
    }

    private inline fun bounded(
        digits: String,
        range: IntRange,
        what: String,
        make: (Int) -> Command,
    ): RuleResult {
        val value = digits.toIntOrNull()
        return if (value != null && value in range) {
            RuleResult.Matched(make(value))
        } else {
            RuleResult.OutOfRange(what, range)
        }
    }

    private fun String.containsAny(needles: List<String>) = needles.any { Regex("""\b$it\b""").containsMatchIn(this) }

    private companion object {
        val CANCEL = Regex("""^(nevermind|cancel|cancel that|forget it|forget about it|stop|scratch that)( please)?$""")
        val YES = Regex("""^(yes|yeah|yep|yes please|sure|confirm|do it|go ahead|ok|okay)( please)?$""")
        val NO = Regex("""^(no|nope|no thanks|dont|do not|no dont)$""")
        val HELP = Regex("""^(help|what can you do|what can i say|what can i ask)$""")
        val SHOW_CLIMATE = Regex("""^(show|open|display)( me)?( my| the)? climate( settings| controls| panel)?$""")

        val SET_TO = Regex("""\b(?:to|at)\s+(\d+)\b""")
        val DEGREES = Regex("""\b(\d+)\s+degrees\b""")
        val QUESTION = Regex("""^(is|are|was|what|whats|how|did|does|do|why|when)\b""")
        val STEP = Regex("""\b(up|down)\s+(?:by\s+)?(\d+)\s+degrees?\b""")
        val WARMER =
            listOf("warmer", "hotter", "turn up the heat", "raise the temperature", "increase the temperature", "warm it up")
        val COOLER =
            listOf("cooler", "colder", "turn down the heat", "lower the temperature", "decrease the temperature", "cool it down")

        val FAN_LEVEL = Regex("""\bfan(?:\s+speed)?\s+(?:to\s+|on\s+|at\s+)?(?:level\s+)?(\d+)\b""")
        val FAN_OFF = Regex("""\b(fan off|turn off the fan|switch off the fan|turn the fan off|switch the fan off)\b""")
        val FAN_MAX = Regex("""\bfan\b.*\b(max|maximum|full|highest)\b""")

        val AC = Regex("""\bac\b""")
        val ON = Regex("""\b(turn on|switch on|put on|on)\b""")
        val OFF = Regex("""\b(turn off|switch off|shut off|off)\b""")

        val SPEED = Regex("""\bhow fast\b|\b(what|whats)( is)?( my| the)?( current)? speed\b|\bcurrent speed\b""")
        val GEAR = Regex("""\b(what|which|whats)( is)?( the)? gear\b|\bwhat gear am i in\b""")
        val CABIN =
            Regex(
                """\b(what|whats)( is)?( the| my)?( cabin| current| inside)? temperature\b|\bhow (warm|hot|cold) is it\b|\bclimate status\b""",
            )
    }
}
