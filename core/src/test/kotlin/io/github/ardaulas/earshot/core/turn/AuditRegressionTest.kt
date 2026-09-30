@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.ardaulas.earshot.core.turn

import io.github.ardaulas.earshot.core.command.Bounds
import io.github.ardaulas.earshot.core.command.Command
import io.github.ardaulas.earshot.core.interpret.LmInterpreter
import io.github.ardaulas.earshot.core.interpret.RuleInterpreter
import io.github.ardaulas.earshot.core.interpret.RuleResult
import io.github.ardaulas.earshot.core.policy.DrivingState
import io.github.ardaulas.earshot.core.policy.Policy
import io.github.ardaulas.earshot.core.requirements.Verifies
import io.github.ardaulas.earshot.core.time.MonotonicClock
import io.github.ardaulas.earshot.core.trace.HostInfo
import io.github.ardaulas.earshot.core.trace.InputSource
import io.github.ardaulas.earshot.core.vehicle.ClimateProperty
import io.github.ardaulas.earshot.core.vehicle.DrivingStateResolver
import io.github.ardaulas.earshot.core.vehicle.Gear
import io.github.ardaulas.earshot.core.vehicle.ReadResult
import io.github.ardaulas.earshot.core.vehicle.SignalSample
import io.github.ardaulas.earshot.core.vehicle.SimulatedVehicleGateway
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Core-level reproductions of the hostile audit of v0.2.1 (2026-09-30) and of its re-audits. They cover
 * the scenarios that live in :core. App-level fixes (capture failure and overflow, speech revocation,
 * teardown) have no automated test; docs/safety.md lists them as known gaps.
 */
class AuditRegressionTest {
    private class Rig(
        val engine: TurnEngine,
        val speech: FakeSpeech,
        val lm: FakeLm,
        val vehicle: FaultInjectingGateway,
        val trace: RecordingTraceSink,
        var state: DrivingState,
        val scope: TestScope,
    ) {
        suspend fun say(
            text: String,
            confidence: Float = 0.9f,
            deliver: Boolean = true,
            startMs: Long? = null,
            sttMs: Long = 0,
        ): TurnResult {
            speech.queue(text, confidence, delayMs = sttMs)
            val end = scope.testScheduler.currentTime
            val r = engine.handle(FloatArray(0), InputSource.CLIP, end, startMs ?: end)
            if (deliver) r.confirmationId?.let { engine.confirmationDelivered(it) }
            return r
        }

        fun temp() =
            (
                runCatching {
                    kotlinx.coroutines.runBlocking { vehicle.read(ClimateProperty.CABIN_TEMPERATURE_C) }
                }.getOrNull() as? ReadResult.Value
            )?.value
    }

    private fun TestScope.rig(state: DrivingState): Rig {
        val clock = MonotonicClock { testScheduler.currentTime * 1_000_000 }
        val speech = FakeSpeech()
        val lm = FakeLm()
        val vehicle = FaultInjectingGateway(SimulatedVehicleGateway(clock))
        val trace = RecordingTraceSink()
        lateinit var rig: Rig
        val engine =
            TurnEngine(
                speech = speech,
                rules = RuleInterpreter(),
                lm = LmInterpreter(lm),
                policy = Policy(),
                vehicle = vehicle,
                drivingState = { rig.state },
                clock = clock,
                host = HostInfo("test", true, "arm64-v8a"),
                traceSink = trace,
            )
        rig = Rig(engine, speech, lm, vehicle, trace, state, this)
        return rig
    }

    // --- #1 negation, questions, unsupported targets -----------------------------------------

    @Test
    @Verifies("SR-1")
    fun `audit 1 - negated, interrogative and unsupported-target requests never act`() =
        runTest {
            val r = rig(DrivingState.PARKED)
            for (text in listOf(
                "don't make it warmer",
                "do not turn on the AC",
                "is the fan off?",
                "is the defrost on",
                "make my seat warmer",
                "turn on the heated seats",
                "turn on the AC and turn off the defrost",
                "can you make it warmer?",
            )) {
                val result = r.say(text)
                (result.outcome in setOf(Outcome.REFUSED, Outcome.ANSWERED)) shouldBe true
            }
            r.vehicle.writeCount shouldBe 0
            r.lm.callCount shouldBe 0
        }

