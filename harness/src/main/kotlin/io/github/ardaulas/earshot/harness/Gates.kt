package io.github.ardaulas.earshot.harness

import java.util.Locale

data class GateResult(
    val gate: String,
    val group: String,
    val passed: Boolean,
    val detail: String,
)

data class Comparison(
    val gates: List<GateResult>,
    /** Things worth knowing that do not fail the comparison, such as opted-out coverage gaps. */
    val notes: List<String>,
) {
    val passed: Boolean get() = gates.all { it.passed }
}

/** Explicit opt-outs from the coverage gates, for runs that are partial on purpose (CI's text-only run). */
data class CompareOptions(
    /** Baseline groups missing from the reports, and conditions the runs skipped, are noted instead of failed. */
    val allowPartial: Boolean = false,
    /** Report groups with no baseline group are noted instead of failed. */
    val allowNewGroups: Boolean = false,
)

/**
 * The regression gates.
 *
 * Fixed, for every group (transcript source, language-model setting, audio condition): every decision
 * scored and 100 % policy-correct, no false actions, no engine or language-model errors, and core's
 * `TurnEngine` agreeing with the harness pipeline on every decision.
 *
 * Against the baseline: the reports must be for the same suite, the same clip count, the same labels
 * (SHA-256) and the same WER normaliser, and their thresholds may not be looser than the baseline's.
 * Every baseline group must be in the reports, every report group must have a baseline group, and no
 * condition may have been skipped, unless [CompareOptions] opts out. For each group, intent accuracy may
 * not drop, and WER may not rise, by more than the baseline's thresholds (percentage points). Latency
 * is reported, never gated.
 */
object Gates {
    fun compare(
        baseline: Baseline,
        report: Report,
        options: CompareOptions = CompareOptions(),
    ): Comparison = compare(baseline, listOf(report), options)

    fun compare(
        baseline: Baseline,
        reports: List<Report>,
        options: CompareOptions = CompareOptions(),
    ): Comparison {
        require(reports.isNotEmpty())
        val gates = mutableListOf<GateResult>()
        val notes = mutableListOf<String>()
        sameInputs(baseline, reports, gates)
        val t = baseline.thresholds ?: reports.first().thresholds

        val skipped = reports.flatMap { it.skipped }
        if (skipped.isNotEmpty()) {
            if (options.allowPartial) {
                skipped.forEach { notes += "skipped: $it" }
            } else {
                gates += GateResult("no skipped conditions", "-", false, skipped.joinToString("; "))
            }
        }

        val byKey = baseline.groups.associateBy { it.key }
        val groups = reports.flatMap { it.groups }
        groups.groupBy { it.key }.filter { it.value.size > 1 }.keys.forEach {
            gates += GateResult("each group once", it, false, "the reports contain $it more than once")
        }
        for (g in groups) {
            absolute(g, gates)
            val base = byKey[g.key]
            if (base == null) {
                if (options.allowNewGroups) {
                    notes += "${g.key}: no baseline group; intent and WER not compared"
                } else {
                    gates += GateResult("baseline group exists", g.key, false, "no baseline group; intent and WER cannot be compared")
                }
                continue
            }
            if (base.clips != null && base.clips != g.clips) {
                gates += GateResult("same clip count", g.key, false, "baseline ${base.clips} clips, report ${g.clips}")
            }
            val drop = (base.intentAccuracy - g.intentAccuracy) * PERCENT
            gates +=
                GateResult(
                    "intent accuracy drop <= ${t.maxIntentDropPoints} pt",
                    g.key,
                    drop <= t.maxIntentDropPoints + EPSILON,
                    "${pct(base.intentAccuracy)} -> ${pct(g.intentAccuracy)} (${signed(-drop)} pt)",
                )
            val rise = (g.wer - base.wer) * PERCENT
            gates +=
                GateResult(
                    "WER rise <= ${t.maxWerRisePoints} pt",
                    g.key,
                    rise <= t.maxWerRisePoints + EPSILON,
                    "${pct(base.wer)} -> ${pct(g.wer)} (${signed(rise)} pt)",
                )
        }
        val reported = groups.map { it.key }.toSet()
        baseline.groups.filter { it.key !in reported }.forEach {
            if (options.allowPartial) {
                notes += "${it.key}: in the baseline but not in these reports"
            } else {
                gates += GateResult("baseline group reported", it.key, false, "in the baseline but not in these reports")
            }
        }
        return Comparison(gates, notes)
    }

