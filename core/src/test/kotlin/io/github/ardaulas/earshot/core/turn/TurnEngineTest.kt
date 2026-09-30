@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.ardaulas.earshot.core.turn

import io.github.ardaulas.earshot.core.command.Command
import io.github.ardaulas.earshot.core.command.Window
import io.github.ardaulas.earshot.core.interpret.LmInterpreter
import io.github.ardaulas.earshot.core.interpret.RuleInterpreter
import io.github.ardaulas.earshot.core.policy.DrivingState
import io.github.ardaulas.earshot.core.policy.Policy
import io.github.ardaulas.earshot.core.requirements.Verifies
import io.github.ardaulas.earshot.core.time.MonotonicClock
import io.github.ardaulas.earshot.core.trace.HostInfo
import io.github.ardaulas.earshot.core.trace.InputSource
import io.github.ardaulas.earshot.core.vehicle.ClimateProperty
import io.github.ardaulas.earshot.core.vehicle.Gear
import io.github.ardaulas.earshot.core.vehicle.SignalSample
import io.github.ardaulas.earshot.core.vehicle.SimulatedVehicleGateway
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private val PCM = FloatArray(0)
private val HOST = HostInfo(device = "test-device", emulator = true, abi = "arm64-v8a")

/** Lets a test change the driving state mid-scenario, since [TurnEngine] takes it as a supplier. */
private class DrivingStateHolder(
    var value: DrivingState,
)

private class Harness(
    val engine: TurnEngine,
    val speech: FakeSpeech,
    val lm: FakeLm,
    val vehicle: FaultInjectingGateway,
    val trace: RecordingTraceSink,
    val drivingState: DrivingStateHolder,
    val now: () -> Long,
) {
    /** Both capture start and end at the current virtual time. */
    suspend fun handle(): TurnResult = engine.handle(PCM, InputSource.CLIP, now(), now())
}

private fun TestScope.harness(
    drivingState: DrivingState,
    useLm: Boolean = true,
    config: TurnConfig = TurnConfig(),
): Harness {
    val clock = MonotonicClock { testScheduler.currentTime * 1_000_000 }
    val speech = FakeSpeech()
    val lm = FakeLm()
    val vehicle = FaultInjectingGateway(SimulatedVehicleGateway(clock))
    val trace = RecordingTraceSink()
    val stateHolder = DrivingStateHolder(drivingState)
    val engine =
        TurnEngine(
            speech = speech,
            rules = RuleInterpreter(),
            lm = if (useLm) LmInterpreter(lm) else null,
            policy = Policy(),
            vehicle = vehicle,
            drivingState = { stateHolder.value },
            clock = clock,
            host = HOST,
            traceSink = trace,
            config = config,
        )
    return Harness(engine, speech, lm, vehicle, trace, stateHolder) { testScheduler.currentTime }
}

/** Handles one utterance and, as the app does, reports a confirmation question as delivered. */
private suspend fun Harness.handleAndDeliver(): TurnResult {
    val r = handle()
    r.confirmationId?.let { engine.confirmationDelivered(it) }
    return r
}

class TurnEngineTest {
    // --- U1..U10 -----------------------------------------------------------------------------

    @Test
    @Verifies("U1", "SR-15")
    fun `U1 parked sets the temperature and confirms by voice and screen`() =
        runTest {
            val h = harness(DrivingState.PARKED)
            h.speech.queue("Set the temperature to 19.", 0.9f)
            val result = h.handleAndDeliver()
            result.outcome shouldBe Outcome.ACTED
            result.spoken shouldBe Responses.temperatureNow(19)
            result.screen shouldBe ScreenContent.Text(result.spoken)
            h.vehicle.writeCount shouldBe 1
        }

    @Test
    @Verifies("U2", "SR-4")
    fun `U2 moving turns on the front defrost, voice only`() =
        runTest {
            val h = harness(DrivingState.MOVING)
            h.speech.queue("Turn on the front defrost.", 0.9f)
            val result = h.handleAndDeliver()
            result.outcome shouldBe Outcome.ACTED
            result.spoken shouldBe Responses.defrostNow(Window.FRONT, on = true)
            result.screen shouldBe null
        }

    @Test
    @Verifies("U3")
    fun `U3 moving speaks the current speed`() =
        runTest {
            val h = harness(DrivingState.MOVING)
            h.vehicle.signalsOverride = SignalSample(63.0, Gear.DRIVE, 0)
            h.speech.queue("How fast am I going?", 0.9f)
            val result = h.handleAndDeliver()
            result.outcome shouldBe Outcome.ANSWERED
            result.spoken shouldBe Responses.speed(63.0)
            result.screen shouldBe null
        }

