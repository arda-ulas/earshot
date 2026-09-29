package io.github.ardaulas.earshot.core.policy

import io.github.ardaulas.earshot.core.command.Command

/** Where a command came from. Anything from the language model is always confirmed (SG-7). */
enum class Source { RULES, LM }

/**
 * What the policy allows for one command in one context. Ordered from least to most restrictive, so
 * a verdict can be raised to a floor with [atLeast].
 */
sealed interface Verdict {
    val rank: Int

    /** Do it; the screen may show a result. */
    data object Allow : Verdict {
        override val rank = 0
    }

    /** Do it; reply by voice only, in at most [Policy.MAX_WORDS_WHILE_MOVING] words (SG-3). */
    data object AllowVoiceOnly : Verdict {
        override val rank = 1
    }

    /** Ask a spoken yes/no question first; act only on "yes" (SG-6, SG-7). */
    data object Confirm : Verdict {
        override val rank = 2
    }

    /** Ask once to repeat; never guess an action (SG-1). */
    data object Reprompt : Verdict {
        override val rank = 3
    }

    /** End the exchange without acting: cancelled, or already re-prompted once. */
    data object Stop : Verdict {
        override val rank = 4
    }

    data class Refuse(
        val reason: RefuseReason,
    ) : Verdict {
        override val rank = 5
    }

    /** The more restrictive of this verdict and [floor]. */
    fun atLeast(floor: Verdict): Verdict = if (rank >= floor.rank) this else floor
}

enum class RefuseReason {
    OUT_OF_DOMAIN,

    /** Screen-dependent request while moving or with the driving state unknown (SG-3). */
    SCREEN_WHILE_MOVING,

    /** The language model produced a command it has no wire form for; should never happen (SG-7). */
    NOT_PERMITTED_FROM_LM,

    /** A yes or no with no confirmation pending. */
    NOTHING_PENDING,
}

/** The rows of the policy table. Every command falls in exactly one. */
enum class Category {
    COMFORT,
    VISIBILITY_REDUCING,
    QUERY,
    SCREEN_DEPENDENT,

    /** Cancel, help and yes/no answers: no vehicle action of their own. */
    CONVERSATION,
    OUT_OF_DOMAIN,
}

/**
 * Exhaustive by construction: a new [Command] without a category does not compile.
 *
 * Turning the fan off while the front defrost is on stops the defrost from clearing the windshield,
 * so it counts as visibility-reducing. An unknown defrost state is assumed on (fail-safe).
 */
fun Command.category(frontDefrostOn: Boolean?): Category =
    when (this) {
        is Command.SetTemp, is Command.AdjustTemp, is Command.SetAc -> Category.COMFORT
        is Command.SetFan -> if (level == 0 && frontDefrostOn != false) Category.VISIBILITY_REDUCING else Category.COMFORT
        is Command.SetDefrost -> if (on) Category.COMFORT else Category.VISIBILITY_REDUCING
        Command.QuerySpeed, Command.QueryGear, Command.QueryCabin -> Category.QUERY
        Command.ShowClimate -> Category.SCREEN_DEPENDENT
        Command.Cancel, Command.Help, is Command.Answer -> Category.CONVERSATION
        Command.OutOfDomain -> Category.OUT_OF_DOMAIN
    }

/** Categories the language model can produce. Anything else from it is refused (SG-7). */
private val LM_PERMITTED =
    setOf(Category.COMFORT, Category.VISIBILITY_REDUCING, Category.QUERY, Category.OUT_OF_DOMAIN)

data class PolicyInput(
    val command: Command,
    val source: Source,
    val drivingState: DrivingState,
    /** Speech-to-text confidence in 0..1; null means unknown, which is treated as too low. */
    val confidence: Float?,
    /** How many times this exchange has already re-prompted. */
    val repromptsSoFar: Int,
    /** Whether a confirmation question is waiting for a yes or no (and has not expired). */
    val confirmationPending: Boolean = false,
    /** Front defrost state from the vehicle; null when it could not be read. */
    val frontDefrostOn: Boolean? = null,
)

/**
 * The single place that decides whether and how a command runs. A pure function: no clock, no I/O,
 * no state. The interpreter, the language model, the UI and the vehicle gateway cannot bypass it.
 *
 * Precedence, first match wins:
 * 1. From the language model but not a category it can produce: refuse (should never happen).
 * 2. Cancel: stop. Checked before confidence, because a misheard "cancel" only ever stops things.
 * 3. Confidence unknown or below the threshold: re-prompt once, then stop (SG-1).
 * 4. Out of domain: refuse.
 * 5. A yes or no with nothing pending: refuse.
 * 6. The table, with unknown driving state handled as moving (SG-3, SG-4, SG-6).
 * 7. From the language model: at least confirm (SG-7).
 */
class Policy(
    private val confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD,
) {
    init {
        require(confidenceThreshold in 0f..1f)
    }

    /** True when a speech-to-text confidence is known and at or above the threshold. */
    fun isConfident(confidence: Float?): Boolean = confidence != null && !confidence.isNaN() && confidence >= confidenceThreshold

    fun decide(input: PolicyInput): Verdict {
        val command = input.command
        val category = command.category(input.frontDefrostOn)
        if (input.source == Source.LM && category !in LM_PERMITTED) return Verdict.Refuse(RefuseReason.NOT_PERMITTED_FROM_LM)
        if (command == Command.Cancel) return Verdict.Stop

        if (!isConfident(input.confidence)) {
            return if (input.repromptsSoFar == 0) Verdict.Reprompt else Verdict.Stop
        }
        if (category == Category.OUT_OF_DOMAIN) return Verdict.Refuse(RefuseReason.OUT_OF_DOMAIN)
        if (command is Command.Answer && !input.confirmationPending) return Verdict.Refuse(RefuseReason.NOTHING_PENDING)

        val parked = input.drivingState.effective == DrivingState.PARKED
        val verdict =
            when (category) {
                Category.COMFORT, Category.QUERY, Category.CONVERSATION -> {
                    if (parked) Verdict.Allow else Verdict.AllowVoiceOnly
                }

                Category.VISIBILITY_REDUCING -> {
                    if (parked) Verdict.Allow else Verdict.Confirm
                }

                Category.SCREEN_DEPENDENT -> {
                    if (parked) Verdict.Allow else Verdict.Refuse(RefuseReason.SCREEN_WHILE_MOVING)
                }

                Category.OUT_OF_DOMAIN -> {
                    Verdict.Refuse(RefuseReason.OUT_OF_DOMAIN)
                }
            }
        return if (input.source == Source.LM) verdict.atLeast(Verdict.Confirm) else verdict
    }

    companion object {
        /** Not yet calibrated: the regression harness (next phase) is what calibrates it. */
        const val DEFAULT_CONFIDENCE_THRESHOLD = 0.5f
        const val MAX_WORDS_WHILE_MOVING = 12
    }
}
