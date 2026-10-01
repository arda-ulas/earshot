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

    /**
     * The words touch the domain but must not become an action: negated, a question that is not a
     * supported query, an unsupported target (seats, windows, ...), or more than one action. Refused
     * without consulting the language model (audit #1).
     */
    data class Rejected(
        val reason: String,
    ) : RuleResult
}

/**
 * Hand-written rules over the normalized transcript. First matching rule wins, in the order below:
 * short control phrases, then commands with values, then queries. Never throws.
 */
class RuleInterpreter {
    fun interpret(transcript: String): RuleResult {
        val t = TextNormalizer.normalize(transcript)
        if (t.isEmpty()) return RuleResult.NoMatch
        control(t)?.let { return it }
        query(t)?.let { return it }
        // Everything below would be a vehicle action: require one complete, positive, supported request.
        val raw = transcript.trim()
        val domain = DOMAIN_WORD.containsMatchIn(t)
        if (domain && INVALID_NUMBER.containsMatchIn(t)) {
            return when {
                "fan" in t -> RuleResult.OutOfRange("fan speed", Bounds.FAN_LEVEL)
                STEP_WORDS.containsMatchIn(t) -> RuleResult.OutOfRange("temperature change", Bounds.TEMP_DELTA)
                else -> RuleResult.OutOfRange("temperature", Bounds.TEMP_C)
            }
        }
        val actions = listOfNotNull(temperature(t), fan(t), defrost(t), ac(t))
        if (actions.isEmpty()) {
            return when {
                // A feature Earshot cannot control is refused before the language model is asked
                // ("open the sunroof"). Windows stay open to it: "I can't see out the back window" is a
                // defrost request.
                UNSUPPORTED_TARGET.findAll(t).any { it.value !in setOf("window", "windows") } -> RuleResult.Rejected("unsupported target")

                // Negation is refused here only with a climate word ("don't make it warmer"). Without
                // one ("I can't see out the windshield") the language model may still pick a command,
                // which always needs a spoken yes.
                !domain -> RuleResult.NoMatch

                unsupportedTarget(t) -> RuleResult.Rejected("unsupported target")

                NEGATION.containsMatchIn(t) -> RuleResult.Rejected("negated")

                raw.endsWith("?") || QUESTION.containsMatchIn(t) -> RuleResult.Rejected("question")

                CONJUNCTION.containsMatchIn(t) -> RuleResult.Rejected("more than one request")

                else -> RuleResult.NoMatch
            }
        }
        return when {
            NEGATION.containsMatchIn(t) -> {
                RuleResult.Rejected("negated")
            }

            raw.endsWith("?") || QUESTION.containsMatchIn(t) -> {
                RuleResult.Rejected("question")
            }

            unsupportedTarget(t) -> {
                RuleResult.Rejected("unsupported target")
            }

            UNSUPPORTED_UNIT.containsMatchIn(t) -> {
                RuleResult.Rejected("unsupported unit")
            }

            actions.size > 1 || CONJUNCTION.containsMatchIn(t) -> {
                RuleResult.Rejected("more than one action")
            }

            // More than one sentence, more than one command verb, or the same thing named twice is
            // more than one request, even for one feature ("set the fan to 3; turn the fan off")
            // (re-audit 4, #1).
            SENTENCE_BREAK.containsMatchIn(raw.replace(ABBREVIATED_AC, "ac")) -> {
                RuleResult.Rejected("more than one action")
            }

            COMMAND_VERB.findAll(t).count() > 1 -> {
                RuleResult.Rejected("more than one action")
            }

            REPEATABLE
                .findAll(t)
                .map { it.value }
                .groupingBy { it }
                .eachCount()
                .any { it.value > 1 } -> {
                RuleResult.Rejected("more than one action")
            }

            // Every word that sets a value must be the one the command used: a number next to off,
            // max or an on/off switch is a second value, never dropped ("fan to 3 off", "turn off the
            // ac at 5") (pre-review of re-audit 5, #1).
            NUMBER.containsMatchIn(t) && !usesNumber(actions.single()) -> {
                RuleResult.Rejected("unused value")
            }

            NUMBER.containsMatchIn(t) && (OFF.containsMatchIn(t) || FAN_MAX_WORD.containsMatchIn(t)) -> {
                RuleResult.Rejected("more than one value")
            }

            FAN_MAX_WORD.containsMatchIn(t) && (OFF.containsMatchIn(t) || ON.containsMatchIn(t)) -> {
                RuleResult.Rejected("conflicting")
            }

            // One target, one direction, one number: a second request must never vanish silently, and
            // a number must never be truncated to a valid one (pre-review F1, F2, F4).
            targets(t) > 1 -> {
                RuleResult.Rejected("more than one action")
            }

            ON.containsMatchIn(t) && OFF.containsMatchIn(t) -> {
                RuleResult.Rejected("conflicting")
            }

            // One zone: front and rear together are two requests; a zone word on anything but the
            // defrost asks for a zone Earshot does not have ("set the rear temperature") (re-audit 3, #1).
            FRONT.containsMatchIn(t) && REAR.containsMatchIn(t) -> {
                RuleResult.Rejected("more than one action")
            }

            (FRONT.containsMatchIn(t) || REAR.containsMatchIn(t)) && !DEFROST_TARGET.containsMatchIn(t) -> {
                RuleResult.Rejected("unsupported zone")
            }

            // A temperature unit belongs to a temperature only ("fan to 3 celsius").
            TEMP_UNIT.containsMatchIn(t) && !isTemperature(actions.single()) -> {
                RuleResult.Rejected("unit does not fit")
            }

            WARMER_WORD.containsMatchIn(t) && COOLER_WORD.containsMatchIn(t) -> {
                RuleResult.Rejected("conflicting")
            }

            NUMBER.findAll(t).count() > 1 -> {
                RuleResult.Rejected("more than one number")
            }

            // Deny by default: an action utterance may contain only command vocabulary, so "the pizza
            // should be warmer" cannot change the cabin (audit re-check #1).
            t.split(" ").any { it !in ACTION_VOCABULARY && !it.all(Char::isDigit) } -> {
                RuleResult.Rejected("unrecognised words")
            }

            INVALID_NUMBER.containsMatchIn(t) -> {
                invalidNumber(actions.single())
            }

            else -> {
                actions.single()
            }
        }
    }