    @Test
    @Verifies("SR-1")
    fun `audit 1 - the rules reject rather than match`() {
        val rules = RuleInterpreter()
        rules.interpret("don't make it warmer").shouldBeInstanceOf<RuleResult.Rejected>()
        rules.interpret("is the fan off?").shouldBeInstanceOf<RuleResult.Rejected>()
        rules.interpret("make my seat warmer").shouldBeInstanceOf<RuleResult.Rejected>()
        // Still fine: plain requests, supported queries, defrost on a window.
        rules.interpret("make it warmer") shouldBe RuleResult.Matched(Command.AdjustTemp(+1))
        rules.interpret("what's the temperature?") shouldBe RuleResult.Matched(Command.QueryCabin)
        rules.interpret("defrost the rear window") shouldBe
            RuleResult.Matched(Command.SetDefrost(io.github.ardaulas.earshot.core.command.Window.REAR, true))
    }

    // --- #2 signed and fractional numbers ----------------------------------------------------

    @Test
    @Verifies("SR-3")
    fun `audit 2 - negative and fractional values are out of range, not their absolute or truncated value`() {
        val rules = RuleInterpreter()
        for (text in listOf("set temperature to -21", "set the temperature to minus 21", "set temperature to 28.5", "fan to 2.5")) {
            rules.interpret(text).shouldBeInstanceOf<RuleResult.OutOfRange>()
        }
        rules.interpret("set the temperature to 21 fahrenheit").shouldBeInstanceOf<RuleResult.Rejected>()
    }

    // --- #3 an unrelated request ends a pending confirmation --------------------------------

    @Test
    @Verifies("SR-18", "SR-8")
    fun `audit 3 - defrost off, then an out-of-range request, then yes, does not turn defrost off`() =
        runTest {
            val r = rig(DrivingState.MOVING)
            r.vehicle.write(ClimateProperty.FRONT_DEFROST, 1)
            r.say("turn off the defrost").outcome shouldBe Outcome.CONFIRMATION_REQUESTED
            r.say("set temperature to 35").outcome shouldBe Outcome.OUT_OF_RANGE
            val yes = r.say("yes")
            yes.outcome shouldBe Outcome.REFUSED
            yes.spoken shouldBe Responses.NOTHING_PENDING
            (r.vehicle.read(ClimateProperty.FRONT_DEFROST) as ReadResult.Value).value shouldBe 1
        }

    @Test
    @Verifies("SR-18")
    fun `audit 3 - two unclear answers stop the exchange and end the confirmation`() =
        runTest {
            val r = rig(DrivingState.MOVING)
            r.vehicle.write(ClimateProperty.FRONT_DEFROST, 1)
            r.say("turn off the defrost")
            r.say("yes", confidence = 0.1f).outcome shouldBe Outcome.REPROMPTED
            r.say("yes", confidence = 0.1f).outcome shouldBe Outcome.STOPPED
            r.engine.isAwaitingConfirmation shouldBe false
            r.say("yes").outcome shouldBe Outcome.REFUSED
            (r.vehicle.read(ClimateProperty.FRONT_DEFROST) as ReadResult.Value).value shouldBe 1
        }

    // --- #4 / #5 confirmation needs delivery, and an answer that started afterwards -----------

    @Test
    @Verifies("SR-18")
    fun `audit 4 - a question that was never delivered cannot be confirmed`() =
        runTest {
            val r = rig(DrivingState.MOVING)
            r.vehicle.write(ClimateProperty.FRONT_DEFROST, 1)
            val q = r.say("turn off the defrost", deliver = false)
            q.confirmationId shouldBe q.confirmationId!!
            r.say("yes").outcome shouldBe Outcome.EXPIRED
            (r.vehicle.read(ClimateProperty.FRONT_DEFROST) as ReadResult.Value).value shouldBe 1
        }