    @Test
    @Verifies("U4", "SR-4", "SR-5")
    fun `U4 moving refuses the screen with a short spoken summary`() =
        runTest {
            val h = harness(DrivingState.MOVING)
            h.speech.queue("Show me my climate settings.", 0.9f)
            val result = h.handleAndDeliver()
            result.outcome shouldBe Outcome.REFUSED
            result.spoken shouldBe Responses.screenRefusedWithSummary(Responses.shortSummary(21, 2))
            result.screen shouldBe null
        }

    @Test
    @Verifies("U4")
    fun `U4 parked shows the climate panel`() =
        runTest {
            val h = harness(DrivingState.PARKED)
            h.speech.queue("Show me my climate settings.", 0.9f)
            val result = h.handleAndDeliver()
            result.outcome shouldBe Outcome.ANSWERED
            result.spoken shouldBe Responses.SHOWING_CLIMATE
            result.screen shouldBe ScreenContent.ClimatePanel(SimulatedVehicleGateway.DEFAULT_CLIMATE)
        }

    @Test
    @Verifies("U5")
    fun `U5 make it warmer raises the temperature by one`() =
        runTest {
            val h = harness(DrivingState.PARKED)
            h.speech.queue("Make it warmer.", 0.9f)
            val result = h.handleAndDeliver()
            result.outcome shouldBe Outcome.ACTED
            result.spoken shouldBe Responses.temperatureNow(22)
        }

    @Test
    @Verifies("U5", "SR-3")
    fun `U5 warmer at the maximum makes no change and writes nothing`() =
        runTest {
            val h = harness(DrivingState.PARKED)
            h.vehicle.write(ClimateProperty.CABIN_TEMPERATURE_C, 28)
            val writesBefore = h.vehicle.writeCount
            h.speech.queue("Make it warmer.", 0.9f)
            val result = h.handleAndDeliver()
            result.outcome shouldBe Outcome.NO_CHANGE
            result.spoken shouldBe Responses.temperatureAtLimit(28)
            h.vehicle.writeCount shouldBe writesBefore
        }

    @Test
    @Verifies("U6")
    fun `U6 order me a pizza is refused, no action`() =
        runTest {
            val h = harness(DrivingState.PARKED, useLm = false)
            h.speech.queue("Order me a pizza.", 0.9f)
            val result = h.handleAndDeliver()
            result.outcome shouldBe Outcome.REFUSED
            result.spoken shouldBe Responses.OUT_OF_DOMAIN
            h.vehicle.writeCount shouldBe 0
        }

    @Test
    @Verifies("U7", "SR-1", "SR-2")
    fun `U7 unclear audio re-prompts once then stops, without ever consulting the language model`() =
        runTest {
            val h = harness(DrivingState.PARKED)
            h.speech.queue("mumble mumble", 0.1f)
            val first = h.handleAndDeliver()
            first.outcome shouldBe Outcome.REPROMPTED
            first.spoken shouldBe Responses.REPROMPT

            h.speech.queue("mumble mumble", 0.1f)
            val second = h.handleAndDeliver()
            second.outcome shouldBe Outcome.STOPPED
            second.spoken shouldBe Responses.STOP_UNCLEAR

            h.lm.callCount shouldBe 0
            h.vehicle.writeCount shouldBe 0
        }

    @Test
    @Verifies("U8", "SR-18")
    fun `U8 never mind cancels a pending confirmation`() =
        runTest {
            val h = harness(DrivingState.MOVING)
            h.speech.queue("Turn off the defrost.", 0.9f)
            h.handleAndDeliver()
            h.engine.isAwaitingConfirmation shouldBe true

            h.speech.queue("Never mind.", 0.9f)
            val result = h.handleAndDeliver()
            result.outcome shouldBe Outcome.CANCELLED
            result.spoken shouldBe Responses.CANCELLED
            h.engine.isAwaitingConfirmation shouldBe false
            h.vehicle.writeCount shouldBe 0
        }

