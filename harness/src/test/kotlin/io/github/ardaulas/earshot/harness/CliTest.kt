package io.github.ardaulas.earshot.harness

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CliTest {
    private fun cli(vararg args: String): Pair<Int, String> {
        val out = StringBuilder()
        val code = Cli.run(args.toList()) { out.appendLine(it) }
        return code to out.toString()
    }

    @Test
    fun `run, baseline and compare on the fixture`(
        @TempDir dir: Path,
    ) {
        val suite = fixtureDir().toString()
        val reportDir = dir.resolve("r")
        cli("run", "--suite", suite, "--engine", "reference", "--lm", "none", "--out", reportDir.toString()).first shouldBe 0
        val report = ReportWriter.read(reportDir.resolve("report.json"))
        report.evidence shouldBe "clip"
        report.host.threads shouldBe null
        val md = Files.readString(reportDir.resolve("report.md"))
        md shouldContain
            "| reference | none | text | 10 | 0.0% | 100.0% | 100.0% | 30/30 of 30 (30, 0, 0) | 0/30 | 5 | 0 (0) | 0 | 0 | 0, 0 |"
        md shouldContain "Measured on ${report.host.short()}"
        report.labelsSha256.length shouldBe 64
        report.werNormaliser shouldBe WerNormaliser.ID

        val baseline = dir.resolve("baseline.json")
        cli("baseline", "--report", reportDir.resolve("report.json").toString(), "--out", baseline.toString()).first shouldBe 0
        val (code, text) = cli("compare", "--baseline", baseline.toString(), "--report", reportDir.resolve("report.json").toString())
        code shouldBe 0
        text shouldContain "All gates passed."

        // A baseline that claims better intent accuracy makes the same report fail.
        Files.writeString(baseline, Files.readString(baseline).replace("\"intentAccuracy\": 1.0", "\"intentAccuracy\": 1.5"))
        val (failCode, failText) =
            cli(
                "compare",
                "--baseline",
                baseline.toString(),
                "--report",
                reportDir.resolve("report.json").toString(),
            )
        failCode shouldBe 1
        failText shouldContain "FAIL  reference/none/text  intent accuracy drop"
    }

    @Test
    fun `compare takes several reports and the opt-out flags`(
        @TempDir dir: Path,
    ) {
        val suite = fixtureDir().toString()
        val a = dir.resolve("a")
        cli("run", "--suite", suite, "--engine", "reference", "--lm", "none", "--out", a.toString()).first shouldBe 0
        val baseline = dir.resolve("baseline.json")
        cli("baseline", "--report", a.resolve("report.json").toString(), "--out", baseline.toString()).first shouldBe 0
        // A baseline group the report lacks fails, unless --allow-partial says the run is partial on purpose.
        Files.writeString(
            baseline,
            Files.readString(baseline).replace(
                "\"groups\": [",
                "\"groups\": [{\"engine\": \"whisper\", \"lm\": \"none\", " +
                    "\"condition\": \"clean\", \"wer\": 0.1, \"intentAccuracy\": 0.9},",
            ),
        )
        val report = a.resolve("report.json").toString()
        val (code, text) = cli("compare", "--baseline", baseline.toString(), "--report", report)
        code shouldBe 1
        text shouldContain "FAIL  whisper/none/clean  baseline group reported"
        val (partialCode, partialText) = cli("compare", "--baseline", baseline.toString(), "--report", report, "--allow-partial")
        partialCode shouldBe 0
        partialText shouldContain "note  whisper/none/clean: in the baseline but not in these reports"
        Cli.options(listOf("--allow-new-groups", "--report", "x")) shouldBe mapOf("allow-new-groups" to "true", "report" to "x")
    }

    @Test
    fun `validate prints a summary`() {
        val (code, text) = cli("validate", "--suite", fixtureDir().toString())
        code shouldBe 0
        text shouldContain "fixture: 10 clips"
    }

    @Test
    fun `snr selection`() {
        val snrs = listOf(20, 10, 5)
        Cli.snrSelection("all", snrs) shouldBe (true to snrs)
        Cli.snrSelection("clean", snrs) shouldBe (true to emptyList())
        Cli.snrSelection("10", snrs) shouldBe (false to listOf(10))
        Cli.snrSelection("clean,5,20", snrs) shouldBe (true to listOf(5, 20))
        shouldThrow<UsageException> { Cli.snrSelection("15", snrs) }
        shouldThrow<UsageException> { Cli.snrSelection("loud", snrs) }
    }

    @Test
    fun `bad usage is reported`() {
        shouldThrow<UsageException> { cli() }
        shouldThrow<UsageException> { cli("run", "--suite") }
        shouldThrow<UsageException> { cli("run", "--suite", fixtureDir().toString(), "--engine", "nope", "--lm", "none", "--out", "x") }
    }

    @Test
    fun `a whisper run needs a verified model`(
        @TempDir dir: Path,
    ) {
        Files.writeString(dir.resolve("manifest.json"), """{"models":[]}""")
        val e =
            shouldThrow<UsageException> {
                cli(
                    "run",
                    "--suite",
                    fixtureDir().toString(),
                    "--engine",
                    "whisper",
                    "--lm",
                    "none",
                    "--out",
                    dir.resolve("o").toString(),
                    "--models",
                    dir.toString(),
                )
            }
        e.message!! shouldContain "is not a stt model"
    }
}