    @Test
    @Verifies("SR-18")
    fun `audit 4 - delivery failure cancels the confirmation`() =
        runTest {
            val r = rig(DrivingState.MOVING)
            val q = r.say("turn off the defrost", deliver = false)
            r.engine.confirmationFailed(q.confirmationId!!)
            r.engine.isAwaitingConfirmation shouldBe false
            r.say("yes").outcome shouldBe Outcome.REFUSED
            r.vehicle.writeCount shouldBe 0
        }

    @Test
    @Verifies("SR-18")
    fun `audit 4 - the answering window starts at delivery, not when the question was decided`() =
        runTest {
            val r = rig(DrivingState.MOVING)
            val q = r.say("turn off the defrost", deliver = false)
            testScheduler.advanceTimeBy(8_000) // slow speech of the question
            r.engine.confirmationDelivered(q.confirmationId!!)
            testScheduler.advanceTimeBy(5_000)
            r.say("yes").outcome shouldBe Outcome.ACTED
        }

    @Test
    @Verifies("SR-18")
    fun `audit 5 - an answer that started before the question was delivered does not count`() =
        runTest {
            val r = rig(DrivingState.MOVING)
            val q = r.say("turn off the defrost", deliver = false)
            val recordedBefore = testScheduler.currentTime
            testScheduler.advanceTimeBy(500)
            r.engine.confirmationDelivered(q.confirmationId!!)
            testScheduler.advanceTimeBy(500)
            r.say("yes", startMs = recordedBefore).outcome shouldBe Outcome.EXPIRED
            r.vehicle.writeCount shouldBe 0
        }

    // --- #6 invalid and discontinuous signals ------------------------------------------------

    @Test
    @Verifies("SR-6")
    fun `audit 6 - negative, NaN, infinite, future and gapped signals never resolve to parked`() {
        fun resolver() = DrivingStateResolver(staleAfterMs = 1_000, parkedAfterMs = 2_000)
        resolver().apply { update(SignalSample(-5.0, Gear.PARK, 0), 0) }.current(0) shouldBe DrivingState.UNKNOWN
        resolver().apply { update(SignalSample(Double.NaN, Gear.PARK, 0), 0) }.current(0) shouldBe DrivingState.UNKNOWN
        resolver().apply { update(SignalSample(Double.POSITIVE_INFINITY, Gear.NEUTRAL, 0), 0) }.current(0) shouldBe DrivingState.UNKNOWN
        resolver().apply { update(SignalSample(0.0, Gear.PARK, 5_000), 5_000) }.current(0) shouldBe DrivingState.UNKNOWN
        // Negative speed in neutral for a long time: never parked.
        resolver().apply { for (t in 0L..5_000L step 200) update(SignalSample(-1.0, Gear.NEUTRAL, t), t) }.current(5_000) shouldBe
            DrivingState.UNKNOWN
        // Zero at 0 s, a 10 s gap, zero again: not "continuously" stopped.
        resolver()
            .apply {
                update(SignalSample(0.0, Gear.NEUTRAL, 0), 0)
                update(SignalSample(0.0, Gear.NEUTRAL, 10_000), 10_000)
            }.current(10_000) shouldBe DrivingState.MOVING
        // A NaN in the middle resets the stationary history.
        resolver()
            .apply {
                for (t in 0L..1_800L step 200) update(SignalSample(0.0, Gear.NEUTRAL, t), t)
                update(SignalSample(Double.NaN, Gear.NEUTRAL, 2_000), 2_000)
                update(SignalSample(0.0, Gear.NEUTRAL, 2_200), 2_200)
            }.current(2_200) shouldBe DrivingState.MOVING
    }

    // --- #7 driving state re-checked at the write -------------------------------------------

    @Test
    @Verifies("SR-8")
    fun `audit 7 - fan off decided while parked is not written after the car starts moving`() =
        runTest {
            val r = rig(DrivingState.PARKED)
            r.vehicle.write(ClimateProperty.FRONT_DEFROST, 1)
            val before = r.vehicle.writeCount
            // The car pulls away between the decision and the write: the gateway's guard stops it.
            val fanBefore = (r.vehicle.read(ClimateProperty.FAN_LEVEL) as ReadResult.Value).value
            r.vehicle.onWrite = { r.state = DrivingState.MOVING }
            val result = r.say("fan off")
            result.outcome shouldBe Outcome.DISCARDED_STATE_CHANGED
            r.vehicle.writeCount shouldBe before + 1
            r.vehicle.onWrite = null
            (r.vehicle.read(ClimateProperty.FAN_LEVEL) as ReadResult.Value).value shouldBe fanBefore
        }

