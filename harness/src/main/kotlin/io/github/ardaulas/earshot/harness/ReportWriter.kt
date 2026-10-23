package io.github.ardaulas.earshot.harness

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

object ReportWriter {
    private const val WORST = 10

    fun write(
        report: Report,
        outDir: Path,
    ) {
        Files.createDirectories(outDir)
        Files.writeString(outDir.resolve("report.json"), reportJson.encodeToString(Report.serializer(), report) + "\n")
        Files.writeString(outDir.resolve("report.md"), markdown(report))
    }

    fun read(path: Path): Report = reportJson.decodeFromString(Report.serializer(), Files.readString(path))

    fun markdown(r: Report): String =
        buildString {
            appendLine("# Harness report: ${r.suite}")
            appendLine()
            val sources = r.sources.entries.joinToString(", ") { "${it.value} ${it.key}" }
            appendLine(
                "${r.clips} clips ($sources) from `${r.labelsFile}` (SHA-256 `${r.labelsSha256}`). " +
                    "Evidence: **${r.evidence}** (a WAV file or a label's text fed in, never a live microphone).",
            )
            if ("recorded" in r.sources) {
                appendLine()
                appendLine(
                    "Source `recorded` is a person's voice played from a file: still evidence `clip`, not `mic`, and not a test of the app's audio capture.",
                )
            }
            if (r.pendingSkipped > 0) {
                appendLine()
                appendLine("${r.pendingSkipped} label line(s) still tagged `pending-recording` were skipped (no audio yet).")
            }
            appendLine()
            appendLine("Host: ${r.host.short()}, JVM ${r.host.jvm}. ${r.host.note}")
            appendLine()
            r.code?.let { c ->
                val trees = c.trees.entries.joinToString(", ") { "`${it.key}` tree `${it.value.take(TREE_CHARS)}`" }
                appendLine("Code: commit `${c.commit.take(TREE_CHARS)}`${if (c.dirty) " with uncommitted changes" else ""}; $trees.")
                appendLine()
            }
            appendLine(
                "Confidence threshold ${r.thresholds.confidence}; front defrost handed to the policy: ${r.frontDefrost}. " +
                    "WER uses the harness's own normaliser `${r.werNormaliser}` (${WerNormaliser.DESCRIPTION}).",
            )
            appendLine()
            appendLine(
                "Every decision is scored for policy correctness, on one of three bases: `label` (confident transcript, exactly the " +
                    "labelled command: the label's verdicts), `unclear` (below the confidence threshold: the policy must re-prompt, " +
                    "or stop on a cancel) and `table` (confident, another command: the harness's policy table for that command). " +
                    "Every decision also runs through core's `TurnEngine` with a simulated vehicle; its writes are the false-action " +
                    "ground truth and any disagreement with the harness pipeline is counted.",
            )
            if (r.models.isNotEmpty()) {
                appendLine()
                r.models.forEach { appendLine("- Model (${it.role}): `${it.file}`, SHA-256 `${it.sha256}`") }
                r.engines.forEach { e ->
                    appendLine(
                        "- Host engine: ${e["engine"] ?: "?"} ${e["version"] ?: ""}, source SHA-256 `${e["source_sha256"] ?: "?"}`, ${e["threads"] ?: "?"} threads",
                    )
                }
            }
            if (r.skipped.isNotEmpty()) {
                appendLine()
                appendLine("Skipped:")
                r.skipped.forEach { appendLine("- $it") }
            }

            appendLine()
            appendLine("## Results by transcript source and condition")
            appendLine()
            appendLine(
                "| Source | LM | Condition | Clips | WER | Intent | Slots | Policy correct (scored/decisions; label, unclear, table) | " +
                    "False actions | Writes | Wrong confirmations (wrong direction) | TurnEngine mismatches | Unclear | Errors (engine, LM) |",
            )
            appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
            for (g in r.groups) {
                val by = Basis.entries.joinToString(", ") { (g.scoredBy[it.label] ?: 0).toString() }
                val policy = g.policyCorrectness?.let { "${g.policyCorrect}/${g.policyScored} of ${g.decisions} ($by)" } ?: "none scored"
                appendLine(
                    "| ${g.engine} | ${g.lm} | ${g.condition} | ${g.clips} | ${pct(g.wer)} | ${pct(g.intentAccuracy)} | " +
                        "${pct(g.slotAccuracy)} | $policy | ${g.falseActions}/${g.decisions} | ${g.writes} | " +
                        "${g.wrongConfirmations} (${g.wrongDirection}) | ${g.turnEngineMismatches} | ${g.unconfident} | " +
                        "${g.engineErrors}, ${g.lmErrors} |",
                )
            }

            appendLine()
            appendLine("## Intent confusions")
            appendLine()
            appendLine("Clips whose command was not exactly right, counted by expected and predicted command class.")
            for (g in r.groups) {
                appendLine()
                appendLine("**${g.key}**")
                appendLine()
                if (g.confusion.isEmpty()) {
                    appendLine("None.")
                } else {
                    appendLine("| Expected | Predicted | Count |")
                    appendLine("|---|---|---|")
                    g.confusion.forEach { appendLine("| ${it.expected} | ${it.predicted} | ${it.count} |") }
                }
            }

            appendLine()
            appendLine("## Worst clips")
            appendLine()
            appendLine(
                "Up to $WORST across all groups: false actions first, then TurnEngine mismatches, policy errors, wrong " +
                    "confirmations, wrong commands, then word errors.",
            )
            appendLine()
            val worst = worst(r)
            if (worst.isEmpty()) {
                appendLine("None: every clip was interpreted exactly, with no word errors.")
            } else {
                appendLine("| Group | Clip | Reference | Transcript | Expected | Predicted | Problem |")
                appendLine("|---|---|---|---|---|---|---|")
                worst.forEach { (g, c) ->
                    appendLine(
                        "| ${g.key} | ${c.id} | ${cell(
                            c.reference,
                        )} | ${cell(c.hypothesis)} | ${cell(c.expected)} | ${cell(c.predicted)} | ${problem(c)} |",
                    )
                }
            }

            appendLine()
            appendLine("## Latency")
            appendLine()
            appendLine("Measured on ${r.host.short()}. Not a phone, emulator or in-vehicle figure. Reported, not gated.")
            appendLine("`stt` and `lm` are the times the host engines report; `rules` and `policy` are measured in the JVM.")
            appendLine()
            appendLine("| Group | Stage | n | p50 ms | p95 ms |")
            appendLine("|---|---|---|---|---|")
            for (g in r.groups) {
                g.latency.forEach { appendLine("| ${g.key} | ${it.stage} | ${it.n} | ${ms(it.p50Ms)} | ${ms(it.p95Ms)} |") }
            }
        }

    internal fun worst(r: Report): List<Pair<GroupResult, ClipResult>> =
        r.groups
            .flatMap { g -> g.results.map { g to it } }
            .filter { (_, c) -> badness(c) > 0 }
            .sortedWith(
                compareByDescending<Pair<GroupResult, ClipResult>> { badness(it.second) }.thenBy { it.first.key }.thenBy { it.second.id },
            ).take(WORST)

    /** Ordering key: false action, mismatch, policy error, wrong confirmation, wrong command, then the share of words wrong. */
    private fun badness(c: ClipResult): Double {
        val falseActions = c.decisions.count { it.falseAction }
        val mismatches = c.decisions.count { it.turnEngineMismatch != null }
        val policyErrors = c.decisions.count { !it.correct }
        val wrongConfirmations = c.decisions.count { it.wrongConfirmation }
        val wrong = if (c.slotCorrect) 0 else 1
        val wer = if (c.referenceWords == 0) 0.0 else c.wordEdits.toDouble() / c.referenceWords
        return falseActions * FALSE_ACTION_WEIGHT + mismatches * MISMATCH_WEIGHT + policyErrors * POLICY_WEIGHT +
            wrongConfirmations * CONFIRMATION_WEIGHT + wrong * WRONG_WEIGHT + wer.coerceAtMost(1.0)
    }

    private fun problem(c: ClipResult): String {
        val parts = mutableListOf<String>()
        c.decisions.filter { it.falseAction }.forEach { parts += "false action ${it.state} (${it.actual})" }
        c.decisions.mapNotNull { d -> d.turnEngineMismatch?.let { "${d.state}: $it" } }.forEach { parts += it }
        c.decisions.filter { !it.correct }.forEach { parts += "${it.state}: ${it.actual}, expected ${it.expected} (${it.basis})" }
        c.decisions.firstOrNull { it.wrongConfirmation }?.let {
            parts += if (it.wrongDirection) "wrong-direction confirmation" else "confirmation of a command the label does not expect"
        }
        if (!c.slotCorrect) parts += "wrong command"
        if (!c.confident) parts += "unclear (confidence ${c.confidence})"
        if (c.wordEdits > 0) parts += "${c.wordEdits}/${c.referenceWords} word errors"
        c.error?.let { parts += "error: $it" }
        return cell(parts.joinToString("; "))
    }

    private fun cell(s: String) = s.replace("|", "\\|").replace("\n", " ")

    private fun ms(v: Double?) = v?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "-"

    private const val FALSE_ACTION_WEIGHT = 100000.0
    private const val MISMATCH_WEIGHT = 10000.0
    private const val POLICY_WEIGHT = 1000.0
    private const val CONFIRMATION_WEIGHT = 100.0
    private const val WRONG_WEIGHT = 10.0
    private const val TREE_CHARS = 12
}
