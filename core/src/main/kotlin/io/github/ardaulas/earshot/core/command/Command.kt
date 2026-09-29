package io.github.ardaulas.earshot.core.command

/** Which window a defrost command targets. */
enum class Window { FRONT, REAR }

/**
 * Everything the assistant can be asked to do. Sealed, so the policy's `when` over it is exhaustive:
 * adding a command without a policy decision does not compile.
 *
 * Values are validated at construction against [Bounds]; an out-of-range value cannot exist as a
 * [Command], so no later stage has to remember to check (SG-2).
 */
sealed interface Command {
    data class SetTemp(
        val celsius: Int,
    ) : Command {
        init {
            require(celsius in Bounds.TEMP_C) { "temperature $celsius outside ${Bounds.TEMP_C}" }
        }
    }

    data class AdjustTemp(
        val delta: Int,
    ) : Command {
        init {
            require(delta != 0 && kotlin.math.abs(delta) in Bounds.TEMP_DELTA) { "delta $delta outside ±${Bounds.TEMP_DELTA}" }
        }
    }

    data class SetFan(
        val level: Int,
    ) : Command {
        init {
            require(level in Bounds.FAN_LEVEL) { "fan level $level outside ${Bounds.FAN_LEVEL}" }
        }
    }

    data class SetDefrost(
        val window: Window,
        val on: Boolean,
    ) : Command

    data class SetAc(
        val on: Boolean,
    ) : Command

    data object QuerySpeed : Command

    data object QueryGear : Command

    data object QueryCabin : Command

    /** Asks for the climate panel on screen. Screen-dependent, so refused while moving (SG-3). */
    data object ShowClimate : Command

    data object Cancel : Command

    data object Help : Command

    /** A spoken yes or no in reply to a confirmation question. */
    data class Answer(
        val yes: Boolean,
    ) : Command

    data object OutOfDomain : Command
}

/**
 * Value bounds for the simulated cabin. The real vehicle-property ranges are checked when the
 * assistant moves to the automotive emulator; until then these are the only ranges that exist.
 */
object Bounds {
    val TEMP_C = 16..28
    val TEMP_DELTA = 1..4
    val FAN_LEVEL = 0..5
}
