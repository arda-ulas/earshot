package io.github.ardaulas.earshot.core.turn

import io.github.ardaulas.earshot.core.command.Bounds
import io.github.ardaulas.earshot.core.command.Command
import io.github.ardaulas.earshot.core.command.Window
import io.github.ardaulas.earshot.core.interpret.LmInterpreter
import io.github.ardaulas.earshot.core.interpret.LmOutcome
import io.github.ardaulas.earshot.core.interpret.RuleInterpreter
import io.github.ardaulas.earshot.core.interpret.RuleResult
import io.github.ardaulas.earshot.core.policy.DrivingState
import io.github.ardaulas.earshot.core.policy.Policy
import io.github.ardaulas.earshot.core.policy.PolicyInput
import io.github.ardaulas.earshot.core.policy.RefuseReason
import io.github.ardaulas.earshot.core.policy.Source
import io.github.ardaulas.earshot.core.policy.Verdict
import io.github.ardaulas.earshot.core.speech.SpeechEngine
import io.github.ardaulas.earshot.core.speech.Transcript
import io.github.ardaulas.earshot.core.time.MonotonicClock
import io.github.ardaulas.earshot.core.trace.HostInfo
import io.github.ardaulas.earshot.core.trace.InputSource
import io.github.ardaulas.earshot.core.trace.StageTiming
import io.github.ardaulas.earshot.core.trace.TraceSink
import io.github.ardaulas.earshot.core.trace.TurnTrace
import io.github.ardaulas.earshot.core.vehicle.ClimateProperty
import io.github.ardaulas.earshot.core.vehicle.ReadResult
import io.github.ardaulas.earshot.core.vehicle.VehicleGateway
import io.github.ardaulas.earshot.core.vehicle.WriteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

data class TurnConfig(
    /** SG-5: an action must start within this long after the utterance ends, or it is discarded. */
    val actionBudgetMs: Long = 5_000,
    /**
     * How long a confirmation question waits for its yes or no, measured to the end of the answering
     * utterance so slow speech-to-text does not count against the driver.
     */
    val confirmationTtlMs: Long = 10_000,
    /** Speech-to-text that takes longer is abandoned and treated as unclear audio (re-prompt). */
    val sttTimeoutMs: Long = 10_000,
    /** A vehicle write that takes longer counts as failed; there is no retry loop. */
    val writeTimeoutMs: Long = 1_000,
)

enum class Outcome {
    ACTED,
    ANSWERED,
    NO_CHANGE,
    CONFIRMATION_REQUESTED,
    DECLINED,
    CANCELLED,
    REFUSED,
    OUT_OF_RANGE,
    REPROMPTED,
    STOPPED,
    EXPIRED,
    DISCARDED_STALE,
    FAILED,
}

/** What may appear on screen. Null in a [TurnResult] means voice only. */
sealed interface ScreenContent {
    data class Text(
        val text: String,
    ) : ScreenContent

    data class ClimatePanel(
        val values: Map<ClimateProperty, Int>,
    ) : ScreenContent
}

data class TurnResult(
    val transcript: String?,
    val spoken: String,
    val screen: ScreenContent?,
    val verdict: Verdict?,
    val outcome: Outcome,
    val awaitingConfirmation: Boolean,
    val trace: TurnTrace,
)

/**
 * Runs one push-to-talk turn: speech-to-text, rules, the language model on a rule miss, the policy,
 * then the vehicle. The policy's verdict is the only way to reach [VehicleGateway.write]. One action
 * per turn at most. Turns are serialized; cancel a turn by cancelling its coroutine.
 */
