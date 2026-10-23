package io.github.ardaulas.earshot.harness

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The machine the numbers were measured on. Every latency figure is read against this. */
@Serializable
data class HostLabel(
    val os: String,
    val osVersion: String,
    val arch: String,
    val cpu: String,
    val logicalCores: Int,
    /** Threads the host engines were asked to use (`--threads`); null when no host engine ran. */
    val threads: Int?,
    val jvm: String,
    val note: String = "Latency measured on this development host, not on a phone, emulator or vehicle.",
) {
    fun short(): String {
        val engines = threads?.let { ", $it engine threads" } ?: ", no host engine (text only)"
        return "$os $osVersion $arch, $cpu, $logicalCores logical cores$engines"
    }
}

/** What a decision's expected verdict comes from. */
enum class Basis(
    val label: String,
) {
    /** Confident transcript and exactly the labelled command: the label's verdicts. */
    LABEL("label"),

    /** Unconfident transcript: the policy must re-prompt (or stop on a cancel), whatever the label says. */
    UNCLEAR("unclear"),

    /** Confident, but a different command than labelled: [PolicyTable]'s verdict for the command produced. */
    TABLE("table"),
}

@Serializable
data class ContextResult(
    val state: String,
    /** The verdicts accepted in this state, joined with " or ". */
    val expected: String,
    val actual: String,
    /** Where [expected] comes from: `label`, `unclear` or `table` ([Basis]). */
    val basis: String,
    val correct: Boolean,
    val falseAction: Boolean,
    /** Core's `TurnEngine` wrote to the (simulated) vehicle for this transcript and state. */
    val write: Boolean = false,
    /** A confirmation question about a write the label does not expect. */
    val wrongConfirmation: Boolean = false,
    /** A wrong confirmation in the opposite direction to the labelled one (warmer for cooler, or the reverse). */
    val wrongDirection: Boolean = false,
    /** How core's `TurnEngine` differed from the harness pipeline; null when it agreed. */
    val turnEngineMismatch: String? = null,
)

@Serializable
data class ClipResult(
    val id: String,
    val source: String,
    val tags: List<String>,
    val reference: String,
    val hypothesis: String,
    val confidence: Float?,
    /** At or above the policy's confidence threshold. */
    val confident: Boolean,
    val wordEdits: Int,
    val referenceWords: Int,
    val expected: String,
    val predicted: String,
    /** What core's rules returned: `Matched`, `NoMatch`, `OutOfRange(...)` or `Rejected(<reason>)`. */
    val rule: String = "",
    val commandSource: String,
    val lmOutcome: String?,
    /** The language model timed out or failed (not an invalid answer, which is the model's own output). */
    val lmError: Boolean = false,
    val intentCorrect: Boolean,
    val slotCorrect: Boolean,
    val decisions: List<ContextResult>,
    val error: String? = null,
    val sttMs: Double? = null,
    val rulesMs: Double,
    val lmMs: Double? = null,
    /** Mean time of one policy decision for this clip. */
    val policyMs: Double,
)

@Serializable
data class ConfusionCell(
    val expected: String,
    val predicted: String,
    val count: Int,
)

@Serializable
data class StageLatency(
    val stage: String,
    val n: Int,
    val p50Ms: Double?,
    val p95Ms: Double?,
)

/** Metrics for one transcript source, language-model setting and audio condition. */
@Serializable
data class GroupResult(
    val engine: String,
    val lm: String,
    /** `text` (reference transcripts), `clean`, or `<noise>@<snr>dB`. */
    val condition: String,
    val noise: String? = null,
    val snrDb: Int? = null,
    val clips: Int,
    val wer: Double,
    val intentAccuracy: Double,
    val slotAccuracy: Double,
    /** Decisions scored for policy correctness: every decision, on one of the three [Basis]. */
    val policyScored: Int,
    val policyCorrect: Int,
    /** Correct over scored; null when nothing was scored. */
    val policyCorrectness: Double?,
    /** Scored decisions by basis: `label`, `unclear`, `table`. */
    val scoredBy: Map<String, Int> = emptyMap(),
    val decisions: Int,
    val falseActions: Int,
    val falseActionRate: Double,
    /** Decisions where core's `TurnEngine` wrote to the simulated vehicle. */
    val writes: Int = 0,
    /** Confirmation questions about a write the label does not expect (not gated; one "yes" would act). */
    val wrongConfirmations: Int = 0,
    /** The subset of [wrongConfirmations] that go the opposite direction to the label. */
    val wrongDirection: Int = 0,
    /** Decisions where core's `TurnEngine` disagreed with the harness pipeline (gated at 0). */
    val turnEngineMismatches: Int = 0,
    /** Clips whose transcript was below the confidence threshold (the policy must re-prompt). */
    val unconfident: Int,
    val engineErrors: Int,
    /** Language-model calls that timed out or failed; gated with the engine errors. */
    val lmErrors: Int = 0,
    val confusion: List<ConfusionCell>,
    val latency: List<StageLatency>,
    val results: List<ClipResult>,
) {
    val key: String get() = "$engine/$lm/$condition"
}