    @Test
    @Verifies("U9", "SR-8")
    fun `U9 defrost off while moving asks first, then acts on yes`() =
        runTest {
            val h = harness(DrivingState.MOVING)
            h.speech.queue("Turn off the defrost.", 0.9f)
            val asked = h.handleAndDeliver()
            asked.outcome shouldBe Outcome.CONFIRMATION_REQUESTED
            asked.spoken shouldBe Responses.confirmQuestion(Command.SetDefrost(Window.FRONT, on = false))
            h.vehicle.writeCount shouldBe 0

            h.speech.queue("yes", 0.9f)
            val acted = h.handleAndDeliver()
            acted.outcome shouldBe Outcome.ACTED
            acted.spoken shouldBe Responses.defrostNow(Window.FRONT, on = false)
            acted.screen shouldBe null
            h.vehicle.writeCount shouldBe 1
        }

    @Test
    @Verifies("U9", "SR-8")
    fun `U9 defrost off while moving, declining does nothing`() =
        runTest {
            val h = harness(DrivingState.MOVING)
            h.speech.queue("Turn off the defrost.", 0.9f)
            h.handleAndDeliver()

            h.speech.queue("no", 0.9f)
            val result = h.handleAndDeliver()
            result.outcome shouldBe Outcome.DECLINED
            result.spoken shouldBe Responses.DECLINED
            h.vehicle.writeCount shouldBe 0
        }

    @Test
    @Verifies("U9", "SR-18")
    fun `U9 a confirmation expires after 10 seconds`() =
        runTest {
            val h = harness(DrivingState.MOVING)
            h.speech.queue("Turn off the defrost.", 0.9f)
            h.handleAndDeliver()

            testScheduler.advanceTimeBy(10_001)

            h.speech.queue("yes", 0.9f)
            val result = h.handleAndDeliver()
            result.outcome shouldBe Outcome.EXPIRED
            result.spoken shouldBe Responses.CONFIRMATION_EXPIRED
            h.vehicle.writeCount shouldBe 0
            h.engine.isAwaitingConfirmation shouldBe false
        }

    @Test
    @Verifies("U9", "SR-18")
    fun `U9 expiry is measured to the end of the answer, so slow speech-to-text does not count`() =
        runTest {
            val h = harness(DrivingState.MOVING)
            h.speech.queue("Turn off the defrost.", 0.9f)
            h.handleAndDeliver()

            // The driver finishes saying "yes" at 9 s; recognizing it takes 3 s more.
            testScheduler.advanceTimeBy(9_000)
            h.speech.queue("yes", 0.9f, delayMs = 3_000)
            val result = h.handleAndDeliver()
            result.outcome shouldBe Outcome.ACTED
            h.vehicle.writeCount shouldBe 1
        }

    @Test
    @Verifies("U9", "SR-18")
    fun `U9 a new request abandons a pending confirmation`() =
        runTest {
            val h = harness(DrivingState.MOVING)
            h.speech.queue("Turn off the defrost.", 0.9f)
            h.handleAndDeliver()
            h.engine.isAwaitingConfirmation shouldBe true

            h.speech.queue("How fast am I going?", 0.9f)
            h.handleAndDeliver()
            h.engine.isAwaitingConfirmation shouldBe false

            h.speech.queue("yes", 0.9f)
            val result = h.handleAndDeliver()
            result.outcome shouldBe Outcome.REFUSED
            result.spoken shouldBe Responses.NOTHING_PENDING
            h.vehicle.writeCount shouldBe 0
        }

    @Test
    @Verifies("U10", "SR-9", "SR-10")
    fun `U10 an indirect request goes through the language model and needs confirmation`() =
        runTest {
            val h = harness(DrivingState.PARKED)
            h.lm.queueReturns("""{"intent":"warmer"}""")
            h.speech.queue("I'm freezing", 0.9f)
            val asked = h.handleAndDeliver()
            asked.outcome shouldBe Outcome.CONFIRMATION_REQUESTED
            asked.spoken shouldBe Responses.confirmQuestion(Command.AdjustTemp(2))
            h.lm.callCount shouldBe 1

            h.speech.queue("yes", 0.9f)
            val acted = h.handleAndDeliver()
            acted.outcome shouldBe Outcome.ACTED
            acted.spoken shouldBe Responses.temperatureNow(23)
        }

    // --- Language-model failure modes: "not understood", nothing happens (SR-11) -------------

    @Test
    @Verifies("SR-9", "SR-11")
    fun `invalid language model output is refused, no action`() =
        runTest {
            val h = harness(DrivingState.PARKED)
            h.lm.queueReturns("not json")
            h.speech.queue("I'm freezing", 0.9f)
            val result = h.handleAndDeliver()
            result.outcome shouldBe Outcome.REFUSED
            result.spoken shouldBe Responses.OUT_OF_DOMAIN
            h.vehicle.writeCount shouldBe 0
        }