    /** The gates every group must pass on its own, baseline or not. */
    private fun absolute(
        g: GroupResult,
        gates: MutableList<GateResult>,
    ) {
        val correctness = g.policyCorrectness
        val by = g.scoredBy.entries.joinToString(", ") { "${it.key} ${it.value}" }
        gates +=
            GateResult(
                "policy correctness 100% of all decisions",
                g.key,
                g.policyScored > 0 && g.policyScored == g.decisions && g.policyCorrect == g.policyScored,
                if (correctness == null) {
                    "no scored decisions"
                } else {
                    "${g.policyCorrect}/${g.policyScored} correct (${pct(correctness)}); scored ${g.policyScored}/${g.decisions} ($by)"
                },
            )
        gates += GateResult("no false actions", g.key, g.falseActions == 0, "${g.falseActions}/${g.decisions} decisions")
        gates +=
            GateResult(
                "no engine errors",
                g.key,
                g.engineErrors == 0 && g.lmErrors == 0,
                "${g.engineErrors} transcript error(s), ${g.lmErrors} language-model timeout(s) or failure(s)",
            )
        gates +=
            GateResult(
                "TurnEngine agrees",
                g.key,
                g.turnEngineMismatches == 0,
                "${g.turnEngineMismatches}/${g.decisions} decisions differ from core's TurnEngine",
            )
    }

    /** The reports must measure the same thing as the baseline, or intent and WER mean nothing against it. */
    private fun sameInputs(
        baseline: Baseline,
        reports: List<Report>,
        gates: MutableList<GateResult>,
    ) {
        fun check(
            gate: String,
            expected: Any?,
            actual: (Report) -> Any?,
        ) {
            reports.map(actual).toSet().filter { it != expected }.forEach {
                gates += GateResult(gate, "-", false, "baseline ${expected ?: "(not recorded)"}, report $it")
            }
        }
        check("same suite", baseline.suite) { it.suite }
        check("same clip count", baseline.clips) { it.clips }
        check("same labels file", baseline.labelsFile) { it.labelsFile }
        check("same labels (SHA-256)", baseline.labelsSha256) { it.labelsSha256 }
        check("same WER normaliser", baseline.werNormaliser) { it.werNormaliser }
        val base = baseline.thresholds
        for (r in reports) {
            val t = r.thresholds
            when {
                base == null -> {
                    gates += GateResult("thresholds not looser", "-", false, "the baseline records no thresholds")
                }

                t.maxIntentDropPoints > base.maxIntentDropPoints + EPSILON ||
                    t.maxWerRisePoints > base.maxWerRisePoints + EPSILON ||
                    t.confidence != base.confidence -> {
                    gates +=
                        GateResult(
                            "thresholds not looser",
                            "-",
                            false,
                            "baseline $base, report $t: the baseline's thresholds are used, and a changed confidence " +
                                "threshold needs a new baseline",
                        )
                }
            }
        }
    }

    fun render(c: Comparison): String =
        buildString {
            c.gates.forEach { appendLine("${if (it.passed) "PASS" else "FAIL"}  ${it.group}  ${it.gate}: ${it.detail}") }
            c.notes.forEach { appendLine("note  $it") }
            appendLine(if (c.passed) "All gates passed." else "${c.gates.count { !it.passed }} gate(s) failed.")
        }

    private const val PERCENT = 100.0

    /** Rounding slack so 2.0000000001 points does not fail a 2-point gate. */
    private const val EPSILON = 1e-9

    /** One decimal with a sign; anything that rounds to zero prints as +0.0. */
    private fun signed(v: Double): String {
        val x = if (kotlin.math.abs(v) < ROUNDS_TO_ZERO) 0.0 else v
        return (if (x >= 0) "+" else "") + String.format(Locale.ROOT, "%.1f", x)
    }

    private const val ROUNDS_TO_ZERO = 0.05
}

fun pct(v: Double): String = String.format(Locale.ROOT, "%.1f%%", v * 100)
