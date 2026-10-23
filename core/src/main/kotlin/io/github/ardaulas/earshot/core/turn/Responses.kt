package io.github.ardaulas.earshot.core.turn

import io.github.ardaulas.earshot.core.command.Bounds
import io.github.ardaulas.earshot.core.command.Command
import io.github.ardaulas.earshot.core.command.Window
import io.github.ardaulas.earshot.core.vehicle.Gear
import kotlin.math.roundToInt

/**
 * Every spoken string the assistant produces. They are built from commands and vehicle read-backs,
 * never from transcript or model text, so nothing the microphone hears is ever echoed back. Replies
 * used while moving stay within the word limit; a test checks every one.
 */
object Responses {
    const val REPROMPT = "Sorry, I didn't catch that. Please say it again."
    const val STOP_UNCLEAR = "Sorry, I still didn't catch that."
    const val CANCELLED = "Cancelled."
    const val OUT_OF_DOMAIN = "Sorry, I can't help with that."
    const val NOTHING_PENDING = "There's nothing to confirm."
    const val CONFIRMATION_EXPIRED = "That request timed out. Please ask again."
    const val DECLINED = "Okay, I won't."
    const val STALE = "That took too long, so I didn't do it."
    const val STATE_CHANGED = "Driving changed, so I didn't do that. Please ask again."
    const val CONTROLS_UNAVAILABLE = "Vehicle controls are unavailable."
    const val SPEED_UNAVAILABLE = "Speed isn't available right now."
    const val GEAR_UNAVAILABLE = "Gear isn't available right now."
    const val HELP_SHORT = "Ask me to change temperature, fan, AC, or defrost."
    const val HELP_FULL =
        "You can set the temperature, fan, AC, or defrost, ask how fast you're going or which gear you're in, " +
            "or ask to see your climate settings. Say never mind to cancel."
    const val SHOWING_CLIMATE = "Here are your climate settings."
    const val SCREEN_WHILE_MOVING = "I can't show that while driving."

    fun outOfRange(
        what: String,
        range: IntRange,
    ) = "I can only set the $what from ${range.first} to ${range.last}."

    fun writeFailed(command: Command) = "I couldn't change the ${subject(command)}."

    fun writePartial(command: Command) = "Only part of the ${subject(command)} changed. Please check it."

    fun writeUnconfirmed(command: Command) = "I couldn't confirm the change to the ${subject(command)}. Please check it."

    /** At most 12 words, so it is spoken while moving too (re-audit 6, N28). */
    fun temperatureOutsideRange(celsius: Int) =
        "It's $celsius degrees, outside ${Bounds.TEMP_C.first} to ${Bounds.TEMP_C.last}. Say a temperature."

    fun confirmQuestion(command: Command): String = "${describe(command)}? Say yes or no."

    fun temperatureNow(celsius: Int): String {
        val suffix =
            when (celsius) {
                Bounds.TEMP_C.last -> ", the maximum"
                Bounds.TEMP_C.first -> ", the minimum"
                else -> ""
            }
        return "Temperature is now $celsius degrees$suffix."
    }

    fun temperatureAtLimit(celsius: Int): String {
        val which = if (celsius >= Bounds.TEMP_C.last) "maximum" else "minimum"
        return "It's already at the $which, $celsius degrees."
    }

    fun fanNow(level: Int) = if (level == 0) "The fan is now off." else "The fan is now at level $level."

    fun defrostNow(
        window: Window,
        on: Boolean,
    ) = "${windowName(window).replaceFirstChar { it.uppercase() }} defrost is now ${onOff(on)}."

    fun acNow(on: Boolean) = "The AC is now ${onOff(on)}."

    fun speed(kmh: Double) = "You're going ${kmh.roundToInt()} kilometres per hour."

    fun gear(gear: Gear) = "You're in ${gear.name.lowercase()}."

    fun cabin(
        celsius: Int,
        fan: Int,
        acOn: Boolean,
    ) = "It's set to $celsius degrees, fan ${if (fan == 0) "off" else fan.toString()}, AC ${onOff(acOn)}."

    fun screenRefusedWithSummary(summary: String?) = if (summary == null) SCREEN_WHILE_MOVING else "$SCREEN_WHILE_MOVING $summary"

    /** "It's 21 degrees, fan 2." kept short so the refusal plus summary stays in the word limit. */
    fun shortSummary(
        celsius: Int,
        fan: Int,
    ) = "It's $celsius degrees, fan ${if (fan == 0) "off" else fan.toString()}."

    private fun describe(command: Command): String =
        when (command) {
            is Command.SetTemp -> {
                "Set the temperature to ${command.celsius} degrees"
            }

            is Command.AdjustTemp -> {
                if (command.delta > 0) {
                    "Raise the temperature by ${command.delta} ${degrees(command.delta)}"
                } else {
                    "Lower the temperature by ${-command.delta} ${degrees(command.delta)}"
                }
            }

            is Command.SetFan -> {
                if (command.level == 0) "Turn the fan off" else "Set the fan to level ${command.level}"
            }

            is Command.SetDefrost -> {
                "Turn ${onOff(command.on)} the ${windowName(command.window)} defrost"
            }

            is Command.SetAc -> {
                "Turn the AC ${onOff(command.on)}"
            }

            Command.QuerySpeed -> {
                "Tell you your speed"
            }

            Command.QueryGear -> {
                "Tell you your gear"
            }

            Command.QueryCabin -> {
                "Tell you the climate settings"
            }

            Command.ShowClimate -> {
                "Show the climate settings"
            }

            Command.Cancel -> {
                "Cancel"
            }

            Command.Help -> {
                "Explain what I can do"
            }

            is Command.Answer -> {
                if (command.yes) "Confirm" else "Decline"
            }

            Command.OutOfDomain -> {
                "Do nothing"
            }
        }

    private fun subject(command: Command): String =
        when (command) {
            is Command.SetTemp, is Command.AdjustTemp -> "temperature"
            is Command.SetFan -> "fan"
            is Command.SetDefrost -> "${windowName(command.window)} defrost"
            is Command.SetAc -> "AC"
            else -> "setting"
        }

    private fun degrees(n: Int) = if (kotlin.math.abs(n) == 1) "degree" else "degrees"

    private fun windowName(window: Window) = if (window == Window.FRONT) "front" else "rear"

    private fun onOff(on: Boolean) = if (on) "on" else "off"
}
