package io.github.ardaulas.earshot.harness

import io.github.ardaulas.earshot.core.interpret.LmOutcome
import io.github.ardaulas.earshot.core.policy.DrivingState
import java.nio.file.Files
import java.nio.file.Path

/** One audio condition: the reference text, the clean recording, or a noise profile at one SNR. */
data class Condition(
    val name: String,
    val noise: String? = null,
    val snrDb: Int? = null,
) {
    companion object {
        val TEXT = Condition("text")
        val CLEAN = Condition("clean")

        fun noisy(
            noise: String,
            snr: Int,
        ) = Condition("$noise@${snr}dB", noise, snr)
    }
}

class Runner(
    private val suite: Suite,
    private val source: TranscriptSource,
    private val pipeline: Pipeline,
    private val lmName: String,
    private val log: (String) -> Unit = {},
) {
    /**
     * The conditions to run and the ones skipped, with the reason. A text source runs once. An audio
     * source needs every clean file; a noisy condition with no files at all is skipped, one with only
     * some files is an error, so a half-generated set is never reported as complete. [includeClean]
     * and [snrs] select the conditions (`--snr`); a selection that leaves nothing to run is an error.
     */
    fun plan(
        includeClean: Boolean = true,
        snrs: List<Int> = suite.snrDb,
    ): Pair<List<Condition>, List<String>> {
        if (!source.needsAudio) return listOf(Condition.TEXT) to emptyList()
        val missingClean = suite.clips.filter { suite.cleanAudio(it)?.let(Files::isRegularFile) != true }
        if (missingClean.isNotEmpty()) {
            throw TestSetException(
                "${missingClean.size} clip(s) have no audio file under ${suite.audioDir}, e.g. ${missingClean.first().id}; generate the audio first",
            )
        }
        val run = if (includeClean) mutableListOf(Condition.CLEAN) else mutableListOf()
        val skipped = mutableListOf<String>()
        for (noise in suite.noiseProfiles) {
            for (snr in snrs) {
                val present = suite.clips.count { suite.noisyAudio(it, noise, snr)?.let(Files::isRegularFile) == true }
                when (present) {
                    suite.clips.size -> {
                        run += Condition.noisy(noise, snr)
                    }

                    0 -> {
                        skipped += "${source.name}/$lmName/$noise@${snr}dB: no noisy audio files"
                    }

                    else -> {
                        throw TestSetException("$noise@${snr}dB: only $present of ${suite.clips.size} noisy files exist")
                    }
                }
            }
        }
        if (run.isEmpty()) throw TestSetException("nothing to run: no audio for the selected conditions (${skipped.joinToString("; ")})")
        return run to skipped
    }

    fun run(condition: Condition): GroupResult {
        val results =
            suite.clips.mapIndexed { i, clip ->
                if (i % PROGRESS_EVERY == 0) log("${source.name}/$lmName/${condition.name}: clip ${i + 1}/${suite.clips.size}")
                runClip(clip, audioFor(clip, condition))
            }
        return aggregate(source.name, lmName, condition, results)
    }

    private fun audioFor(
        clip: Clip,
        condition: Condition,
    ): Path? =
        when {
            !source.needsAudio -> null
            condition.noise != null && condition.snrDb != null -> suite.noisyAudio(clip, condition.noise, condition.snrDb)
            else -> suite.cleanAudio(clip)
        }

    internal fun runClip(
        clip: Clip,
        audio: Path?,
    ): ClipResult {
        val transcript = source.transcribe(clip, audio)
        val interpretation = pipeline.interpret(transcript.text, transcript.confidence)
        val score = Scoring.intent(clip.expected, interpretation)
        val confident = pipeline.isConfident(transcript.confidence)
        val policyMs = mutableListOf<Double>()
        val decisions =
            DrivingState.entries.map { state ->
                val decision = pipeline.decide(interpretation, transcript.confidence, state)
                policyMs += decision.policyMs
                val observed = pipeline.turn(interpretation, transcript.text, transcript.confidence, state)
                decide(clip, interpretation, score, confident, state, decision.verdict, observed)
            }
        val errors = Wer.errors(clip.transcript, transcript.text)
        return ClipResult(
            id = clip.id,
            source = clip.source.label,
            tags = clip.tags,
            reference = clip.transcript,
            hypothesis = transcript.text,
            confidence = transcript.confidence,
            confident = confident,
            wordEdits = errors.edits,
            referenceWords = errors.referenceWords,
            expected = Scoring.expectedLabel(clip.expected) + (expectedCommand(clip.expected)?.let { " $it" } ?: ""),
            predicted = interpretation.predicted,
            rule = interpretation.ruleOutcome,
            commandSource = interpretation.source.name,
            lmOutcome = interpretation.lmOutcome?.let(::describe),
            lmError = interpretation.lmOutcome.let { it == LmOutcome.TimedOut || it is LmOutcome.Failed },
            intentCorrect = score.intent,
            slotCorrect = score.slots,
            decisions = decisions,
            error = transcript.error,
            sttMs = transcript.ms,
            rulesMs = interpretation.rulesMs,
            lmMs = interpretation.lmMs,
            policyMs = policyMs.average(),
        )
    }

    /**
     * Scores one decision. Every decision is scored, on one of three bases ([Basis]): the label's
     * verdicts when the transcript was confident and the command exactly the labelled one; the
     * re-prompt (or stop, for a cancel) that SG-1 requires when the transcript was unconfident; and
     * [PolicyTable]'s verdict for the command actually produced otherwise. A false action is any write
     * the label does not expect, and any write at all on an unconfident transcript, whether the
     * verdict says so or core's `TurnEngine` actually wrote.
     */
    private fun decide(
        clip: Clip,
        interpretation: Interpretation,
        score: IntentScore,
        confident: Boolean,
        state: DrivingState,
        verdict: String,
        observed: TurnObservation,
    ): ContextResult {
        val accepted =
            Scoring.acceptedVerdicts(
                clip.expected,
                clip.policy.getValue(state),
                pipeline.lmEnabled,
                interpretation.source,
                interpretation.predicted,
            )
        val basis =
            when {
                !confident -> Basis.UNCLEAR
                score.slots -> Basis.LABEL
                else -> Basis.TABLE
            }
        val expected =
            if (basis == Basis.LABEL) {
                accepted
            } else {
                setOf(PolicyTable.expected(interpretation.command, interpretation.source, state, confident, pipeline.frontDefrostOn))
            }
        val command = interpretation.command
        val wrote = observed.writes > 0
        val mismatch =
            buildList {
                if (observed.verdict != verdict) add("verdict ${observed.verdict}")
                if (observed.command != interpretation.predicted) add("command ${observed.command}")
                if (command != null && observed.source != interpretation.source.name) add("source ${observed.source}")
                if (wrote != Scoring.reachesWrite(command, verdict)) add("writes ${observed.writes}")
            }
        return ContextResult(
            state = state.name,
            expected = expected.sorted().joinToString(" or "),
            actual = verdict,
            basis = basis.label,
            correct = Scoring.verdictAccepted(expected, verdict),
            falseAction = Scoring.isFalseAction(clip.expected, accepted, command, verdict, confident, wrote),
            write = wrote,
            wrongConfirmation = Scoring.isWrongConfirmation(clip.expected, command, verdict),
            wrongDirection = Scoring.isWrongDirection(clip.expected, command, verdict),
            turnEngineMismatch = mismatch.takeIf { it.isNotEmpty() }?.joinToString("; ", prefix = "TurnEngine: "),
        )
    }

    private fun expectedCommand(e: Expected): String? =
        when (e) {
            is Expected.Action -> {
                e.command
            }

            is Expected.Lm -> {
                e.acceptable
                    .sorted()
                    .joinToString("|")
                    .ifEmpty { null }
            }

            is Expected.Reject -> {
                null
            }
        }

    private fun describe(outcome: LmOutcome) =
        when (outcome) {
            is LmOutcome.Parsed -> "parsed ${outcome.raw}"
            is LmOutcome.Invalid -> "invalid ${outcome.raw.take(MAX_RAW)}"
            LmOutcome.TimedOut -> "timed out"
            is LmOutcome.Failed -> "failed ${outcome.error}"
        }

    companion object {
        private const val PROGRESS_EVERY = 25
        private const val MAX_RAW = 80

        fun aggregate(
            engine: String,
            lm: String,
            condition: Condition,
            results: List<ClipResult>,
        ): GroupResult {
            val n = results.size
            val decisions = results.flatMap { it.decisions }
            val scored = decisions
            val confusion =
                results
                    .filter { !it.slotCorrect }
                    .groupingBy { it.expected.substringBefore(' ') to predictedClass(it) }
                    .eachCount()
                    .map { (k, v) -> ConfusionCell(k.first, k.second, v) }
                    .sortedWith(compareByDescending<ConfusionCell> { it.count }.thenBy { it.expected }.thenBy { it.predicted })

            fun stage(
                name: String,
                values: List<Double>,
            ) = StageLatency(name, values.size, percentile(values, P50), percentile(values, P95))
            val latency =
                listOf(
                    stage("stt", results.mapNotNull { it.sttMs }),
                    stage("rules", results.map { it.rulesMs }),
                    stage("lm", results.mapNotNull { it.lmMs }),
                    stage("policy", results.map { it.policyMs }),
                ).filter { it.n > 0 }
            return GroupResult(
                engine = engine,
                lm = lm,
                condition = condition.name,
                noise = condition.noise,
                snrDb = condition.snrDb,
                clips = n,
                wer = Wer.rate(results.map { WordErrors(it.wordEdits, it.referenceWords) }),
                intentAccuracy = ratio(results.count { it.intentCorrect }, n),
                slotAccuracy = ratio(results.count { it.slotCorrect }, n),
                policyScored = scored.size,
                policyCorrect = scored.count { it.correct },
                policyCorrectness = if (scored.isEmpty()) null else ratio(scored.count { it.correct }, scored.size),
                scoredBy = Basis.entries.associate { b -> b.label to scored.count { it.basis == b.label } },
                decisions = decisions.size,
                falseActions = decisions.count { it.falseAction },
                falseActionRate = ratio(decisions.count { it.falseAction }, decisions.size),
                writes = decisions.count { it.write },
                wrongConfirmations = decisions.count { it.wrongConfirmation },
                wrongDirection = decisions.count { it.wrongDirection },
                turnEngineMismatches = decisions.count { it.turnEngineMismatch != null },
                unconfident = results.count { !it.confident },
                engineErrors = results.count { it.error != null },
                lmErrors = results.count { it.lmError },
                confusion = confusion,
                latency = latency,
                results = results,
            )
        }

        /** The predicted command class, or `Rejected` when the rules refused the words themselves. */
        private fun predictedClass(c: ClipResult): String = if (c.rule.startsWith("Rejected")) "Rejected" else Scoring.intentOf(c.predicted)

        private fun ratio(
            a: Int,
            b: Int,
        ) = if (b == 0) 0.0 else a.toDouble() / b

        private const val P50 = 50.0
        private const val P95 = 95.0
    }
}
