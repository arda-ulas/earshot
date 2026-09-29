package io.github.ardaulas.earshot.core.policy

import io.github.ardaulas.earshot.core.command.Command
import io.github.ardaulas.earshot.core.command.Window
import io.github.ardaulas.earshot.core.requirements.Verifies
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** One of every [Command] subtype, so a sweep over this list touches every category. */
private val ALL_COMMANDS: List<Command> =
    listOf(
        Command.SetTemp(21),
        Command.SetTemp(16),
        Command.SetTemp(28),
        Command.AdjustTemp(1),
        Command.AdjustTemp(-2),
        Command.SetFan(0),
        Command.SetFan(3),
        Command.SetFan(5),
        Command.SetDefrost(Window.FRONT, on = true),
        Command.SetDefrost(Window.FRONT, on = false),
        Command.SetDefrost(Window.REAR, on = false),
        Command.SetAc(true),
        Command.SetAc(false),
        Command.QuerySpeed,
        Command.QueryGear,
        Command.QueryCabin,
        Command.ShowClimate,
        Command.Cancel,
        Command.Help,
        Command.Answer(true),
        Command.Answer(false),
        Command.OutOfDomain,
    )

private const val CONFIDENT = 0.9f
private val THRESHOLD = Policy.DEFAULT_CONFIDENCE_THRESHOLD
private val UNCONFIDENT_BAND = listOf(null, Float.NaN, 0f, THRESHOLD - 0.01f)

class PolicyTest {
    private val policy = Policy()