    @Test
    @Verifies("SR-4")
    fun `audit 7 - no climate panel if the car moved before it would be shown`() =
        runTest {
            val r = rig(DrivingState.PARKED)
            r.vehicle.onRead = { r.state = DrivingState.MOVING }
            val result = r.say("show me my climate settings")
            result.screen shouldBe null
        }

    // --- #8 the deadline holds at the write ----------------------------------------------------

    @Test
    @Verifies("SR-7")
    fun `audit 8 - a slow read cannot push a write past the 5 s budget`() =
        runTest {
            val r = rig(DrivingState.PARKED)
            val before = r.vehicle.writeCount
            r.vehicle.readDelayMs = 900
            // Speech-to-text ends 4.9 s after the utterance; the temperature read takes 0.9 s more.
            val result = r.say("make it warmer", sttMs = 4_900)
            result.outcome shouldBe Outcome.DISCARDED_STALE
            r.vehicle.writeCount shouldBe before
        }

    // --- #16 trace failure --------------------------------------------------------------------

    @Test
    @Verifies("SR-15", "SR-21", "SR-24")
    fun `audit 16 - a trace storage failure does not hide an executed action`() =
        runTest {
            val r = rig(DrivingState.PARKED)
            r.trace.fail = true
            val result = r.say("set the temperature to 19")
            result.outcome shouldBe Outcome.ACTED
            result.spoken shouldBe Responses.temperatureNow(19)
            result.traceError shouldBe "IOException"
        }

    // --- re-audit round 2 -----------------------------------------------------------------------

    @Test
    @Verifies("SR-1")
    fun `re-audit 1 - conjunctions, stray objects and curly apostrophes never act`() {
        val rules = RuleInterpreter()
        for (text in listOf(
            "make it warmer then cooler",
            "the pizza should be warmer",
            "don\u2019t make it warmer",
            "make it warmer and turn on the ac",
        )) {
            rules.interpret(text).shouldBeInstanceOf<RuleResult.Rejected>()
        }
    }

    @Test
    @Verifies("SR-3")
    fun `re-audit 2 - fractions and abbreviated units are not whole Celsius values`() {
        val rules = RuleInterpreter()
        rules.interpret("set temperature to 21/2").shouldBeInstanceOf<RuleResult.OutOfRange>()
        rules.interpret("set temperature to 21\u00B0F").shouldBeInstanceOf<RuleResult.Rejected>()
        rules.interpret("set temperature to 21 F").shouldBeInstanceOf<RuleResult.Rejected>()
        rules.interpret("set the temperature to 21\u00B0C") shouldBe RuleResult.Matched(Command.SetTemp(21))
    }

    @Test
    @Verifies("SR-18", "SR-8")
    fun `re-audit 3 - an unclear unrelated request ends a pending confirmation`() =
        runTest {
            val r = rig(DrivingState.MOVING)
            r.vehicle.write(ClimateProperty.FRONT_DEFROST, 1)
            r.say("turn off the defrost")
            r.say("set temperature to 35", confidence = 0.1f).outcome shouldBe Outcome.REPROMPTED
            r.say("yes").outcome shouldBe Outcome.REFUSED
            (r.vehicle.read(ClimateProperty.FRONT_DEFROST) as ReadResult.Value).value shouldBe 1
        }

    @Test
    @Verifies("SR-18")
    fun `re-audit 3 - an unclear yes keeps the question open for one clear answer`() =
        runTest {
            val r = rig(DrivingState.MOVING)
            r.say("turn off the defrost")
            r.say("yes", confidence = 0.1f).outcome shouldBe Outcome.REPROMPTED
            r.say("yes").outcome shouldBe Outcome.ACTED
        }

