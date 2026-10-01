package io.github.ardaulas.earshot.core.interpret

import io.github.ardaulas.earshot.core.command.Bounds
import io.github.ardaulas.earshot.core.command.Command
import io.github.ardaulas.earshot.core.command.Window

/**
 * The complete sentences that may become a vehicle action: an allowlist, matched against the whole
 * normalized utterance. [RuleInterpreter] finds a command by keywords and refuses known bad shapes;
 * this second, independent reading must arrive at the same command, or nothing is done. An extra
 * word, a second value, a time, a condition or another person's zone leaves no pattern that fits,
 * so it cannot be dropped silently (re-audits 1 to 8 kept finding such words one at a time).
 *
 * Deliberately small. A phrasing that is not listed is refused, which costs recall, not safety.
 */
internal object CommandGrammar {
    private const val N = """(\d+)"""
    private const val DEG = """(?: degrees?)?(?: celsius)?"""
    private const val AMOUNT = """(?:(?:just )?a (?:little )?bit |a little |slightly )?"""
    private const val WARM = """(warmer|hotter|cooler|colder)"""
    private const val FRONT_W = """front|windshield|front windshield|front window"""
    private const val REAR_W = """rear|back|rear window|back window|rear windshield|back windshield"""
    private const val SWITCH = """(?:turn|switch)"""

    /** Polite or filler words around a request; they carry no value, time or condition. */
    private val PREFIX = Regex("""^(?:(?:please|hey|ok|okay) )*(?:lets |let us )?""")
    private val SUFFIX = Regex("""(?: (?:please|now|thanks|in the car|in the cabin))*$""")

    private class Rule(
        pattern: String,
        val build: (List<String>) -> RuleResult?,
    ) {
        val regex = Regex("^(?:$pattern)$")
    }

    private fun temp(digits: String) = bounded(digits, Bounds.TEMP_C, "temperature") { Command.SetTemp(it) }

    private fun step(
        direction: Int,
        digits: String,
    ) = bounded(digits, Bounds.TEMP_DELTA, "temperature change") { Command.AdjustTemp(direction * it) }

    private fun fan(digits: String) = bounded(digits, Bounds.FAN_LEVEL, "fan speed") { Command.SetFan(it) }

    private fun warmth(word: String) = if (word == "warmer" || word == "hotter") 1 else -1

    private fun upDown(word: String) = if (word in setOf("up", "raise", "increase")) 1 else -1

    private fun matched(command: Command): RuleResult = RuleResult.Matched(command)

    private fun defrost(
        window: Window,
        onOff: String,
    ) = matched(Command.SetDefrost(window, onOff == "on"))