    /** Commands whose value comes from a number in the utterance. */
    private fun usesNumber(r: RuleResult): Boolean =
        when (r) {
            is RuleResult.Matched -> {
                val c = r.command
                c is Command.SetTemp || c is Command.AdjustTemp || (c is Command.SetFan && c.level in 1..Bounds.FAN_LEVEL.last)
            }

            is RuleResult.OutOfRange -> {
                true
            }

            else -> {
                false
            }
        }

    private fun isTemperature(r: RuleResult): Boolean =
        when (r) {
            is RuleResult.Matched -> r.command is Command.SetTemp || r.command is Command.AdjustTemp
            is RuleResult.OutOfRange -> r.what.startsWith("temperature")
            else -> false
        }

    /** How many different things the utterance names: temperature, fan, defrost, AC. */
    private fun targets(t: String): Int = listOf(TEMP_TARGET, FAN_TARGET, DEFROST_TARGET, AC).count { it.containsMatchIn(t) }

    /** "window" is a supported target only for defrost ("defrost the rear window"). */
    private fun unsupportedTarget(t: String): Boolean {
        val words = UNSUPPORTED_TARGET.findAll(t).map { it.value }.toList()
        return words.any { it !in setOf("window", "windows") || "defrost" !in t }
    }

    /** A signed or fractional number inside an action: answer with the valid range, never a guess. */
    private fun invalidNumber(action: RuleResult): RuleResult =
        when (action) {
            is RuleResult.OutOfRange -> {
                action
            }

            is RuleResult.Matched -> {
                when (action.command) {
                    is Command.SetFan -> RuleResult.OutOfRange("fan speed", Bounds.FAN_LEVEL)
                    is Command.AdjustTemp -> RuleResult.OutOfRange("temperature change", Bounds.TEMP_DELTA)
                    else -> RuleResult.OutOfRange("temperature", Bounds.TEMP_C)
                }
            }

            else -> {
                action
            }
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
        // A relative word with a number is a change by that number ("3 degrees warmer", "raise it by
        // 2"), never a set-point and never a default step of 1 (pre-review F3). With "to"/"at" it is
        // ambiguous ("turn up the heat to 25") and refused.
        val number = NUMBER.find(t)
        val up = WARMER_WORD.containsMatchIn(t)
        val down = COOLER_WORD.containsMatchIn(t)
        if (number != null && (up || down)) {
            if (SET_TO.containsMatchIn(t)) return RuleResult.Rejected("unclear change")
            val direction = if (up) 1 else -1
            return bounded(number.value, Bounds.TEMP_DELTA, "temperature change") { Command.AdjustTemp(direction * it) }
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
        val QUESTION =
            Regex(
                """^(?:(?:please|so|ok|okay|hey|and|um|uh|well)\s+)*(is|are|was|were|what|whats|how|did|does|do|why|when|can|could|would|will|should|has|have)\b""",
            )
        val NEGATION = Regex("""\b(dont|do not|not|never|no|nothing|without|stop|isnt|arent|wont|cant|shouldnt|neither|nor)\b""")
        val UNSUPPORTED_TARGET =
            Regex(
                """\b(seat|seats|steering|wheel|mirror|mirrors|sunroof|roof|door|doors|lock|locks|light|lights|headlights|window|windows|massage|trunk|boot)\b""",
            )
        val DOMAIN_WORD =
            Regex("""\b(warmer|cooler|hotter|colder|heat|heated|heating|heater|hot|cold|cool|cooling|warm|temperature|fan|defrost|ac)\b""")
        val CONJUNCTION = Regex("""\b(and|but|then|also|plus)\b""")
        val STEP_WORDS = Regex("""\b(up|down|by|warmer|cooler|raise|lower|increase|decrease)\b""")
        val UNSUPPORTED_UNIT = Regex("""\b(fahrenheit|kelvin|percent|f)\b""")
        val NUMBER = Regex("""\b\d+\b""")
        val WARMER_WORD = Regex("""\b(warmer|hotter|warm|heat up|up|raise|increase)\b""")
        val COOLER_WORD = Regex("""\b(cooler|colder|cool|down|lower|decrease)\b""")
        val TEMP_TARGET = Regex("""\b(temperature|thermostat|heat|heating|heater|degrees?|warmer|hotter|cooler|colder|warm)\b""")
        val FAN_TARGET = Regex("""\bfan\b""")
        val FAN_MAX_WORD = Regex("""\b(max|maximum|full|highest)\b""")
        val SENTENCE_BREAK = Regex("""[;]|[.!?](?=\s*\S)""")
        val ABBREVIATED_AC = Regex("""(?i)\ba\.\s?c\.?""")
        val COMMAND_VERB = Regex("""\b(set|turn|switch|make|put|change|raise|lower|increase|decrease|keep|get)\b""")
        val REPEATABLE = Regex("""\b(fan|defrost|ac|temperature|heat|on|off|up|down|warmer|cooler)\b""")
        val FRONT = Regex("""\bfront\b|(?<!rear |back )\bwindshield\b""")
        val REAR = Regex("""\b(rear|back)\b""")
        val TEMP_UNIT = Regex("""\b(degrees?|celsius)\b""")
        val DEFROST_TARGET = Regex("""\bdefrost\b""")

        /** Every word an action utterance may contain; anything else rejects the action. */
        val ACTION_VOCABULARY =
            setOf(
                "please",
                "can",
                "could",
                "would",
                "will",
                "you",
                "i",
                "id",
                "want",
                "lets",
                "let",
                "us",
                "me",
                "my",
                "we",
                "the",
                "a",
                "an",
                "it",
                "this",
                "that",
                "to",
                "of",
                "at",
                "in",
                "on",
                "off",
                "up",
                "down",
                "by",
                "for",
                "now",
                "set",
                "make",
                "turn",
                "switch",
                "put",
                "get",
                "change",
                "go",
                "keep",
                "just",
                "bit",
                "little",
                "lot",
                "more",
                "much",
                "some",
                "slightly",
                "degree",
                "degrees",
                "celsius",
                "temperature",
                "thermostat",
                "heat",
                "heating",
                "heater",
                "warm",
                "warmer",
                "hotter",
                "cool",
                "cooler",
                "colder",
                "raise",
                "lower",
                "increase",
                "decrease",
                "fan",
                "speed",
                "level",
                "max",
                "maximum",
                "full",
                "highest",
                "ac",
                "air",
                "defrost",
                "front",
                "rear",
                "back",
                "windshield",
                "window",
                "windows",
                "car",
                "cabin",
                "inside",
                "in",
                "please",
            )
        val INVALID_NUMBER = Regex("""\b(minus|negative) \d+|\d+ point \d+|\bpoint \d+|\d+ slash \d+|\b(half|quarter)\b""")
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
