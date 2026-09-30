package io.github.ardaulas.earshot.harness

import io.github.ardaulas.earshot.core.command.Command
import io.github.ardaulas.earshot.core.interpret.Example
import io.github.ardaulas.earshot.core.interpret.LmEngine
import io.github.ardaulas.earshot.core.interpret.LmInterpreter
import io.github.ardaulas.earshot.core.interpret.LmOutcome
import io.github.ardaulas.earshot.core.interpret.RuleInterpreter
import io.github.ardaulas.earshot.core.interpret.RuleResult
import io.github.ardaulas.earshot.core.policy.DrivingState
import io.github.ardaulas.earshot.core.policy.Policy
import io.github.ardaulas.earshot.core.policy.PolicyInput
import io.github.ardaulas.earshot.core.policy.Source
import io.github.ardaulas.earshot.core.policy.Verdict
import io.github.ardaulas.earshot.core.speech.SpeechEngine
import io.github.ardaulas.earshot.core.speech.Transcript
import io.github.ardaulas.earshot.core.time.MonotonicClock
import io.github.ardaulas.earshot.core.trace.InputSource
import io.github.ardaulas.earshot.core.trace.TraceSink
import io.github.ardaulas.earshot.core.turn.Outcome
import io.github.ardaulas.earshot.core.turn.TurnEngine
import io.github.ardaulas.earshot.core.vehicle.ClimateProperty
import io.github.ardaulas.earshot.core.vehicle.ReadResult
import io.github.ardaulas.earshot.core.vehicle.SignalSample
import io.github.ardaulas.earshot.core.vehicle.SimulatedVehicleGateway
import io.github.ardaulas.earshot.core.vehicle.VehicleGateway
import io.github.ardaulas.earshot.core.vehicle.WriteResult
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import io.github.ardaulas.earshot.core.trace.HostInfo as TraceHost

/** What the interpretation stages made of one transcript. */
data class Interpretation(
    val rule: RuleResult,
    /** The command handed to the policy; null when the rules rejected a value and the policy never ran. */
    val command: Command?,
    val source: Source,
    val lmOutcome: LmOutcome?,
    val rulesMs: Double,
    /** Language-model time: what lmhost reported, else wall time around the call. Null if it did not run. */
    val lmMs: Double?,
) {
    /** The command's string form, or `OutOfRange`. */
    val predicted: String get() = command?.toString() ?: OUT_OF_RANGE

    /** What the rules said, for the report: `Matched`, `NoMatch`, `OutOfRange(...)`, `Rejected(negated)`. */
    val ruleOutcome: String
        get() =
            when (rule) {
                is RuleResult.Matched -> "Matched"
                RuleResult.NoMatch -> "NoMatch"
                is RuleResult.OutOfRange -> "OutOfRange(${rule.what})"
                is RuleResult.Rejected -> "Rejected(${rule.reason})"
            }

    companion object {
        const val OUT_OF_RANGE = "OutOfRange"
    }
}

data class Decision(
    val verdict: String,
    val policyMs: Double,
)

/** What core's [TurnEngine] did with the same transcript in one driving state. */
data class TurnObservation(
    /** The verdict name, `OutOfRange` when it answered with the valid range, or `none(<outcome>)`. */
    val verdict: String,
    val outcome: String,
    /** The command in its trace, or `OutOfRange` when the rules stopped at a value. */
    val command: String?,
    val source: String?,
    /** Calls that reached [VehicleGateway.write]. */
    val writes: Int,
)