    @Test
    @Verifies("SR-1", "SR-4", "SR-8")
    fun `table over category and driving state when confident, ruled sourced`() {
        data class Case(
            val category: Category,
            val command: Command,
        )
        val cases =
            listOf(
                Case(Category.COMFORT, Command.SetTemp(21)),
                Case(Category.VISIBILITY_REDUCING, Command.SetDefrost(Window.FRONT, on = false)),
                Case(Category.QUERY, Command.QuerySpeed),
                Case(Category.SCREEN_DEPENDENT, Command.ShowClimate),
                Case(Category.CONVERSATION, Command.Help),
            )
        for (case in cases) {
            for (state in DrivingState.entries) {
                val parked = state.effective == DrivingState.PARKED
                val expected =
                    when (case.category) {
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
                val actual = policy.decide(PolicyInput(case.command, Source.RULES, state, CONFIDENT, repromptsSoFar = 0))
                withClue(case, state) { actual shouldBe expected }
            }
        }
    }

    @Test
    @Verifies("SR-10")
    fun `language model source raises comfort, visibility and query verdicts to at least confirm`() {
        val commands =
            listOf(
                Category.COMFORT to Command.SetTemp(21),
                Category.VISIBILITY_REDUCING to Command.SetDefrost(Window.FRONT, on = false),
                Category.QUERY to Command.QuerySpeed,
            )
        for ((category, command) in commands) {
            for (state in DrivingState.entries) {
                val actual = policy.decide(PolicyInput(command, Source.LM, state, CONFIDENT, repromptsSoFar = 0))
                withClue(category, state) { actual shouldBe Verdict.Confirm }
            }
        }
    }

    @Test
    @Verifies("SR-10")
    fun `language model source is refused for categories it must never produce, even with unclear audio`() {
        val commands = listOf(Command.ShowClimate, Command.Help, Command.Cancel, Command.Answer(true))
        for (command in commands) {
            for (state in DrivingState.entries) {
                for (confidence in UNCONFIDENT_BAND + CONFIDENT) {
                    val actual = policy.decide(PolicyInput(command, Source.LM, state, confidence, repromptsSoFar = 0))
                    withClue(command, state, confidence) {
                        actual shouldBe Verdict.Refuse(RefuseReason.NOT_PERMITTED_FROM_LM)
                    }
                }
            }
        }
    }

    @Test
    @Verifies("SR-2")
    fun `cancel always stops, even at low confidence`() {
        for (state in DrivingState.entries) {
            for (confidence in UNCONFIDENT_BAND + CONFIDENT) {
                for (reprompts in listOf(0, 1, 2)) {
                    val actual = policy.decide(PolicyInput(Command.Cancel, Source.RULES, state, confidence, reprompts))
                    withClue(state, confidence, reprompts) { actual shouldBe Verdict.Stop }
                }
            }
        }
    }

    @Test
    @Verifies("SR-2")
    fun `below threshold confidence re-prompts once then stops`() {
        for (confidence in UNCONFIDENT_BAND) {
            val reprompt =
                policy.decide(
                    PolicyInput(Command.SetTemp(21), Source.RULES, DrivingState.PARKED, confidence, repromptsSoFar = 0),
                )
            withClue(confidence) { reprompt shouldBe Verdict.Reprompt }
            val stop = policy.decide(PolicyInput(Command.SetTemp(21), Source.RULES, DrivingState.PARKED, confidence, repromptsSoFar = 1))
            withClue(confidence) { stop shouldBe Verdict.Stop }
        }
    }

    @Test
    @Verifies("SR-1")
    fun `confidence exactly at the threshold counts as confident`() {
        val actual = policy.decide(PolicyInput(Command.SetTemp(21), Source.RULES, DrivingState.PARKED, THRESHOLD, repromptsSoFar = 0))
        actual shouldBe Verdict.Allow
    }

    @Test
    @Verifies("SR-1")
    fun `out of domain is refused once confident, regardless of driving state`() {
        for (state in DrivingState.entries) {
            val actual = policy.decide(PolicyInput(Command.OutOfDomain, Source.RULES, state, CONFIDENT, repromptsSoFar = 0))
            withClue(state) { actual shouldBe Verdict.Refuse(RefuseReason.OUT_OF_DOMAIN) }
        }
    }

    @Test
    @Verifies("SR-2")
    fun `out of domain with unclear audio re-prompts instead of refusing`() {
        val actual = policy.decide(PolicyInput(Command.OutOfDomain, Source.RULES, DrivingState.PARKED, null, repromptsSoFar = 0))
        actual shouldBe Verdict.Reprompt
    }

    @Test
    @Verifies("SR-10")
    fun `out of domain from the language model is refused, not raised to confirm`() {
        val actual = policy.decide(PolicyInput(Command.OutOfDomain, Source.LM, DrivingState.PARKED, CONFIDENT, repromptsSoFar = 0))
        actual shouldBe Verdict.Refuse(RefuseReason.OUT_OF_DOMAIN)
    }

    @Test
    @Verifies("SR-18")
    fun `answering with nothing pending is refused`() {
        for (answer in listOf(Command.Answer(true), Command.Answer(false))) {
            for (state in DrivingState.entries) {
                val actual =
                    policy.decide(
                        PolicyInput(answer, Source.RULES, state, CONFIDENT, repromptsSoFar = 0, confirmationPending = false),
                    )
                withClue(answer, state) { actual shouldBe Verdict.Refuse(RefuseReason.NOTHING_PENDING) }
            }
        }
    }

    @Test
    @Verifies("SR-18")
    fun `answering with a pending confirmation falls through to the conversation category`() {
        for (state in DrivingState.entries) {
            val parked = state.effective == DrivingState.PARKED
            val expected = if (parked) Verdict.Allow else Verdict.AllowVoiceOnly
            val actual =
                policy.decide(
                    PolicyInput(Command.Answer(true), Source.RULES, state, CONFIDENT, repromptsSoFar = 0, confirmationPending = true),
                )
            withClue(state) { actual shouldBe expected }
        }
    }

    @Test
    @Verifies("SR-8")
    fun `setting the fan to zero is visibility reducing unless the front defrost is confirmed off`() {
        for (frontDefrostOn in listOf(null, true, false)) {
            for (state in DrivingState.entries) {
                val parked = state.effective == DrivingState.PARKED
                val visibilityReducing = frontDefrostOn != false
                val expected =
                    if (visibilityReducing) {
                        if (parked) Verdict.Allow else Verdict.Confirm
                    } else {
                        if (parked) Verdict.Allow else Verdict.AllowVoiceOnly
                    }
                val actual =
                    policy.decide(
                        PolicyInput(Command.SetFan(0), Source.RULES, state, CONFIDENT, repromptsSoFar = 0, frontDefrostOn = frontDefrostOn),
                    )
                withClue(frontDefrostOn, state) { actual shouldBe expected }
            }
        }
    }

    @Test
    @Verifies("SR-6")
    fun `unknown driving state behaves exactly like moving for every command`() {
        for (command in ALL_COMMANDS) {
            for (source in Source.entries) {
                for (confidence in listOf(CONFIDENT, null)) {
                    for (frontDefrostOn in listOf(null, true, false)) {
                        for (confirmationPending in listOf(false, true)) {
                            val input = { state: DrivingState ->
                                PolicyInput(
                                    command = command,
                                    source = source,
                                    drivingState = state,
                                    confidence = confidence,
                                    repromptsSoFar = 0,
                                    confirmationPending = confirmationPending,
                                    frontDefrostOn = frontDefrostOn,
                                )
                            }
                            val unknown = policy.decide(input(DrivingState.UNKNOWN))
                            val moving = policy.decide(input(DrivingState.MOVING))
                            withClue(command, source, confidence, frontDefrostOn, confirmationPending) {
                                unknown shouldBe moving
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `atLeast is monotone, picking the more restrictive rank`() {
        val verdicts =
            listOf(
                Verdict.Allow,
                Verdict.AllowVoiceOnly,
                Verdict.Confirm,
                Verdict.Reprompt,
                Verdict.Stop,
                Verdict.Refuse(RefuseReason.OUT_OF_DOMAIN),
                Verdict.Refuse(RefuseReason.SCREEN_WHILE_MOVING),
            )
        for (a in verdicts) {
            for (b in verdicts) {
                val result = a.atLeast(b)
                withClue(a, b) { result.rank shouldBe maxOf(a.rank, b.rank) }
                // The more restrictive of the two wins; ties keep `this`.
                val expected = if (a.rank >= b.rank) a else b
                withClue(a, b) { result shouldBe expected }
            }
        }
    }
}

/** Runs [block], attaching the given values to any assertion failure for a readable message. */
private inline fun withClue(
    vararg values: Any?,
    block: () -> Unit,
) {
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("${values.toList()}: ${e.message}", e)
    }
}