    @Test
    @Verifies("SR-18")
    fun `re-audit 3 - an utterance the app rejected without handling ends the confirmation`() =
        runTest {
            val r = rig(DrivingState.MOVING)
            r.say("turn off the defrost")
            r.engine.abandonConfirmation()
            r.say("yes").outcome shouldBe Outcome.REFUSED
            r.vehicle.writeCount shouldBe 0
        }

    @Test
    @Verifies("SR-8")
    fun `re-audit 7 - the car moving just before the write still stops fan off`() =
        runTest {
            val r = rig(DrivingState.PARKED)
            r.vehicle.write(ClimateProperty.FRONT_DEFROST, 1)
            val before = r.vehicle.writeCount
            r.vehicle.onWrite = { r.state = DrivingState.MOVING }
            r.say("fan off").outcome shouldBe Outcome.DISCARDED_STATE_CHANGED
            r.vehicle.writeCount shouldBe before + 1
        }

    @Test
    @Verifies("SR-8")
    fun `re-audit 4 N20 - fan off while moving always asks, whatever the defrost reading`() =
        runTest {
            val r = rig(DrivingState.MOVING)
            r.vehicle.write(ClimateProperty.FRONT_DEFROST, 0)
            r.say("fan off").outcome shouldBe Outcome.CONFIRMATION_REQUESTED
        }

    @Test
    @Verifies("SR-2")
    fun `re-audit 4 N18 - words in brackets are kept, only known non-speech tags go`() {
        io.github.ardaulas.earshot.core.speech.AudioGate
            .clean("Turn off the defrost. (No, cancel that.)") shouldBe "Turn off the defrost. No, cancel that."
        RuleInterpreter()
            .interpret(
                io.github.ardaulas.earshot.core.speech.AudioGate
                    .clean("Turn off the defrost. (No, cancel that.)"),
            ).shouldBeInstanceOf<RuleResult.Rejected>()
        io.github.ardaulas.earshot.core.speech.AudioGate
            .clean("(don't) make it warmer") shouldBe "don't make it warmer"
    }

    @Test
    @Verifies("SR-6")
    fun `re-audit N1 - a park gear without a readable speed is not parked`() {
        DrivingStateResolver().apply { update(SignalSample(null, Gear.PARK, 0), 0) }.current(0) shouldBe DrivingState.UNKNOWN
        DrivingStateResolver().apply { update(SignalSample(0.0, Gear.PARK, 0), 0) }.current(0) shouldBe DrivingState.PARKED
    }

    // --- Pre-review of round 2 (2026-09-30) ---------------------------------------------------

    @Test
    @Verifies("SR-1")
    fun `pre-review F1 - conflicting directions and complaints never change the temperature`() =
        runTest {
            val r = rig(DrivingState.MOVING)
            for (text in listOf(
                "make it warmer cooler",
                "make it cooler, warmer",
                "it's colder in here",
                "its hotter in here",
                "i like it cooler",
            )) {
                r.say(text).outcome shouldBe Outcome.REFUSED
            }
            r.vehicle.writeCount shouldBe 0
            r.lm.callCount shouldBe 0
        }

    @Test
    @Verifies("SR-1")
    fun `pre-review F2 - a second request without a conjunction is refused, not dropped`() =
        runTest {
            val r = rig(DrivingState.MOVING)
            for (text in listOf("turn on the defrost fan off", "turn the ac on fan off", "fan off ac on", "temperature 22 fan 3")) {
                r.say(text).outcome shouldBe Outcome.REFUSED
            }
            r.vehicle.writeCount shouldBe 0
        }

    @Test
    @Verifies("SR-3")
    fun `pre-review F3 - a relative word with a number is a change by that number, never a set-point`() {
        val rules = RuleInterpreter()
        rules.interpret("make it 17 degrees warmer") shouldBe RuleResult.OutOfRange("temperature change", Bounds.TEMP_DELTA)
        rules.interpret("make it 18 degrees cooler") shouldBe RuleResult.OutOfRange("temperature change", Bounds.TEMP_DELTA)
        rules.interpret("increase the temperature by 3") shouldBe RuleResult.Matched(Command.AdjustTemp(3))
        rules.interpret("lower the temperature by 4") shouldBe RuleResult.Matched(Command.AdjustTemp(-4))
        rules.interpret("make it warmer by 3") shouldBe RuleResult.Matched(Command.AdjustTemp(3))
        rules.interpret("make it 2 degrees cooler") shouldBe RuleResult.Matched(Command.AdjustTemp(-2))
        rules.interpret("turn the heat up to 25").shouldBeInstanceOf<RuleResult.Rejected>()
    }

