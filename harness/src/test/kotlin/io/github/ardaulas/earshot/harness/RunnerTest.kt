package io.github.ardaulas.earshot.harness

import io.github.ardaulas.earshot.core.command.Command
import io.github.ardaulas.earshot.core.interpret.LmInterpreter
import io.github.ardaulas.earshot.core.interpret.LmWireFormat
import io.github.ardaulas.earshot.core.policy.DrivingState
import io.github.ardaulas.earshot.core.policy.Policy
import io.github.ardaulas.earshot.core.policy.PolicyInput
import io.github.ardaulas.earshot.core.policy.Verdict
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RunnerTest {
    private val suite = Fixtures.suite

    private fun run(
        source: TranscriptSource = ReferenceSource(),
        lm: ConstantLm? = null,
        decide: ((Policy, PolicyInput) -> Verdict)? = null,
    ): GroupResult {
        val policy = Policy(suite.thresholds.confidence)
        val pipeline =
            Pipeline(
                policy,
                lm = lm?.let { LmInterpreter(it) },
                frontDefrostOn = suite.frontDefrostOn,
                decideFn = decide?.let { d -> { input: PolicyInput -> d(policy, input) } } ?: policy::decide,
            )
        val runner = Runner(suite, source, pipeline, if (lm == null) "none" else "fake")
        val (conditions, skipped) = runner.plan()
        conditions shouldContainExactly listOf(Condition.TEXT)
        skipped shouldBe emptyList()
        return runner.run(Condition.TEXT)
    }

    @Test
    fun `reference transcripts meet every label in every driving state`() {
        val g = run()
        g.clips shouldBe suite.clips.size
        g.wer shouldBe 0.0
        g.intentAccuracy shouldBe 1.0
        g.slotAccuracy shouldBe 1.0
        g.decisions shouldBe suite.clips.size * 3
        g.policyScored shouldBe g.decisions
        g.policyCorrect shouldBe g.decisions
        g.scoredBy shouldBe mapOf("label" to g.decisions, "unclear" to 0, "table" to 0)
        g.falseActions shouldBe 0
        g.engineErrors shouldBe 0
        g.turnEngineMismatches shouldBe 0
        g.confusion shouldBe emptyList()
        // Core's TurnEngine wrote exactly where a label allows a write command.
        g.writes shouldBe 7
    }

    @Test
    fun `with the language model, a fallback clip is confirmed and never written`() {
        val lm = ConstantLm(LmWireFormat.wire(LmWireFormat.Intent.WARMER))
        val g = run(lm = lm)
        // "I'm freezing", "Play some jazz" and the mumble miss the rules. "Don't make it warmer" is
        // refused by the rules themselves and never reaches the model.
        lm.calls shouldBe 3
        val negated = g.results.single { it.id == "fx-009" }
        negated.commandSource shouldBe "RULES"
        negated.rule shouldBe "Rejected(negated)"
        negated.decisions.map { it.actual } shouldBe List(3) { "Refuse(OUT_OF_DOMAIN)" }
        val freezing = g.results.single { it.id == "fx-006" }
        freezing.predicted shouldBe "AdjustTemp(delta=2)"
        freezing.commandSource shouldBe "LM"
        freezing.decisions.map { it.actual } shouldBe listOf("Confirm", "Confirm", "Confirm")
        freezing.decisions.all { it.correct == true } shouldBe true
        // The jazz request became "warmer": a wrong command, but only ever a question, not a write.
        val jazz = g.results.single { it.id == "fx-004" }
        jazz.slotCorrect shouldBe false
        jazz.decisions.none { it.falseAction } shouldBe true
        g.falseActions shouldBe 0
        g.policyCorrect shouldBe g.policyScored
    }

    @Test
    fun `a misheard value that is allowed counts as a false action in every state it is written`() {
        val g = run(ScriptedSource(mapOf("fx-001" to ("set the temperature to 25" to 0.9f))))
        val clip = g.results.single { it.id == "fx-001" }
        clip.predicted shouldBe "SetTemp(celsius=25)"
        clip.intentCorrect shouldBe true
        clip.slotCorrect shouldBe false
        clip.decisions.map { it.falseAction } shouldBe listOf(true, true, true)
        // Not the labelled command: scored against the policy table for SetTemp(25), which allows it.
        clip.decisions.map { it.basis } shouldBe List(3) { "table" }
        clip.decisions.all { it.correct && it.write } shouldBe true
        g.falseActions shouldBe 3
        // "21 degrees" -> "25": one substitution, one deletion.
        g.wer shouldBe (2.0 / suite.clips.sumOf { Wer.words(it.transcript).size })
        g.confusion shouldBe listOf(ConfusionCell("SetTemp", "SetTemp", 1))
    }

    @Test
    fun `a misheard command the language model recovers is scored against a confirmation`() {
        // Found in the SNR 20 dB run: "Defrost the windshield" heard as "Frost the windshield" and "What
        // gear am I in?" as "What year am I in?". The rules miss, the model gets the command right, and
        // the policy asks first because it came from the model. That is the correct verdict, not a
        // policy error against the label's Allow.
        val lm = ConstantLm(LmWireFormat.wire(LmWireFormat.Intent.QUERY_SPEED))
        val g = run(ScriptedSource(mapOf("fx-007" to ("are we going quickly" to 0.9f))), lm)
        val clip = g.results.single { it.id == "fx-007" }
        clip.rule shouldBe "NoMatch"
        clip.commandSource shouldBe "LM"
        clip.slotCorrect shouldBe true
        clip.decisions.map { it.actual } shouldBe listOf("Confirm", "Confirm", "Confirm")
        clip.decisions.all { it.basis == "label" && it.correct } shouldBe true
        g.turnEngineMismatches shouldBe 0
        g.falseActions shouldBe 0
    }

    @Test
    fun `an unclear transcript is scored against a re-prompt and is not a false action`() {
        val g = run(ScriptedSource(mapOf("fx-001" to ("set the temperature to 25" to 0.2f))))
        val clip = g.results.single { it.id == "fx-001" }
        clip.confident shouldBe false
        clip.decisions.map { it.actual } shouldBe listOf("Reprompt", "Reprompt", "Reprompt")
        clip.decisions.all { it.basis == "unclear" && it.expected == "Reprompt" && it.correct } shouldBe true
        clip.decisions.none { it.falseAction || it.write } shouldBe true
        g.unconfident shouldBe 1
        g.falseActions shouldBe 0
        g.policyCorrect shouldBe g.policyScored
    }

    @Test
    fun `SG-1 regression - a policy that acts on an unconfident transcript fails the gates`() {
        // The review's repro: a policy change that allows a low-confidence SetTemp(21). The transcript
        // is even the right one, so the label alone would accept the verdict; the unclear basis and the
        // false-action rule for unconfident transcripts must not.
        val allowUnclear = { policy: Policy, input: PolicyInput ->
            if (!policy.isConfident(input.confidence) && input.command is Command.SetTemp) Verdict.Allow else policy.decide(input)
        }
        val source = ScriptedSource(mapOf("fx-001" to ("Set the temperature to 21 degrees" to 0.3f)))
        val broken = run(source, decide = allowUnclear)
        val clip = broken.results.single { it.id == "fx-001" }
        clip.slotCorrect shouldBe true
        clip.decisions.map { it.actual } shouldBe List(3) { "Allow" }
        clip.decisions.all { it.basis == "unclear" && !it.correct && it.falseAction } shouldBe true
        // Core's TurnEngine (with the real policy) re-prompts: the difference is reported too.
        clip.decisions.all { it.turnEngineMismatch!!.startsWith("TurnEngine: verdict Reprompt") } shouldBe true

        val good = run(source)
        good.policyCorrect shouldBe good.policyScored
        good.falseActions shouldBe 0
        val c = Gates.compare(Baseline.from(report(good)), report(broken))
        c.passed shouldBe false
        c.gates.filter { !it.passed }.map { it.gate } shouldBe
            listOf("policy correctness 100% of all decisions", "no false actions", "TurnEngine agrees")
    }

    @Test
    fun `an unclear cancel is expected to stop`() {
        val g = run(ScriptedSource(mapOf("fx-004" to ("cancel" to 0.2f))))
        val clip = g.results.single { it.id == "fx-004" }
        clip.decisions.all { it.basis == "unclear" && it.actual == "Stop" && it.correct } shouldBe true
    }

    @Test
    fun `a misheard non-write is scored against the policy table, not skipped`() {
        // "Play some jazz" (a rejection) misheard as "show the climate settings": no label speaks for
        // ShowClimate here, so the policy table does. A policy that showed the screen while moving is caught.
        val g = run(ScriptedSource(mapOf("fx-004" to ("show the climate settings" to 0.9f))))
        val clip = g.results.single { it.id == "fx-004" }
        clip.predicted shouldBe "ShowClimate"
        clip.decisions.map { it.basis } shouldBe List(3) { "table" }
        clip.decisions.map { it.expected } shouldBe listOf("Allow", "Refuse(SCREEN_WHILE_MOVING)", "Refuse(SCREEN_WHILE_MOVING)")
        clip.decisions.all { it.correct } shouldBe true
        val lax =
            run(
                ScriptedSource(mapOf("fx-004" to ("show the climate settings" to 0.9f))),
                decide = { p, input -> if (input.command == Command.ShowClimate) Verdict.Allow else p.decide(input) },
            )
        // Two table-scored errors here, and two label-scored ones on fx-003 ("Show the climate settings").
        lax.policyCorrect shouldBe lax.policyScored - 4
        lax.turnEngineMismatches shouldBe 4
    }

    @Test
    fun `an unclear out-of-range value goes to the policy as out of domain`() {
        val g = run(ScriptedSource(mapOf("fx-005" to ("set the temperature to 40" to 0.2f))))
        g.results
            .single { it.id == "fx-005" }
            .decisions
            .map { it.actual } shouldBe listOf("Reprompt", "Reprompt", "Reprompt")
    }

    @Test
    fun `a negated request misheard without the negation is a false action`() {
        // The audit's first scenario: if speech-to-text drops the "don't", the harness must show it.
        val g = run(ScriptedSource(mapOf("fx-009" to ("make it warmer" to 0.9f))))
        val clip = g.results.single { it.id == "fx-009" }
        clip.intentCorrect shouldBe false
        clip.decisions.map { it.actual } shouldBe listOf("Allow", "AllowVoiceOnly", "AllowVoiceOnly")
        clip.decisions.map { it.falseAction } shouldBe listOf(true, true, true)
        g.falseActions shouldBe 3
    }

    @Test
    fun `signed and fractional values are out of range, never a valid temperature`() {
        for (text in listOf("set the temperature to -21", "set the temperature to minus 21", "set the temperature to 21.5")) {
            val g = run(ScriptedSource(mapOf("fx-005" to (text to 1.0f))))
            val clip = g.results.single { it.id == "fx-005" }
            clip.predicted shouldBe "OutOfRange"
            clip.decisions.all { it.correct == true && !it.falseAction } shouldBe true
        }
    }

    @Test
    fun `a mumble may be re-prompted or refused`() {
        val unclear = run(ScriptedSource(mapOf("fx-010" to ("mm the uh fan hmm" to 0.9f))))
        unclear.results
            .single { it.id == "fx-010" }
            .decisions
            .all { it.correct == true } shouldBe true
        // With a low confidence the policy re-prompts: no action, and not scored.
        val low = run(ScriptedSource(mapOf("fx-010" to ("mm the uh fan hmm" to 0.1f))))
        val decisions = low.results.single { it.id == "fx-010" }.decisions
        decisions.map { it.actual } shouldBe List(3) { "Reprompt" }
        decisions.all { it.basis == "unclear" && it.correct } shouldBe true
        low.falseActions shouldBe 0
    }

    @Test
    fun `the car suite passes on its reference transcripts`() {
        val car =
            TestSetLoader.load(
                java.nio.file.Paths
                    .get(System.getProperty("user.dir"))
                    .resolve("../testset/car")
                    .normalize(),
            )
        val pipeline = Pipeline(Policy(car.thresholds.confidence), frontDefrostOn = car.frontDefrostOn)
        val g = Runner(car, ReferenceSource(), pipeline, "none").run(Condition.TEXT)
        g.intentAccuracy shouldBe 1.0
        g.slotAccuracy shouldBe 1.0
        g.policyScored shouldBe g.decisions
        g.policyCorrect shouldBe g.decisions
        g.falseActions shouldBe 0
        g.turnEngineMismatches shouldBe 0
        g.wrongConfirmations shouldBe 0
    }

    @Test
    fun `the harness policy table agrees with every car label`() {
        // The table scores what labels cannot; on the labels' own transcripts both must say the same.
        val car = TestSetLoader.load(carDir())
        val pipeline = Pipeline(Policy(car.thresholds.confidence), frontDefrostOn = car.frontDefrostOn)
        for (clip in car.clips) {
            val i = pipeline.interpret(clip.transcript, 1f)
            for (state in DrivingState.entries) {
                val accepted = Scoring.acceptedVerdicts(clip.expected, clip.policy.getValue(state), false, i.source, i.predicted)
                val table = PolicyTable.expected(i.command, i.source, state, true, car.frontDefrostOn)
                withClue("${clip.id} $state") { Scoring.verdictAccepted(accepted, table) shouldBe true }
            }
        }
    }

    @Test
    fun `core's TurnEngine agrees with the pipeline on the car suite, with a language model`() {
        // Every fallback, rejection and mumble reaches the (fake) model's "warmer"; TurnEngine replays
        // that answer and must reach the same command, source, verdict and writes.
        val car = TestSetLoader.load(carDir())
        val lm = ConstantLm(LmWireFormat.wire(LmWireFormat.Intent.WARMER))
        val pipeline = Pipeline(Policy(car.thresholds.confidence), lm = LmInterpreter(lm), frontDefrostOn = car.frontDefrostOn)
        val g = Runner(car, ReferenceSource(), pipeline, "fake").run(Condition.TEXT)
        g.turnEngineMismatches shouldBe 0
        g.falseActions shouldBe 0
        g.policyCorrect shouldBe g.policyScored
        // "Order me a pizza" -> warmer is a question, never a write; "It's way too hot" -> warmer goes the wrong way.
        g.wrongConfirmations shouldBe g.results.count { it.commandSource == "LM" && it.predicted !in expectedOf(car, it.id) } * 3
        g.wrongDirection shouldBe 2 * 4 * 3
    }

    private fun carDir() =
        java.nio.file.Paths
            .get(System.getProperty("user.dir"))
            .resolve("../testset/car")
            .normalize()

    private fun expectedOf(
        suite: Suite,
        id: String,
    ) = Scoring.expectedCommands(suite.clips.single { it.id == id }.expected)

    private fun report(g: GroupResult) =
        Report(
            suite = suite.name,
            clips = suite.clips.size,
            sources = mapOf("synthetic" to suite.clips.size),
            labelsSha256 = suite.labelsSha256,
            host = HostLabel("os", "1", "arch", "cpu", 8, null, "17"),
            thresholds = ReportThresholds(0.5f, 2.0, 3.0),
            frontDefrost = "off",
            models = emptyList(),
            skipped = emptyList(),
            groups = listOf(g),
        )

    @Test
    fun `plan honours the condition selection`() {
        val runner = Runner(suite, ReferenceSource(), Pipeline(Policy()), "none")
        runner.plan(includeClean = false, snrs = listOf(20)).first shouldBe listOf(Condition.TEXT)
    }

    @Test
    fun `an audio source without audio files is refused before running`() {
        val source =
            object : TranscriptSource {
                override val name = "audio"
                override val needsAudio = true

                override fun transcribe(
                    clip: Clip,
                    audio: java.nio.file.Path?,
                ) = SourceTranscript("", null, null)
            }
        val runner = Runner(suite, source, Pipeline(Policy()), "none")
        val e = runCatching { runner.plan() }.exceptionOrNull()
        (e is TestSetException) shouldBe true
    }
}