    @Test
    @Verifies("SR-11")
    fun `a language model timeout is refused, no action`() =
        runTest {
            val h = harness(DrivingState.PARKED)
            h.lm.queueHangs()
            h.speech.queue("I'm freezing", 0.9f)
            val result = h.handleAndDeliver()
            result.outcome shouldBe Outcome.REFUSED
            result.spoken shouldBe Responses.OUT_OF_DOMAIN
            h.vehicle.writeCount shouldBe 0
        }

    @Test
    @Verifies("SR-11")
    fun `a language model failure is refused, no action`() =
        runTest {
            val h = harness(DrivingState.PARKED)
            h.lm.queueThrows()
            h.speech.queue("I'm freezing", 0.9f)
            val result = h.handleAndDeliver()
            result.outcome shouldBe Outcome.REFUSED
            result.spoken shouldBe Responses.OUT_OF_DOMAIN
            h.vehicle.writeCount shouldBe 0
        }

    // --- SG-5: the 5 second action budget (SR-7) ----------------------------------------------

    @Test
    @Verifies("SR-7")
    fun `SG-5 a stale action is discarded when speech recognition itself takes too long`() =
        runTest {
            val h = harness(DrivingState.PARKED)
            h.speech.queue("Set the temperature to 19.", 0.9f, delayMs = 6_000)
            val result = h.handleAndDeliver()
            result.outcome shouldBe Outcome.DISCARDED_STALE
            result.spoken shouldBe Responses.STALE
            h.vehicle.writeCount shouldBe 0
        }

    @Test
    @Verifies("SR-7")
    fun `SG-5 the action budget is measured from the confirming utterance, not the original request`() =
        runTest {
            val h = harness(DrivingState.MOVING)
            h.speech.queue("Turn off the defrost.", 0.9f)
            h.handleAndDeliver()

            // Six seconds pass with the driver saying nothing - longer than the 5 s action budget,
            // but well inside the 10 s confirmation window.
            testScheduler.advanceTimeBy(6_000)

            h.speech.queue("yes", 0.9f)
            val result = h.handleAndDeliver()
            result.outcome shouldBe Outcome.ACTED
            result.spoken shouldBe Responses.defrostNow(Window.FRONT, on = false)
        }

    // --- Fault injection: SR-13, SR-14, SR-15, SR-16 -------------------------------------------

    @Test
    @Verifies("SR-13")
    fun `vehicle unavailable fails with a spoken unavailable message`() =
        runTest {
            val h = harness(DrivingState.PARKED)
            h.vehicle.forceUnavailable = true
            h.speech.queue("Set the temperature to 19.", 0.9f)
            val result = h.handleAndDeliver()
            result.outcome shouldBe Outcome.FAILED
            result.spoken shouldBe Responses.CONTROLS_UNAVAILABLE
        }

    @Test
    @Verifies("SR-14", "SR-16")
    fun `a rejected write fails by voice with no retry`() =
        runTest {
            val h = harness(DrivingState.PARKED)
            h.vehicle.rejectWrites = true
            h.speech.queue("Set the temperature to 19.", 0.9f)
            val result = h.handleAndDeliver()
            result.outcome shouldBe Outcome.FAILED
            result.spoken shouldBe Responses.writeFailed(Command.SetTemp(19))
            h.vehicle.writeCount shouldBe 1
        }

    @Test
    @Verifies("SR-14", "SR-16")
    fun `a write that hangs fails after the write timeout, with no retry`() =
        runTest {
            val h = harness(DrivingState.PARKED, config = TurnConfig(writeTimeoutMs = 1_000))
            h.vehicle.hangWrites = true
            h.speech.queue("Set the temperature to 19.", 0.9f)
            val result = h.handleAndDeliver()
            result.outcome shouldBe Outcome.FAILED
            result.spoken shouldBe Responses.writeFailed(Command.SetTemp(19))
            h.vehicle.writeCount shouldBe 1
        }

    @Test
    @Verifies("SR-15")
    fun `the spoken confirmation comes from the read-back, never the request`() =
        runTest {
            val h = harness(DrivingState.PARKED)
            h.vehicle.readOverride[ClimateProperty.CABIN_TEMPERATURE_C] = 99
            h.speech.queue("Set the temperature to 19.", 0.9f)
            val result = h.handleAndDeliver()
            result.outcome shouldBe Outcome.ACTED
            result.spoken shouldBe Responses.temperatureNow(99)
        }

