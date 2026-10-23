package io.github.ardaulas.earshot.harness

import io.github.ardaulas.earshot.core.command.Command
import io.github.ardaulas.earshot.core.policy.DrivingState
import io.github.ardaulas.earshot.core.policy.Source

/**
 * The verdict the policy must give for a command it was actually handed, written out here in the
 * harness from the policy table in `docs/safety.md` and the precedence documented on core's `Policy`.
 * It is a test oracle, deliberately not a call to `Policy`: it scores the decisions a label cannot
 * speak for, because the transcript was unconfident or the command was not the labelled one. A change
 * to core's policy that disagrees with this table fails the policy-correctness gate until the table
 * (and the labels) are updated on purpose.
 *
 * Precedence, first match wins, with no confirmation pending and `repromptsSoFar = 0` (each clip is a
 * fresh exchange):
 * 1. A confident out-of-range value is answered before the policy: `OutOfRange`.
 * 2. From the language model but not a category it may produce: `Refuse(NOT_PERMITTED_FROM_LM)`.
 * 3. Cancel: `Stop`, even when unconfident.
 * 4. Unconfident: `Reprompt` (SG-1). Never an action.
 * 5. Out of domain: `Refuse(OUT_OF_DOMAIN)`.
 * 6. A yes or no: `Refuse(NOTHING_PENDING)`.
 * 7. The table; unknown driving state as moving.
 * 8. From the language model: at least `Confirm` (SG-7).
 */
object PolicyTable {
    private enum class Row { COMFORT, VISIBILITY_REDUCING, QUERY, SCREEN_DEPENDENT, CONVERSATION, OUT_OF_DOMAIN }

    private val LM_MAY_PRODUCE = setOf(Row.COMFORT, Row.VISIBILITY_REDUCING, Row.QUERY, Row.OUT_OF_DOMAIN)

    private fun row(
        command: Command,
        frontDefrostOn: Boolean?,
    ): Row =
        when (command) {
            is Command.SetTemp, is Command.AdjustTemp, is Command.SetAc -> Row.COMFORT

            // Fan off can stop the windshield clearing. Core's turn engine does not read the defrost
            // (a reading can go stale before the write), so fan off always counts as visibility-reducing.
            is Command.SetFan -> if (command.level == 0) Row.VISIBILITY_REDUCING else Row.COMFORT

            is Command.SetDefrost -> if (command.on) Row.COMFORT else Row.VISIBILITY_REDUCING

            Command.QuerySpeed, Command.QueryGear, Command.QueryCabin -> Row.QUERY

            Command.ShowClimate -> Row.SCREEN_DEPENDENT

            Command.Cancel, Command.Help, is Command.Answer -> Row.CONVERSATION

            Command.OutOfDomain -> Row.OUT_OF_DOMAIN
        }

    /** The verdict name for [command] (null: the rules found a value out of range). */
    fun expected(
        command: Command?,
        source: Source,
        state: DrivingState,
        confident: Boolean,
        frontDefrostOn: Boolean?,
    ): String {
        if (command == null) return if (confident) Interpretation.OUT_OF_RANGE else "Reprompt"
        val row = row(command, frontDefrostOn)
        if (source == Source.LM && row !in LM_MAY_PRODUCE) return "Refuse(NOT_PERMITTED_FROM_LM)"
        if (command == Command.Cancel) return "Stop"
        if (!confident) return "Reprompt"
        if (row == Row.OUT_OF_DOMAIN) return Scoring.REFUSE_OUT_OF_DOMAIN
        if (command is Command.Answer) return "Refuse(NOTHING_PENDING)"
        val parked = state == DrivingState.PARKED
        val verdict =
            when (row) {
                Row.COMFORT, Row.QUERY, Row.CONVERSATION -> if (parked) "Allow" else "AllowVoiceOnly"
                Row.VISIBILITY_REDUCING -> if (parked) "Allow" else "Confirm"
                Row.SCREEN_DEPENDENT -> if (parked) "Allow" else "Refuse(SCREEN_WHILE_MOVING)"
                Row.OUT_OF_DOMAIN -> Scoring.REFUSE_OUT_OF_DOMAIN
            }
        return if (source == Source.LM && verdict in setOf("Allow", "AllowVoiceOnly")) "Confirm" else verdict
    }
}