    @Test
    @Verifies("SR-3")
    fun `pre-review F4 - extra numbers, symbols and dashes never become a valid value`() {
        val rules = RuleInterpreter()
        for (text in listOf(
            "set the temperature to 21 5",
            "set the temperature to twenty one five",
            "set the temperature to 20 20",
            "set the temperature to 21\u00bd",
            "set fan to 3\u00bd",
            "set the temperature to 21 \u2109",
            "set the temperature to 21-22",
        )) {
            (rules.interpret(text) is RuleResult.Matched) shouldBe false
        }
        for (text in listOf("set the temperature to \u201321", "set temperature to-21", "set the temperature to \uFF0D21")) {
            rules.interpret(text) shouldBe RuleResult.OutOfRange("temperature", Bounds.TEMP_C)
        }
    }

    @Test
    @Verifies("SR-1")
    fun `unsupported features are refused before the language model, windows stay open to it`() =
        runTest {
            val r = rig(DrivingState.PARKED)
            for (text in listOf("Turn on the seat heater", "Open the sunroof", "Lock the doors")) {
                r.say(text).outcome shouldBe Outcome.REFUSED
            }
            r.lm.callCount shouldBe 0
            RuleInterpreter().interpret("I can't see out the back window") shouldBe RuleResult.NoMatch
        }

    @Test
    @Verifies("SR-6", "SR-22")
    fun `pre-review F5 - zero speed with no gear reading is never parked`() {
        val resolver = DrivingStateResolver()
        for (t in 0L..3_000L step 200) resolver.update(SignalSample(0.0, null, t), t)
        resolver.current(3_000) shouldBe DrivingState.UNKNOWN
    }

    @Test
    @Verifies("SR-3")
    fun `pre-review F8 - a relative change from outside the range never writes, in either direction`() =
        runTest {
            val r = rig(DrivingState.MOVING)
            r.vehicle.readOverride[ClimateProperty.CABIN_TEMPERATURE_C] = 30
            r.say("make it warmer").outcome shouldBe Outcome.NO_CHANGE
            r.say("make it cooler").outcome shouldBe Outcome.NO_CHANGE
            r.vehicle.readOverride[ClimateProperty.CABIN_TEMPERATURE_C] = 12
            r.say("make it cooler").outcome shouldBe Outcome.NO_CHANGE
            r.vehicle.writeCount shouldBe 0
        }

    @Test
    fun `the language model gets the words without trailing punctuation, as it was evaluated`() {
        val lm = LmInterpreter(FakeLm())
        lm.forModel("It's really stuffy in here.") shouldBe "It's really stuffy in here"
        lm.forModel("  I'm  freezing!? ") shouldBe "I'm freezing"
    }

    @Test
    @Verifies("SR-1")
    fun `negated requests with heater, hot or cold are refused by the rules, not sent to the language model (harness finding)`() =
        runTest {
            val r = rig(DrivingState.PARKED)
            for (text in listOf("Don't make it hot", "Don't turn on the heater", "Do not turn the heater off", "don't make it cold")) {
                r.say(text).outcome shouldBe Outcome.REFUSED
            }
            r.lm.callCount shouldBe 0
            r.vehicle.writeCount shouldBe 0
        }

    @Test
    @Verifies("SR-8")
    fun `re-audit 3 N12 - a write that reaches the vehicle after the car started moving does nothing`() =
        runTest {
            val r = rig(DrivingState.PARKED)
            r.vehicle.write(ClimateProperty.FRONT_DEFROST, 1)
            // The policy and the re-check see PARKED; by the time the queued write runs, the car moves.
            r.vehicle.onWrite = { r.state = DrivingState.MOVING }
            r.say("turn off the defrost").outcome shouldBe Outcome.DISCARDED_STATE_CHANGED
            r.vehicle.onWrite = null
            (r.vehicle.read(ClimateProperty.FRONT_DEFROST) as ReadResult.Value).value shouldBe 1
        }