class TurnEngine(
    private val speech: SpeechEngine,
    private val rules: RuleInterpreter,
    private val lm: LmInterpreter?,
    private val policy: Policy,
    private val vehicle: VehicleGateway,
    private val drivingState: () -> DrivingState,
    private val clock: MonotonicClock,
    private val host: HostInfo,
    private val traceSink: TraceSink,
    private val config: TurnConfig = TurnConfig(),
    private val newTurnId: () -> String = { UUID.randomUUID().toString() },
) {
    private data class Pending(
        val command: Command,
        val expiresAtMs: Long,
    )

    private val mutex = Mutex()
    private var pending: Pending? = null
    private var reprompts = 0

    val isAwaitingConfirmation: Boolean get() = pending?.let { clock.millis() <= it.expiresAtMs } ?: false

    /** Handles one utterance. [utteranceEndMs] is when capture stopped, on [clock]'s timeline. */
    suspend fun handle(
        pcm16k: FloatArray,
        inputSource: InputSource,
        utteranceEndMs: Long = clock.millis(),
    ): TurnResult =
        mutex.withLock {
            val turn = TurnRecorder(newTurnId(), inputSource, clock.nanoTime())
            try {
                // The trace is finalized here, after every stage (including "act") has been timed.
                val draft = runTurn(turn, pcm16k, utteranceEndMs)
                draft.copy(trace = turn.finish(draft.outcome.name, draft.spoken)).also { traceSink.write(it.trace) }
            } catch (e: CancellationException) {
                pending = null
                reprompts = 0
                traceSink.write(turn.finish("CANCELLED_BY_USER", Responses.CANCELLED))
                throw e
            }
        }

    private suspend fun runTurn(
        turn: TurnRecorder,
        pcm16k: FloatArray,
        utteranceEndMs: Long,
    ): TurnResult {
        val transcript =
            turn.stage("stt") {
                try {
                    withTimeoutOrNull(config.sttTimeoutMs) { speech.transcribe(pcm16k) } ?: Transcript("", null)
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    Transcript("", null)
                }
            }
        turn.transcript = transcript
        val state = drivingState()
        turn.drivingState = state
        val confident = policy.isConfident(transcript.confidence)

        // Interpret. Rules always run (cheap, and "cancel" must work even when unclear); the language
        // model only runs on a rule miss at or above the confidence threshold, so audio the policy would
        // re-prompt for never reaches it.
        val ruleResult = turn.stage("rules") { rules.interpret(transcript.text) }
        var source = Source.RULES
        val command: Command =
            when (ruleResult) {
                is RuleResult.Matched -> {
                    ruleResult.command
                }

                is RuleResult.OutOfRange -> {
                    if (confident) {
                        reprompts = 0
                        val spoken = Responses.outOfRange(ruleResult.what, ruleResult.range)
                        return turn.result(null, Outcome.OUT_OF_RANGE, spoken, screenFor(state, spoken))
                    }
                    Command.OutOfDomain
                }

                RuleResult.NoMatch -> {
                    val lmInterpreter = lm
                    if (confident && lmInterpreter != null) {
                        source = Source.LM
                        val outcome = turn.stage("lm") { lmInterpreter.interpret(transcript.text) }
                        turn.lmOutcome = outcome
                        (outcome as? LmOutcome.Parsed)?.command ?: Command.OutOfDomain
                    } else {
                        Command.OutOfDomain
                    }
                }
            }
        turn.command = command
        turn.source = source

        val now = clock.millis()
        val currentPending = pending
        val pendingValid = currentPending != null && utteranceEndMs <= currentPending.expiresAtMs
        val frontDefrostOn =
            if (command is Command.SetFan) readBool(ClimateProperty.FRONT_DEFROST) else null
        val verdict =
            turn.stage("policy") {
                policy.decide(
                    PolicyInput(command, source, state, transcript.confidence, reprompts, pendingValid, frontDefrostOn),
                )
            }
        turn.verdict = verdict

        // Anything other than a re-prompt ends the re-prompt streak; anything other than a re-prompt or
        // an answer abandons a pending confirmation.
        if (verdict != Verdict.Reprompt) reprompts = 0
        if (verdict != Verdict.Reprompt && command !is Command.Answer) pending = null

        return when (verdict) {
            Verdict.Reprompt -> {
                reprompts = 1
                turn.result(verdict, Outcome.REPROMPTED, Responses.REPROMPT, null)
            }

            Verdict.Stop -> {
                if (command == Command.Cancel) {
                    pending = null
                    turn.result(verdict, Outcome.CANCELLED, Responses.CANCELLED, null)
                } else {
                    turn.result(verdict, Outcome.STOPPED, Responses.STOP_UNCLEAR, null)
                }
            }

            is Verdict.Refuse -> {
                refuse(turn, verdict, currentPending)
            }

            Verdict.Confirm -> {
                pending = Pending(command, now + config.confirmationTtlMs)
                turn.result(verdict, Outcome.CONFIRMATION_REQUESTED, Responses.confirmQuestion(command), null)
            }

            Verdict.Allow, Verdict.AllowVoiceOnly -> {
                val voiceOnly = verdict == Verdict.AllowVoiceOnly
                if (command is Command.Answer) {
                    val confirmed = checkNotNull(currentPending).command
                    pending = null
                    if (command.yes) {
                        execute(turn, verdict, confirmed, voiceOnly, utteranceEndMs)
                    } else {
                        turn.result(verdict, Outcome.DECLINED, Responses.DECLINED, null)
                    }
                } else {
                    execute(turn, verdict, command, voiceOnly, utteranceEndMs)
                }
            }
        }
    }

    private suspend fun refuse(
        turn: TurnRecorder,
        verdict: Verdict.Refuse,
        previousPending: Pending?,
    ): TurnResult =
        when (verdict.reason) {
            RefuseReason.OUT_OF_DOMAIN, RefuseReason.NOT_PERMITTED_FROM_LM -> {
                turn.result(verdict, Outcome.REFUSED, Responses.OUT_OF_DOMAIN, null)
            }

            RefuseReason.NOTHING_PENDING -> {
                pending = null
                if (previousPending != null) {
                    turn.result(verdict, Outcome.EXPIRED, Responses.CONFIRMATION_EXPIRED, null)
                } else {
                    turn.result(verdict, Outcome.REFUSED, Responses.NOTHING_PENDING, null)
                }
            }

            RefuseReason.SCREEN_WHILE_MOVING -> {
                val temp = readInt(ClimateProperty.CABIN_TEMPERATURE_C)
                val fan = readInt(ClimateProperty.FAN_LEVEL)
                val summary = if (temp != null && fan != null) Responses.shortSummary(temp, fan) else null
                turn.result(verdict, Outcome.REFUSED, Responses.screenRefusedWithSummary(summary), null)
            }
        }

    private suspend fun execute(
        turn: TurnRecorder,
        verdict: Verdict,
        command: Command,
        voiceOnly: Boolean,
        utteranceEndMs: Long,
    ): TurnResult {
        if (clock.millis() - utteranceEndMs > config.actionBudgetMs) {
            return turn.result(verdict, Outcome.DISCARDED_STALE, Responses.STALE, null)
        }
        return turn.stage("act") { act(turn, verdict, command, voiceOnly) }
    }

    @Suppress("CyclomaticComplexMethod")
    private suspend fun act(
        turn: TurnRecorder,
        verdict: Verdict,
        command: Command,
        voiceOnly: Boolean,
    ): TurnResult {
        fun answered(
            spoken: String,
            outcome: Outcome = Outcome.ANSWERED,
        ) = turn.result(verdict, outcome, spoken, if (voiceOnly) null else ScreenContent.Text(spoken))

        return when (command) {
            is Command.SetTemp -> {
                writeThenReadBack(turn, verdict, command, ClimateProperty.CABIN_TEMPERATURE_C, command.celsius, voiceOnly)
            }

            is Command.AdjustTemp -> {
                val current = readInt(ClimateProperty.CABIN_TEMPERATURE_C) ?: return unavailable(turn, verdict)
                val target = (current + command.delta).coerceIn(Bounds.TEMP_C)
                if (target == current) {
                    answered(Responses.temperatureAtLimit(current), Outcome.NO_CHANGE)
                } else {
                    writeThenReadBack(turn, verdict, command, ClimateProperty.CABIN_TEMPERATURE_C, target, voiceOnly)
                }
            }

            is Command.SetFan -> {
                writeThenReadBack(turn, verdict, command, ClimateProperty.FAN_LEVEL, command.level, voiceOnly)
            }

            is Command.SetDefrost -> {
                val property =
                    if (command.window == Window.FRONT) {
                        ClimateProperty.FRONT_DEFROST
                    } else {
                        ClimateProperty.REAR_DEFROST
                    }
                writeThenReadBack(turn, verdict, command, property, if (command.on) 1 else 0, voiceOnly)
            }

            is Command.SetAc -> {
                writeThenReadBack(turn, verdict, command, ClimateProperty.AC, if (command.on) 1 else 0, voiceOnly)
            }

            Command.QuerySpeed -> {
                val speed = vehicle.latestSignals()?.speedKmh
                answered(if (speed == null || speed.isNaN()) Responses.SPEED_UNAVAILABLE else Responses.speed(speed))
            }

            Command.QueryGear -> {
                val gear = vehicle.latestSignals()?.gear
                answered(if (gear == null) Responses.GEAR_UNAVAILABLE else Responses.gear(gear))
            }

            Command.QueryCabin -> {
                val temp = readInt(ClimateProperty.CABIN_TEMPERATURE_C)
                val fan = readInt(ClimateProperty.FAN_LEVEL)
                val ac = readBool(ClimateProperty.AC)
                if (temp == null || fan == null || ac == null) unavailable(turn, verdict) else answered(Responses.cabin(temp, fan, ac))
            }

            Command.ShowClimate -> {
                val values = ClimateProperty.entries.associateWith { readInt(it) ?: return unavailable(turn, verdict) }
                turn.result(
                    verdict,
                    Outcome.ANSWERED,
                    Responses.SHOWING_CLIMATE,
                    if (voiceOnly) null else ScreenContent.ClimatePanel(values),
                )
            }

            Command.Help -> {
                answered(if (voiceOnly) Responses.HELP_SHORT else Responses.HELP_FULL)
            }

            // Handled before execution; reaching here would be a bug, so do nothing.
            Command.Cancel, is Command.Answer, Command.OutOfDomain -> {
                turn.result(verdict, Outcome.REFUSED, Responses.OUT_OF_DOMAIN, null)
            }
        }
    }

    /** Writes, then speaks the value read back from the vehicle, never the requested one. */
    private suspend fun writeThenReadBack(
        turn: TurnRecorder,
        verdict: Verdict,
        command: Command,
        property: ClimateProperty,
        value: Int,
        voiceOnly: Boolean,
    ): TurnResult {
        if (!vehicle.isAvailable) return unavailable(turn, verdict)
        val result = withTimeoutOrNull(config.writeTimeoutMs) { vehicle.write(property, value) } ?: WriteResult.TimedOut
        turn.writeResult = result
        val readBack = readInt(property)
        return when (result) {
            WriteResult.Ok -> {
                if (readBack == null) return unavailable(turn, verdict)
                val spoken =
                    when (property) {
                        ClimateProperty.CABIN_TEMPERATURE_C -> {
                            Responses.temperatureNow(readBack)
                        }

                        ClimateProperty.FAN_LEVEL -> {
                            Responses.fanNow(readBack)
                        }

                        ClimateProperty.FRONT_DEFROST -> {
                            Responses.defrostNow(Window.FRONT, readBack == 1)
                        }

                        ClimateProperty.REAR_DEFROST -> {
                            Responses.defrostNow(Window.REAR, readBack == 1)
                        }

                        ClimateProperty.AC -> {
                            Responses.acNow(readBack == 1)
                        }
                    }
                turn.result(verdict, Outcome.ACTED, spoken, if (voiceOnly) null else ScreenContent.Text(spoken))
            }

            WriteResult.Rejected, WriteResult.TimedOut -> {
                turn.result(verdict, Outcome.FAILED, Responses.writeFailed(command), null)
            }

            WriteResult.Unavailable -> {
                unavailable(turn, verdict)
            }
        }
    }

    private fun unavailable(
        turn: TurnRecorder,
        verdict: Verdict,
    ) = turn.result(verdict, Outcome.FAILED, Responses.CONTROLS_UNAVAILABLE, null)

    private suspend fun readInt(property: ClimateProperty): Int? =
        if (!vehicle.isAvailable) {
            null
        } else {
            (withTimeoutOrNull(config.writeTimeoutMs) { vehicle.read(property) } as? ReadResult.Value)?.value
        }

    private suspend fun readBool(property: ClimateProperty): Boolean? = readInt(property)?.let { it != 0 }

    private fun screenFor(
        state: DrivingState,
        text: String,
    ): ScreenContent? = if (state.effective == DrivingState.PARKED) ScreenContent.Text(text) else null

    /** Collects stage timings and decisions for one turn's trace. */
    private inner class TurnRecorder(
        val turnId: String,
        val inputSource: InputSource,
        val startNanos: Long,
    ) {
        private val stages = mutableListOf<StageTiming>()
        var transcript: Transcript? = null
        var drivingState: DrivingState? = null
        var command: Command? = null
        var source: Source? = null
        var lmOutcome: LmOutcome? = null
        var verdict: Verdict? = null
        var writeResult: WriteResult? = null

        suspend fun <T> stage(
            name: String,
            block: suspend () -> T,
        ): T {
            val start = clock.nanoTime()
            try {
                return block()
            } finally {
                val end = clock.nanoTime()
                stages += StageTiming(name, ms(start - startNanos), ms(end - start))
            }
        }

        fun result(
            verdict: Verdict?,
            outcome: Outcome,
            spoken: String,
            screen: ScreenContent?,
        ) = TurnResult(
            transcript = transcript?.text,
            spoken = spoken,
            screen = screen,
            verdict = verdict,
            outcome = outcome,
            awaitingConfirmation = pending != null,
            trace = finish(outcome.name, spoken),
        )

        fun finish(
            outcome: String,
            spoken: String,
        ) = TurnTrace(
            turnId = turnId,
            inputSource = inputSource,
            host = host,
            drivingState = drivingState?.name ?: "NOT_READ",
            transcript = transcript?.text,
            asrConfidence = transcript?.confidence,
            command = command?.toString(),
            commandSource = source?.name,
            lmOutcome = lmOutcome?.let(::describe),
            verdict = verdict?.toString(),
            outcome = outcome + (writeResult?.let { " (write: $it)" } ?: ""),
            spoken = spoken,
            stages = stages.toList(),
            totalMs = ms(clock.nanoTime() - startNanos),
        )

        private fun describe(outcome: LmOutcome) =
            when (outcome) {
                is LmOutcome.Parsed -> "PARSED ${outcome.raw}"
                is LmOutcome.Invalid -> "INVALID ${outcome.raw.take(80)}"
                LmOutcome.TimedOut -> "TIMED_OUT"
                is LmOutcome.Failed -> "FAILED ${outcome.error}"
            }

        private fun ms(nanos: Long) = nanos / 1_000_000.0
    }
}