@Serializable
data class ModelUsed(
    val role: String,
    val file: String,
    val sha256: String,
)

@Serializable
data class ReportThresholds(
    val confidence: Float,
    val maxIntentDropPoints: Double,
    val maxWerRisePoints: Double,
)

/** The code a report was produced with, so it can be traced to a commit after the fact. */
@Serializable
data class CodeVersion(
    /** `git rev-parse HEAD`. */
    val commit: String,
    /** True when the working tree differed from [commit] (tracked or untracked files). */
    val dirty: Boolean,
    /**
     * Git tree hashes of the directories that decide the numbers, taken from the working tree as it was
     * (`core/src/main`, `harness/src/main`, `testset/<suite>`). A later commit contains exactly this
     * code when `git rev-parse <commit>:<dir>` gives the same hash.
     */
    val trees: Map<String, String>,
) {
    companion object
}

@Serializable
data class Report(
    val schema: Int = SCHEMA,
    val suite: String,
    val clips: Int,
    /** Clip counts by audio source (synthetic, recorded). */
    val sources: Map<String, Int>,
    /** The labels file and its SHA-256; the baseline records both. */
    val labelsFile: String = "labels.jsonl",
    val labelsSha256: String = "",
    /** Label lines left out because they are still tagged `pending-recording`. */
    val pendingSkipped: Int = 0,
    /** Always "clip": a WAV file or a label's text is fed in; the harness never uses a live microphone. */
    val evidence: String = "clip",
    val host: HostLabel,
    val thresholds: ReportThresholds,
    val frontDefrost: String,
    /** The WER text normalisation ([WerNormaliser.ID]). */
    val werNormaliser: String = WerNormaliser.ID,
    val code: CodeVersion? = null,
    val models: List<ModelUsed>,
    /** What each host engine binary reported with `--info`. */
    val engines: List<Map<String, String>> = emptyList(),
    val skipped: List<String>,
    val groups: List<GroupResult>,
) {
    companion object {
        const val SCHEMA = 2
    }
}

/** The gated numbers of a report, kept in git as the reference to compare against. */
@Serializable
data class BaselineGroup(
    val engine: String,
    val lm: String,
    val condition: String,
    val wer: Double,
    val intentAccuracy: Double,
    val slotAccuracy: Double? = null,
    val policyCorrectness: Double? = null,
    val falseActionRate: Double? = null,
    val clips: Int? = null,
) {
    val key: String get() = "$engine/$lm/$condition"
}

@Serializable
data class Baseline(
    val schema: Int = Report.SCHEMA,
    val suite: String,
    /** What the numbers were measured on: the clip count and the exact labels, normaliser and thresholds. */
    val clips: Int? = null,
    val labelsFile: String? = null,
    val labelsSha256: String? = null,
    val werNormaliser: String? = null,
    val thresholds: ReportThresholds? = null,
    val host: String? = null,
    val code: List<CodeVersion> = emptyList(),
    val groups: List<BaselineGroup>,
) {
    companion object {
        /**
         * Joins baselines of the same suite, clip count, labels, normaliser and thresholds; a later group
         * replaces an earlier one with the same key.
         */
        fun merge(parts: List<Baseline>): Baseline {
            require(parts.isNotEmpty())

            fun <T> same(
                what: String,
                f: (Baseline) -> T,
            ): T {
                val values = parts.map(f).toSet()
                if (values.size != 1) throw TestSetException("reports differ in $what: $values")
                return values.single()
            }
            val suite = same("suite (reports are for different suites)") { it.suite }
            val hosts = parts.mapNotNull { it.host }.toSet()
            return Baseline(
                suite = suite,
                clips = same("clip count") { it.clips },
                labelsFile = same("labels file") { it.labelsFile },
                labelsSha256 = same("labels SHA-256") { it.labelsSha256 },
                werNormaliser = same("WER normaliser") { it.werNormaliser },
                thresholds = same("thresholds") { it.thresholds },
                host = hosts.joinToString("; ").ifEmpty { null },
                code = parts.flatMap { it.code }.distinct(),
                groups =
                    parts
                        .flatMap { it.groups }
                        .associateBy { it.key }
                        .values
                        .toList(),
            )
        }

        fun from(report: Report) =
            Baseline(
                suite = report.suite,
                clips = report.clips,
                labelsFile = report.labelsFile,
                labelsSha256 = report.labelsSha256,
                werNormaliser = report.werNormaliser,
                thresholds = report.thresholds,
                host = report.host.short(),
                code = listOfNotNull(report.code),
                groups =
                    report.groups.map {
                        BaselineGroup(
                            it.engine,
                            it.lm,
                            it.condition,
                            it.wer,
                            it.intentAccuracy,
                            it.slotAccuracy,
                            it.policyCorrectness,
                            it.falseActionRate,
                            it.clips,
                        )
                    },
            )
    }
}

val reportJson =
    Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