    @Test
    @Verifies("SR-16")
    fun `at most one vehicle write happens per turn, across acted, no-change and failed outcomes`() =
        runTest {
            val acted = harness(DrivingState.PARKED)
            acted.speech.queue("Set the temperature to 19.", 0.9f)
            acted.handle()
            (acted.vehicle.writeCount <= 1) shouldBe true

            val noChange = harness(DrivingState.PARKED)
            noChange.vehicle.write(ClimateProperty.CABIN_TEMPERATURE_C, 28)
            val before = noChange.vehicle.writeCount
            noChange.speech.queue("Make it warmer.", 0.9f)
            noChange.handle()
            (noChange.vehicle.writeCount - before <= 1) shouldBe true

            val failed = harness(DrivingState.PARKED)
            failed.vehicle.rejectWrites = true
            failed.speech.queue("Set the temperature to 19.", 0.9f)
            failed.handle()
            (failed.vehicle.writeCount <= 1) shouldBe true

            val confirmed = harness(DrivingState.MOVING)
            confirmed.speech.queue("Turn off the defrost.", 0.9f)
            confirmed.handleAndDeliver()
            confirmed.speech.queue("yes", 0.9f)
            confirmed.handleAndDeliver()
            (confirmed.vehicle.writeCount <= 1) shouldBe true
        }

    // --- Misc: unclear STT, cancellation, tracing ----------------------------------------------

    @Test
    @Verifies("SR-1", "SR-2")
    fun `speech recognition throwing is treated as unknown confidence and re-prompts`() =
        runTest {
            val h = harness(DrivingState.PARKED)
            h.speech.queueThrow()
            val result = h.handleAndDeliver()
            result.outcome shouldBe Outcome.REPROMPTED
            result.spoken shouldBe Responses.REPROMPT
            h.vehicle.writeCount shouldBe 0
        }

    @Test
    @Verifies("SR-2")
    fun `speech recognition slower than its timeout is abandoned and re-prompts`() =
        runTest {
            val h = harness(DrivingState.PARKED)
            h.speech.queue("Set the temperature to 19.", 0.9f, delayMs = 60_000)
            val result = h.handleAndDeliver()
            result.outcome shouldBe Outcome.REPROMPTED
            h.vehicle.writeCount shouldBe 0
            (
                h.trace.traces
                    .last()
                    .stages
                    .first { it.stage == "stt" }
                    .durationMs <= TurnConfig().sttTimeoutMs
            ) shouldBe true
        }

    @Test
    fun `cancelling the coroutine mid-turn records a CANCELLED_BY_USER trace and rethrows`() =
        runTest {
            val h = harness(DrivingState.PARKED)
            h.speech.queue("Set the temperature to 19.", 0.9f, delayMs = Long.MAX_VALUE)
            var completedNormally = false
            val job =
                launch {
                    h.handleAndDeliver()
                    completedNormally = true
                }
            runCurrent()
            job.cancel()
            job.join()

            completedNormally shouldBe false
            job.isCancelled shouldBe true
            val last = h.trace.traces.last()
            last.outcome shouldBe "CANCELLED_BY_USER"
            last.spoken shouldBe Responses.CANCELLED
        }

    @Test
    @Verifies("SR-21")
    fun `every trace records its stages, input source and host`() =
        runTest {
            val h = harness(DrivingState.PARKED)
            h.speech.queue("Set the temperature to 19.", 0.9f)
            h.handleAndDeliver()
            val acted = h.trace.traces.last()
            acted.inputSource shouldBe InputSource.CLIP
            acted.host shouldBe HOST
            (acted.totalMs >= 0.0) shouldBe true
            val actedStages = acted.stages.map { it.stage }.toSet()
            (actedStages.containsAll(listOf("stt", "rules", "policy", "act"))) shouldBe true
            ("lm" in actedStages) shouldBe false

            h.lm.queueReturns("""{"intent":"warmer"}""")
            h.speech.queue("I'm freezing", 0.9f)
            h.handleAndDeliver()
            val confirmed = h.trace.traces.last()
            val confirmedStages = confirmed.stages.map { it.stage }.toSet()
            (confirmedStages.containsAll(listOf("stt", "rules", "lm", "policy"))) shouldBe true
            ("act" in confirmedStages) shouldBe false
            (confirmed.totalMs >= 0.0) shouldBe true
        }
}