/**
 * The app's decision path without audio capture, in two forms that must agree on every clip:
 *
 * - [interpret] and [decide] run core's [RuleInterpreter], [LmInterpreter] and [Policy] one stage at a
 *   time, so the harness can score each stage and time it. The routing between them is copied from
 *   [TurnEngine] (rules always run; a rule rejection goes to the policy as out of domain and never
 *   reaches the language model; the model only runs on a confident rule miss; a confident out-of-range
 *   value is answered before the policy, an unconfident one goes to it as out of domain).
 * - [turn] runs the same transcript through core's [TurnEngine] itself, with a simulated vehicle whose
 *   writes are counted and the language model's answer from [interpret] replayed (so it is not run
 *   again). The runner compares the two on command, source, verdict and vehicle writes; any
 *   difference is a gated mismatch, so the copied routing cannot drift from core unnoticed.
 */
class Pipeline(
    private val policy: Policy,
    private val rules: RuleInterpreter = RuleInterpreter(),
    private val lm: LmInterpreter? = null,
    private val lmHostMs: () -> Double? = { null },
    /** Front defrost state handed to the policy and read from the simulated vehicle; null is unknown. */
    val frontDefrostOn: Boolean? = null,
    /** The policy's decision for [decide]: core's [Policy.decide], replaced only by the harness's own gate tests. */
    private val decideFn: (PolicyInput) -> Verdict = policy::decide,
) {
    val lmEnabled: Boolean get() = lm != null

    fun isConfident(confidence: Float?): Boolean = policy.isConfident(confidence)

    fun interpret(
        text: String,
        confidence: Float?,
    ): Interpretation {
        val confident = policy.isConfident(confidence)
        val t0 = System.nanoTime()
        val rule = rules.interpret(text)
        val rulesMs = ms(System.nanoTime() - t0)
        return when (rule) {
            is RuleResult.Matched -> {
                Interpretation(rule, rule.command, Source.RULES, null, rulesMs, null)
            }

            is RuleResult.Rejected -> {
                Interpretation(rule, Command.OutOfDomain, Source.RULES, null, rulesMs, null)
            }

            is RuleResult.OutOfRange -> {
                Interpretation(rule, if (confident) null else Command.OutOfDomain, Source.RULES, null, rulesMs, null)
            }

            RuleResult.NoMatch -> {
                val interpreter = lm
                if (confident && interpreter != null) {
                    val l0 = System.nanoTime()
                    val outcome = runBlocking { interpreter.interpret(text) }
                    val wall = ms(System.nanoTime() - l0)
                    val command = (outcome as? LmOutcome.Parsed)?.command ?: Command.OutOfDomain
                    Interpretation(rule, command, Source.LM, outcome, rulesMs, lmHostMs() ?: wall)
                } else {
                    Interpretation(rule, Command.OutOfDomain, Source.RULES, null, rulesMs, null)
                }
            }
        }
    }

    fun decide(
        interpretation: Interpretation,
        confidence: Float?,
        state: DrivingState,
    ): Decision {
        val command = interpretation.command ?: return Decision(Interpretation.OUT_OF_RANGE, 0.0)
        val t0 = System.nanoTime()
        val verdict =
            decideFn(
                PolicyInput(
                    command = command,
                    source = interpretation.source,
                    drivingState = state,
                    confidence = confidence,
                    repromptsSoFar = 0,
                    confirmationPending = false,
                    frontDefrostOn = frontDefrostOn,
                ),
            )
        return Decision(verdictName(verdict), ms(System.nanoTime() - t0))
    }

    /**
     * Runs [text] at [confidence] through core's [TurnEngine] in [state]: a fresh engine per call (no
     * pending confirmation, no re-prompt streak), a frozen clock (so the action deadline never
     * passes), and a simulated vehicle at its default climate whose front defrost reads as
     * [frontDefrostOn] (unreadable when null). The language model, if any, replays what [interpret]
     * got for this text; if the engine asks it when [interpret] did not, it fails, and the mismatch
     * shows up as a different command source.
     */
    fun turn(
        interpretation: Interpretation,
        text: String,
        confidence: Float?,
        state: DrivingState,
    ): TurnObservation {
        val vehicle = CountingVehicle(frontDefrostOn)
        val replay =
            lm?.let {
                val timeout = if (interpretation.lmOutcome == LmOutcome.TimedOut) 1L else LmInterpreter.DEFAULT_TIMEOUT_MS
                LmInterpreter(ReplayLm(interpretation.lmOutcome), timeout)
            }
        val engine =
            TurnEngine(
                speech = FixedTranscript(Transcript(text, confidence)),
                rules = rules,
                lm = replay,
                policy = policy,
                vehicle = vehicle,
                drivingState = { state },
                clock = FROZEN,
                host = TURN_HOST,
                traceSink = TraceSink { },
                newTurnId = { "harness" },
            )
        val result = runBlocking { engine.handle(FloatArray(0), InputSource.CLIP, 0L, 0L) }
        val outOfRange = result.outcome == Outcome.OUT_OF_RANGE
        val verdict =
            result.verdict?.let(::verdictName) ?: if (outOfRange) Interpretation.OUT_OF_RANGE else "none(${result.outcome})"
        return TurnObservation(
            verdict = verdict,
            outcome = result.outcome.name,
            command = result.trace.command ?: if (outOfRange) Interpretation.OUT_OF_RANGE else null,
            source = result.trace.commandSource,
            writes = vehicle.writes,
        )
    }

    private class FixedTranscript(
        private val transcript: Transcript,
    ) : SpeechEngine {
        override suspend fun transcribe(pcm16k: FloatArray) = transcript
    }

    /** Answers with what the model said in [interpret]: the same raw text, the same failure, or a timeout. */
    private class ReplayLm(
        private val outcome: LmOutcome?,
    ) : LmEngine {
        override suspend fun complete(
            system: String,
            examples: List<Example>,
            user: String,
            grammar: String,
            maxTokens: Int,
            assistantPrefix: String,
        ): String =
            when (outcome) {
                is LmOutcome.Parsed -> outcome.raw
                is LmOutcome.Invalid -> outcome.raw
                LmOutcome.TimedOut -> awaitCancellation()
                is LmOutcome.Failed -> throw ReplayException(outcome.error)
                null -> throw ReplayException("the harness pipeline did not ask the language model for this text")
            }
    }

    private class ReplayException(
        message: String,
    ) : Exception(message)

    /** Core's simulated vehicle, with every write counted and the front defrost fixed to the suite's context. */
    private class CountingVehicle(
        private val frontDefrostOn: Boolean?,
    ) : VehicleGateway {
        private val sim = SimulatedVehicleGateway(FROZEN)
        var writes = 0
            private set

        override val isAvailable: Boolean get() = sim.isAvailable

        override suspend fun read(property: ClimateProperty): ReadResult =
            if (property == ClimateProperty.FRONT_DEFROST) {
                frontDefrostOn?.let { ReadResult.Value(if (it) 1 else 0) } ?: ReadResult.Unavailable
            } else {
                sim.read(property)
            }

        override suspend fun write(
            property: ClimateProperty,
            value: Int,
            notAfterMs: Long,
        ): WriteResult {
            writes++
            return sim.write(property, value, notAfterMs)
        }

        override fun latestSignals(): SignalSample? = sim.latestSignals()
    }

    private fun ms(nanos: Long) = nanos / 1_000_000.0

    companion object {
        private val FROZEN = MonotonicClock { 0L }
        private val TURN_HOST = TraceHost(device = "harness", emulator = false, abi = "jvm")

        /** `Allow`, `Confirm`, `Refuse(OUT_OF_DOMAIN)`, ... */
        fun verdictName(verdict: Verdict): String =
            when (verdict) {
                Verdict.Allow -> "Allow"
                Verdict.AllowVoiceOnly -> "AllowVoiceOnly"
                Verdict.Confirm -> "Confirm"
                Verdict.Reprompt -> "Reprompt"
                Verdict.Stop -> "Stop"
                is Verdict.Refuse -> "Refuse(${verdict.reason.name})"
            }
    }
}
