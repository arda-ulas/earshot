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

    /** Allowed when decided, but the driving state became stricter before the write (audit #7). */
    DISCARDED_STATE_CHANGED,
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
    /**
     * Set when this turn asked a confirmation question. The caller must report delivery with
     * [TurnEngine.confirmationDelivered] (or failure with [TurnEngine.confirmationFailed]); until then
     * no answer can confirm it.
     */
    val confirmationId: String? = null,
    /** Non-null when the trace could not be stored; the turn's result is still valid. */
    val traceError: String? = null,
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
    /**
     * A confirmation question. It is only answerable once [deliveredAtMs] is set (the question was
     * actually spoken or shown), and only by an utterance that started after that (audit #4, #5).
     */
    private data class Pending(
        val id: String,
        val command: Command,
        val source: Source,
        val deliveredAtMs: Long? = null,
    )

    private val mutex = Mutex()
    private val lock = Any()
    private var pending: Pending? = null
    private var reprompts = 0

    val isAwaitingConfirmation: Boolean
        get() =
            synchronized(lock) {
                pending?.deliveredAtMs?.let { clock.millis() <= it + config.confirmationTtlMs } ?: false
            }

    /** The question for [confirmationId] has been delivered: its answering window starts now. */
    fun confirmationDelivered(
        confirmationId: String,
        atMs: Long = clock.millis(),
    ) = synchronized(lock) {
        val p = pending
        if (p != null && p.id == confirmationId && p.deliveredAtMs == null) pending = p.copy(deliveredAtMs = atMs)
    }

    /** The question for [confirmationId] could not be delivered: it can never be confirmed. */
    fun confirmationFailed(confirmationId: String) =
        synchronized(lock) {
            if (pending?.id == confirmationId) pending = null
        }

    /** Drops any pending confirmation, e.g. when the app rejects an utterance without handling it. */
    fun abandonConfirmation() = setPending(null)

    /** Takes the pending confirmation out; each turn decides whether one exists afterwards. */
    private fun takePending(): Pending? =
        synchronized(lock) {
            val p = pending
            pending = null
            p
        }

    private fun setPending(p: Pending?) = synchronized(lock) { pending = p }

    /**
     * Handles one utterance. [utteranceEndMs] is when capture stopped and [utteranceStartMs] when it
     * started, on [clock]'s timeline; an answer counts only if it started after its question was
     * delivered.
     */
    suspend fun handle(
        pcm16k: FloatArray,
        inputSource: InputSource,
        utteranceEndMs: Long,
        utteranceStartMs: Long,
    ): TurnResult =
        mutex.withLock {
            val turn = TurnRecorder(newTurnId(), inputSource, clock.nanoTime())
            try {
                // The trace is finalized here, after every stage (including "act") has been timed.
                val draft = runTurn(turn, pcm16k, utteranceEndMs, utteranceStartMs)
                val result = draft.copy(trace = turn.finish(draft.outcome.name, draft.spoken))
                // A storage failure must not hide the result of an action that already happened (audit #16).
                result.copy(traceError = writeTrace(result.trace))
            } catch (e: CancellationException) {
                setPending(null)
                reprompts = 0
                writeTrace(turn.finish("CANCELLED_BY_USER", Responses.CANCELLED))
                throw e
            }
        }

    private fun writeTrace(trace: TurnTrace): String? =
        try {
            traceSink.write(trace)
            null
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            e.javaClass.simpleName
        }

    private suspend fun runTurn(
        turn: TurnRecorder,
        pcm16k: FloatArray,
        utteranceEndMs: Long,
        utteranceStartMs: Long,
    ): TurnResult {
        // This turn owns any pending confirmation: it survives only a re-prompt; everything else ends
        // it, including early returns (audit #3).
        val prior = takePending()
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

                is RuleResult.Rejected -> {
                    Command.OutOfDomain
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

        val currentPending = prior
        val delivered = prior?.deliveredAtMs
        val pendingValid =
            delivered != null &&
                utteranceStartMs >= delivered &&
                utteranceEndMs <= delivered + config.confirmationTtlMs
        val frontDefrostOn =
            if (command is Command.SetFan) readBool(ClimateProperty.FRONT_DEFROST) else null
        val verdict =
            turn.stage("policy") {
                policy.decide(
                    PolicyInput(command, source, state, transcript.confidence, reprompts, pendingValid, frontDefrostOn),
                )
            }
        turn.verdict = verdict

        // Anything other than a re-prompt ends the re-prompt streak.
        if (verdict != Verdict.Reprompt) reprompts = 0

        return when (verdict) {
            Verdict.Reprompt -> {
                reprompts = 1
                // Keep a pending question only if the unclear utterance was itself an answer to it;
                // any other unclear request ends it (audit re-check #3).
                if (command is Command.Answer) setPending(prior)
                turn.result(verdict, Outcome.REPROMPTED, Responses.REPROMPT, null)
            }

            Verdict.Stop -> {
                if (command == Command.Cancel) {
                    turn.result(verdict, Outcome.CANCELLED, Responses.CANCELLED, null)
                } else {
                    turn.result(verdict, Outcome.STOPPED, Responses.STOP_UNCLEAR, null)
                }
            }

            is Verdict.Refuse -> {
                refuse(turn, verdict, currentPending)
            }

            Verdict.Confirm -> {
                val p = Pending(newTurnId(), command, source)
                setPending(p)
                turn
                    .result(verdict, Outcome.CONFIRMATION_REQUESTED, Responses.confirmQuestion(command), null)
                    .copy(confirmationId = p.id)
            }

            Verdict.Allow, Verdict.AllowVoiceOnly -> {
                val voiceOnly = verdict == Verdict.AllowVoiceOnly
                val deadlineMs = utteranceEndMs + config.actionBudgetMs
                if (command is Command.Answer) {
                    val confirmed = checkNotNull(currentPending)
                    if (command.yes) {
                        execute(turn, Act(verdict, confirmed.command, confirmed.source, true, voiceOnly, deadlineMs))
                    } else {
                        turn.result(verdict, Outcome.DECLINED, Responses.DECLINED, null)
                    }
                } else {
                    execute(turn, Act(verdict, command, source, false, voiceOnly, deadlineMs))
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

    /** What was allowed, by whom, and until when it may still start. */
    private data class Act(
        val verdict: Verdict,
        val command: Command,
        val source: Source,
        /** True when the command runs after a spoken yes. */
        val confirmed: Boolean,
        val voiceOnly: Boolean,
        /** Absolute SG-5 deadline: utterance end + action budget (audit #8). */
        val deadlineMs: Long,
    )

    private suspend fun execute(
        turn: TurnRecorder,
        a: Act,
    ): TurnResult {
        if (clock.millis() > a.deadlineMs) {
            return turn.result(a.verdict, Outcome.DISCARDED_STALE, Responses.STALE, null)
        }
        return turn.stage("act") { act(turn, a) }
    }

    /**
     * Re-runs the policy against the driving state now, just before an effect. The decision was
     * taken earlier, and suspending work in between (reads, a slow gateway) can cross a parked-to-moving
     * change (audit #7). A confirmed command may still need a confirmation; anything stricter than
     * what was already granted stops it.
     */
    private suspend fun stillAllowed(a: Act): Boolean {
        val frontDefrostOn = if (a.command is Command.SetFan) readBool(ClimateProperty.FRONT_DEFROST) else null
        // Sampled after the last read, so nothing suspends between this and the write (audit re-check #7).
        val now = drivingState()
        val recheck = policy.decide(PolicyInput(a.command, a.source, now, 1f, 0, false, frontDefrostOn))
        val ceiling = if (a.confirmed) Verdict.Confirm.rank else Verdict.AllowVoiceOnly.rank
        return recheck.rank <= ceiling
    }

    @Suppress("CyclomaticComplexMethod")
    private suspend fun act(
        turn: TurnRecorder,
        a: Act,
    ): TurnResult {
        val verdict = a.verdict
        val command = a.command
        // Screen output only if the car is still parked now, not just when the turn was decided.
        val voiceOnly = a.voiceOnly || drivingState().effective != DrivingState.PARKED

        fun answered(
            spoken: String,
            outcome: Outcome = Outcome.ANSWERED,
        ) = turn.result(verdict, outcome, spoken, if (voiceOnly) null else ScreenContent.Text(spoken))

        return when (command) {
            is Command.SetTemp -> {
                writeThenReadBack(turn, a, ClimateProperty.CABIN_TEMPERATURE_C, command.celsius, voiceOnly)
            }

            is Command.AdjustTemp -> {
                val current = readInt(ClimateProperty.CABIN_TEMPERATURE_C) ?: return unavailable(turn, verdict)
                val target = (current + command.delta).coerceIn(Bounds.TEMP_C)
                if (target == current) {
                    answered(Responses.temperatureAtLimit(current), Outcome.NO_CHANGE)
                } else {
                    writeThenReadBack(turn, a, ClimateProperty.CABIN_TEMPERATURE_C, target, voiceOnly)
                }
            }

            is Command.SetFan -> {
                writeThenReadBack(turn, a, ClimateProperty.FAN_LEVEL, command.level, voiceOnly)
            }

            is Command.SetDefrost -> {
                val property =
                    if (command.window == Window.FRONT) {
                        ClimateProperty.FRONT_DEFROST
                    } else {
                        ClimateProperty.REAR_DEFROST
                    }
                writeThenReadBack(turn, a, property, if (command.on) 1 else 0, voiceOnly)
            }

            is Command.SetAc -> {
                writeThenReadBack(turn, a, ClimateProperty.AC, if (command.on) 1 else 0, voiceOnly)
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
                if (voiceOnly) {
                    val temp = readInt(ClimateProperty.CABIN_TEMPERATURE_C)
                    val fan = readInt(ClimateProperty.FAN_LEVEL)
                    val summary = if (temp != null && fan != null) Responses.shortSummary(temp, fan) else null
                    return turn.result(verdict, Outcome.REFUSED, Responses.screenRefusedWithSummary(summary), null)
                }
                val values = ClimateProperty.entries.associateWith { readInt(it) ?: return unavailable(turn, verdict) }
                // The reads may have taken time: show nothing if the car is no longer parked (audit #7).
                if (drivingState().effective != DrivingState.PARKED) {
                    val summary =
                        Responses.shortSummary(
                            values.getValue(ClimateProperty.CABIN_TEMPERATURE_C),
                            values.getValue(ClimateProperty.FAN_LEVEL),
                        )
                    return turn.result(verdict, Outcome.REFUSED, Responses.screenRefusedWithSummary(summary), null)
                }
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
        a: Act,
        property: ClimateProperty,
        value: Int,
        voiceOnly: Boolean,
    ): TurnResult {
        val verdict = a.verdict
        val command = a.command
        if (!vehicle.isAvailable) return unavailable(turn, verdict)
        // Both checks sit immediately before the write, after every read that could have taken time.
        if (!stillAllowed(a)) return turn.result(verdict, Outcome.DISCARDED_STATE_CHANGED, Responses.STATE_CHANGED, null)
        if (clock.millis() > a.deadlineMs) return turn.result(verdict, Outcome.DISCARDED_STALE, Responses.STALE, null)
        val result = withTimeoutOrNull(config.writeTimeoutMs) { vehicle.write(property, value, a.deadlineMs) } ?: WriteResult.TimedOut
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