    @Test
    @Verifies("SR-1", "SR-3")
    fun `re-audit 3 1 and 2 - two zones, an unsupported zone, a misplaced unit or a hidden sign never act`() {
        val rules = RuleInterpreter()
        for (text in listOf(
            "Turn off the front defrost; turn off the rear defrost",
            "Set the rear temperature to 21",
            "turn on the front ac",
            "Fan to 3 celsius",
            "set the fan to 2 degrees",
        )) {
            rules.interpret(text).shouldBeInstanceOf<RuleResult.Rejected>()
        }
        for (text in listOf("Set temperature to .21", "set temperature to -twenty one")) {
            rules.interpret(text) shouldBe RuleResult.OutOfRange("temperature", Bounds.TEMP_C)
        }
        // Still fine: one window, and a temperature with its unit.
        rules.interpret("turn off the rear defrost") shouldBe
            RuleResult.Matched(Command.SetDefrost(io.github.ardaulas.earshot.core.command.Window.REAR, false))
        rules.interpret("defrost the rear windshield") shouldBe
            RuleResult.Matched(Command.SetDefrost(io.github.ardaulas.earshot.core.command.Window.REAR, true))
        rules.interpret("set the temperature to 21 degrees celsius") shouldBe RuleResult.Matched(Command.SetTemp(21))
    }

    @Test
    @Verifies("SR-1", "SR-3")
    fun `re-audit 4 1 and 2 - one feature asked twice, extra sentences, a joined minus or a leading comma never act`() {
        val rules = RuleInterpreter()
        for (text in listOf(
            "Set the fan to 3; turn the fan off",
            "Set the fan to 3. Turn the fan off.",
            "Turn off the defrost. No, cancel that.",
            "set the fan to 3 set the fan to 1",
            "fan up fan down",
        )) {
            (rules.interpret(text) is RuleResult.Matched) shouldBe false
        }
        for (text in listOf("Set temperature to\u2212twenty one", "Set temperature to ,21", "set temperature to-twenty one")) {
            rules.interpret(text) shouldBe RuleResult.OutOfRange("temperature", Bounds.TEMP_C)
        }
        rules.interpret("Set the temperature to twenty-one.") shouldBe RuleResult.Matched(Command.SetTemp(21))
        rules.interpret("Turn on the A.C. please") shouldBe RuleResult.Matched(Command.SetAc(true))
    }

    @Test
    @Verifies("SR-6")
    fun `re-audit 4 6 - a future reading is dropped, so later valid readings still count`() {
        val resolver = DrivingStateResolver()
        resolver.update(SignalSample(0.0, Gear.PARK, 5_000), nowMs = 0)
        for (t in 1_000L..4_800L step 200) resolver.update(SignalSample(30.0, Gear.DRIVE, t), nowMs = t)
        resolver.current(5_000) shouldBe DrivingState.MOVING
    }

    @Test
    @Verifies("SR-1", "SR-3")
    fun `a second value for the same feature is refused, never dropped (pre-review of re-audit 5)`() {
        val rules = RuleInterpreter()
        for (text in listOf(
            "fan to 3 off",
            "fan at level 3 off",
            "fan 3 max",
            "fan to 2 full",
            "max fan 2",
            "turn the fan off 3",
            "turn off the ac at 5",
            "set temperature to 21-",
            "set temperature to \u02D721",
        )) {
            (rules.interpret(text) is RuleResult.Matched) shouldBe false
        }
        rules.interpret("fan to 3") shouldBe RuleResult.Matched(Command.SetFan(3))
        rules.interpret("turn the fan to max") shouldBe RuleResult.Matched(Command.SetFan(Bounds.FAN_LEVEL.last))
        io.github.ardaulas.earshot.core.speech.AudioGate
            .clean("Turn on the ac (just kidding music playing)") shouldBe "Turn on the ac just kidding music playing"
    }
}
