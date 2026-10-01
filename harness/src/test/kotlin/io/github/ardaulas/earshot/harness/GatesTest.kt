package io.github.ardaulas.earshot.harness

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class GatesTest {
    private fun group(
        engine: String = "whisper",
        condition: String = "clean",
        wer: Double = 0.10,
        intent: Double = 0.90,
        policyScored: Int = 30,
        policyCorrect: Int = 30,
        falseActions: Int = 0,
        errors: Int = 0,
        lmErrors: Int = 0,
        mismatches: Int = 0,
        clips: Int = 10,
    ) = GroupResult(
        engine = engine,
        lm = "none",
        condition = condition,
        clips = clips,
        wer = wer,
        intentAccuracy = intent,
        slotAccuracy = intent,
        policyScored = policyScored,
        policyCorrect = policyCorrect,
        policyCorrectness = if (policyScored == 0) null else policyCorrect.toDouble() / policyScored,
        scoredBy = mapOf("label" to policyScored),
        decisions = 30,
        falseActions = falseActions,
        falseActionRate = falseActions / 30.0,
        turnEngineMismatches = mismatches,
        unconfident = 0,
        engineErrors = errors,
        lmErrors = lmErrors,
        confusion = emptyList(),
        latency = emptyList(),
        results = emptyList(),
    )

    private fun report(
        vararg groups: GroupResult,
        skipped: List<String> = emptyList(),
        thresholds: ReportThresholds = ReportThresholds(0.5f, 2.0, 3.0),
    ) = Report(
        suite = "car",
        clips = 10,
        sources = mapOf("synthetic" to 10),
        labelsSha256 = "abc",
        host = HostLabel("os", "1", "arch", "cpu", 8, 2, "17"),
        thresholds = thresholds,
        frontDefrost = "off",
        models = emptyList(),
        skipped = skipped,
        groups = groups.toList(),
    )

    private val baseline = Baseline.from(report(group()))

    private fun failed(c: Comparison) = c.gates.filter { !it.passed }.map { it.gate }

    @Test
    fun `an identical report passes`() {
        Gates.compare(baseline, report(group())).passed shouldBe true
    }

    @Test
    fun `one policy error fails`() {
        failed(Gates.compare(baseline, report(group(policyCorrect = 29)))) shouldBe listOf("policy correctness 100% of all decisions")
    }

    @Test
    fun `nothing scored, or not every decision scored, fails the policy gate`() {
        failed(Gates.compare(baseline, report(group(policyScored = 0, policyCorrect = 0)))) shouldBe
            listOf("policy correctness 100% of all decisions")
        val c = Gates.compare(baseline, report(group(policyScored = 20, policyCorrect = 20)))
        failed(c) shouldBe listOf("policy correctness 100% of all decisions")
        c.gates.first().detail shouldContain "scored 20/30"
    }

    @Test
    fun `one false action fails`() {
        failed(Gates.compare(baseline, report(group(falseActions = 1)))) shouldBe listOf("no false actions")
    }

    @Test
    fun `engine errors and language-model timeouts or failures fail`() {
        failed(Gates.compare(baseline, report(group(errors = 2)))) shouldBe listOf("no engine errors")
        failed(Gates.compare(baseline, report(group(lmErrors = 1)))) shouldBe listOf("no engine errors")
    }

    @Test
    fun `a TurnEngine mismatch fails`() {
        failed(Gates.compare(baseline, report(group(mismatches = 1)))) shouldBe listOf("TurnEngine agrees")
    }

    @Test
    fun `intent may drop by exactly two points but not more`() {
        Gates.compare(baseline, report(group(intent = 0.88))).passed shouldBe true
        failed(Gates.compare(baseline, report(group(intent = 0.879)))) shouldBe listOf("intent accuracy drop <= 2.0 pt")
    }

    @Test
    fun `WER may rise by exactly three points but not more`() {
        Gates.compare(baseline, report(group(wer = 0.13))).passed shouldBe true
        failed(Gates.compare(baseline, report(group(wer = 0.131)))) shouldBe listOf("WER rise <= 3.0 pt")
    }

    @Test
    fun `improvements pass`() {
        Gates.compare(baseline, report(group(wer = 0.02, intent = 1.0))).passed shouldBe true
    }

    @Test
    fun `groups are matched by source, model and condition`() {
        val b = Baseline.from(report(group(condition = "cabin_hum@5dB", intent = 0.5, wer = 0.5), group()))
        // A noisy group far below the clean baseline still passes against its own baseline.
        Gates.compare(b, report(group(condition = "cabin_hum@5dB", intent = 0.5, wer = 0.5), group())).passed shouldBe true
    }

    @Test
    fun `a group with no baseline fails unless new groups are allowed, and absolute gates still apply`() {
        val r = report(group(), group(engine = "reference", condition = "text", falseActions = 1))
        failed(Gates.compare(baseline, r)) shouldBe listOf("no false actions", "baseline group exists")
        val c = Gates.compare(baseline, r, CompareOptions(allowNewGroups = true))
        failed(c) shouldBe listOf("no false actions")
        c.notes.single() shouldContain "reference/none/text: no baseline group"
    }

    @Test
    fun `a baseline group missing from the reports fails unless partial runs are allowed`() {
        val b = Baseline.from(report(group(), group(condition = "cabin_hum@10dB")))
        failed(Gates.compare(b, report(group()))) shouldBe listOf("baseline group reported")
        val c = Gates.compare(b, report(group()), CompareOptions(allowPartial = true))
        c.passed shouldBe true
        c.notes.single() shouldContain "cabin_hum@10dB: in the baseline but not in these reports"
    }

    @Test
    fun `several reports are compared together against one baseline`() {
        val b = Baseline.from(report(group(), group(condition = "cabin_hum@10dB")))
        Gates.compare(b, listOf(report(group()), report(group(condition = "cabin_hum@10dB")))).passed shouldBe true
        failed(Gates.compare(b, listOf(report(group()), report(group())))) shouldContain "each group once"
    }

    @Test
    fun `a skipped condition fails unless partial runs are allowed`() {
        val r = report(group(), skipped = listOf("whisper/none/cabin_hum@5dB: no noisy audio files"))
        failed(Gates.compare(baseline, r)) shouldBe listOf("no skipped conditions")
        Gates.compare(baseline, r, CompareOptions(allowPartial = true)).passed shouldBe true
    }

    @Test
    fun `a different suite, labels file, clip count or WER normaliser fails`() {
        failed(Gates.compare(baseline.copy(suite = "other"), report(group()))) shouldBe listOf("same suite")
        failed(Gates.compare(baseline.copy(labelsSha256 = "def"), report(group()))) shouldBe listOf("same labels (SHA-256)")
        failed(Gates.compare(baseline.copy(clips = 9), report(group()))) shouldBe listOf("same clip count")
        failed(Gates.compare(baseline.copy(werNormaliser = "core-normalizer"), report(group()))) shouldBe listOf("same WER normaliser")
        // A baseline written before these were recorded cannot vouch for the labels.
        failed(Gates.compare(baseline.copy(labelsSha256 = null), report(group()))) shouldBe listOf("same labels (SHA-256)")
    }

    @Test
    fun `a group with a different clip count fails`() {
        failed(Gates.compare(baseline, report(group(clips = 12)))) shouldBe listOf("same clip count")
    }

    @Test
    fun `the baseline's thresholds apply, and looser report thresholds fail`() {
        val loose = ReportThresholds(0.5f, 10.0, 10.0)
        val c = Gates.compare(baseline, report(group(intent = 0.85), thresholds = loose))
        failed(c) shouldBe listOf("thresholds not looser", "intent accuracy drop <= 2.0 pt")
        // Stricter thresholds in the report are fine; the baseline's are still the ones applied.
        Gates.compare(baseline, report(group(intent = 0.88), thresholds = ReportThresholds(0.5f, 1.0, 1.0))).passed shouldBe true
        failed(Gates.compare(baseline, report(group(), thresholds = ReportThresholds(0.4f, 2.0, 3.0)))) shouldBe
            listOf("thresholds not looser")
    }

    @Test
    fun `baselines of one suite merge by group, and only with the same labels`() {
        val a = Baseline.from(report(group(engine = "reference", condition = "text")))
        val b = Baseline.from(report(group(), group(condition = "pink@5dB")))
        val merged = Baseline.merge(listOf(a, b))
        merged.groups.map { it.key } shouldBe listOf("reference/none/text", "whisper/none/clean", "whisper/none/pink@5dB")
        merged.labelsSha256 shouldBe "abc"
        merged.thresholds shouldBe ReportThresholds(0.5f, 2.0, 3.0)
        runCatching { Baseline.merge(listOf(a, b.copy(suite = "other"))) }.exceptionOrNull()!!.message!! shouldContain "different suites"
        runCatching { Baseline.merge(listOf(a, b.copy(labelsSha256 = "x"))) }.exceptionOrNull()!!.message!! shouldContain "labels"
    }
}