    private val RULES =
        listOf(
            // Temperature, absolute.
            Rule("""(?:set|put|change|keep) (?:the )?(?:temperature|thermostat) (?:to|at) $N$DEG""") { temp(it[1]) },
            Rule("""(?:set|make|keep) it (?:to |at )?$N degrees?(?: celsius)?""") { temp(it[1]) },
            Rule("""(?:temperature|thermostat) (?:to |at )?$N$DEG""") { temp(it[1]) },
            // Temperature, one step.
            Rule("""(?:(?:make|get) it )?$AMOUNT$WARM""") { matched(Command.AdjustTemp(warmth(it[1]))) },
            Rule("""turn (up|down) the (?:heat|temperature)""") { matched(Command.AdjustTemp(upDown(it[1]))) },
            Rule("""turn the (?:heat|temperature) (up|down)""") { matched(Command.AdjustTemp(upDown(it[1]))) },
            Rule("""(raise|increase|lower|decrease) the temperature""") { matched(Command.AdjustTemp(upDown(it[1]))) },
            Rule("""warm it up""") { matched(Command.AdjustTemp(1)) },
            Rule("""cool it down""") { matched(Command.AdjustTemp(-1)) },
            // Temperature, by a number of degrees.
            Rule("""(?:(?:make|get) it )?$N degrees? $WARM""") { step(warmth(it[2]), it[1]) },
            Rule("""(?:make|get) it $WARM by $N(?: degrees?)?""") { step(warmth(it[1]), it[2]) },
            Rule("""(?:turn (?:it |the temperature |the heat )?)?(up|down) (?:by )?$N degrees?""") { step(upDown(it[1]), it[2]) },
            Rule("""turn (up|down) the (?:heat|temperature) by $N(?: degrees?)?""") { step(upDown(it[1]), it[2]) },
            Rule("""(raise|increase|lower|decrease) the temperature by $N(?: degrees?)?""") { step(upDown(it[1]), it[2]) },
            // Fan.
            Rule("""(?:(?:set|put|turn|change|switch) )?(?:the )?fan(?: speed)? (?:to |on |at )?(?:level )?$N""") { fan(it[1]) },
            Rule("""$SWITCH off the fan|$SWITCH the fan off|fan off""") { matched(Command.SetFan(0)) },
            Rule("""(?:(?:set|put|turn) )?(?:the )?fan (?:to |on |at )?(?:max|maximum|full|highest)""") {
                matched(Command.SetFan(Bounds.FAN_LEVEL.last))
            },
            // Defrost. Without a window it is the front one; "back on" after the defrost means on again.
            Rule("""$SWITCH (on|off) the defrost|$SWITCH the defrost (on|off)|defrost (on|off)""") {
                defrost(Window.FRONT, it.drop(1).first(String::isNotEmpty))
            },
            Rule("""$SWITCH the defrost back (on|off)""") { defrost(Window.FRONT, it[1]) },
            Rule("""$SWITCH (on|off) the (?:$FRONT_W) defrost|$SWITCH the (?:$FRONT_W) defrost (on|off)|(?:$FRONT_W) defrost (on|off)""") {
                defrost(Window.FRONT, it.drop(1).first(String::isNotEmpty))
            },
            Rule("""$SWITCH (on|off) the (?:$REAR_W) defrost|$SWITCH the (?:$REAR_W) defrost (on|off)|(?:$REAR_W) defrost (on|off)""") {
                defrost(Window.REAR, it.drop(1).first(String::isNotEmpty))
            },
            Rule("""$SWITCH the defrost (?:in|at|for) the front (on|off)|$SWITCH (on|off) the defrost (?:in|at|for) the front""") {
                defrost(Window.FRONT, it.drop(1).first(String::isNotEmpty))
            },
            Rule(
                """$SWITCH the defrost (?:in|at|for) the (?:rear|back) (on|off)|$SWITCH (on|off) the defrost (?:in|at|for) the (?:rear|back)""",
            ) {
                defrost(Window.REAR, it.drop(1).first(String::isNotEmpty))
            },
            Rule("""defrost the (?:windshield|front windshield|front window)""") { matched(Command.SetDefrost(Window.FRONT, true)) },
            Rule("""defrost the (?:rear window|back window|rear windshield|back windshield)""") {
                matched(Command.SetDefrost(Window.REAR, true))
            },
            // AC.
            Rule("""$SWITCH (on|off) the ac|$SWITCH the ac (on|off)|ac (on|off)|$SWITCH the ac back (on|off)""") {
                matched(Command.SetAc(it.drop(1).first(String::isNotEmpty) == "on"))
            },
        )

    /** The action this normalized utterance spells out in full, or null if no pattern fits it. */
    fun parse(normalized: String): RuleResult? {
        val core = normalized.replace(PREFIX, "").replace(SUFFIX, "")
        for (rule in RULES) {
            val m = rule.regex.matchEntire(core) ?: continue
            return rule.build(m.groupValues)
        }
        return null
    }

    private inline fun bounded(
        digits: String,
        range: IntRange,
        what: String,
        make: (Int) -> Command,
    ): RuleResult {
        val value = digits.toIntOrNull()
        return if (value != null && value in range && !(digits.length > 1 && digits.startsWith("0"))) {
            RuleResult.Matched(make(value))
        } else {
            RuleResult.OutOfRange(what, range)
        }
    }
}
