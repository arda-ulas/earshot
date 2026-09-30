package io.github.ardaulas.earshot.harness

import io.github.ardaulas.earshot.core.command.Command
import io.github.ardaulas.earshot.core.policy.DrivingState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class TestSetLoaderTest {
    private val good =
        """{"id":"a","audio":"a.wav","source":"synthetic","voice":"v","transcript":"fan off","expected":{"command":"SetFan(level=0)"},""" +
            """"policy":{"PARKED":"Allow","MOVING":"AllowVoiceOnly","UNKNOWN":"AllowVoiceOnly"},"tags":["fan"]}"""

    private fun labels(vararg lines: String): Pair<List<Clip>, List<String>> {
        val problems = mutableListOf<String>()
        return TestSetLoader.parseLabels(lines.toList(), problems) to problems
    }

    private fun yaml(text: String): Pair<Suite, List<String>> {
        val problems = mutableListOf<String>()
        return TestSetLoader.parseSuiteYaml(text, Paths.get("x"), problems) to problems
    }

    @Test
    fun `the fixture suite loads`() {
        val s = Fixtures.suite
        s.name shouldBe "fixture"
        s.snrDb shouldBe listOf(20, 10, 5)
        s.noiseProfiles shouldBe listOf("cabin_hum")
        s.clips.size shouldBe 10
        s.clips.first().expected shouldBe Expected.Action("SetTemp(celsius=21)")
        s.clips.single { it.id == "fx-006" }.expected shouldBe Expected.Lm("AdjustTemp(delta=2)")
        s.clips.single { it.id == "fx-004" }.expected shouldBe Expected.Reject(RejectReason.OUT_OF_DOMAIN)
        s.clips.single { it.id == "fx-005" }.expected shouldBe Expected.Reject(RejectReason.OUT_OF_RANGE)
        s.clips.single { it.id == "fx-009" }.expected shouldBe Expected.Reject(RejectReason.RULES_REJECTED)
        s.clips.first().policy[DrivingState.MOVING] shouldBe setOf("AllowVoiceOnly")
        s.clips.single { it.id == "fx-010" }.policy[DrivingState.PARKED] shouldBe setOf("Refuse(OUT_OF_DOMAIN)", "Reprompt")
        s.clips.all { it.source == ClipSource.SYNTHETIC } shouldBe true
    }

    @Test
    fun `a good label parses`() {
        val (clips, problems) = labels(good, "")
        problems shouldBe emptyList()
        clips.single().tags shouldBe listOf("fan")
    }

    @Test
    fun `known commands cover every value core accepts`() {
        KnownCommands.all["SetTemp(celsius=16)"] shouldBe Command.SetTemp(16)
        KnownCommands.all["AdjustTemp(delta=-4)"] shouldBe Command.AdjustTemp(-4)
        KnownCommands.all.containsKey("SetTemp(celsius=29)") shouldBe false
        KnownCommands.all.containsKey("AdjustTemp(delta=0)") shouldBe false
        KnownCommands.all["SetDefrost(window=REAR, on=true)"] shouldBe
            Command.SetDefrost(io.github.ardaulas.earshot.core.command.Window.REAR, true)
    }

    @Test
    fun `label problems are all reported with line numbers`() {
        val (_, problems) =
            labels(
                good.replace("SetFan(level=0)", "SetFan(level=9)"),
                good.replace("\"synthetic\"", "\"mic\""),
                good.replace(",\"UNKNOWN\":\"AllowVoiceOnly\"", ""),
                good.replace("\"Allow\"", "\"Maybe\""),
                good.replace("a.wav", "../a.wav"),
                good.replace("\"tags\"", "\"extra\""),
                "not json",
                good,
                good,
            )
        problems shouldContain "labels.jsonl line 1: expected.command 'SetFan(level=9)' is not a command core can produce"
        problems shouldContain "labels.jsonl line 2: source must be \"synthetic\" or \"recorded\", not mic"
        problems shouldContain "labels.jsonl line 3: policy has no verdict for UNKNOWN"
        problems shouldContain "labels.jsonl line 4: policy.PARKED '\"Maybe\"' is not a verdict name or a list of them"
        problems shouldContain "labels.jsonl line 5: audio must be a relative path under audio/"
        problems shouldContain "labels.jsonl line 6: unknown field 'extra'"
        problems shouldContain "labels.jsonl line 7: not a JSON object"
        problems shouldContain "labels.jsonl line 9: duplicate id 'a'"
    }

    @Test
    fun `expected must be exactly one kind`() {
        labels(
            good.replace("{\"command\":\"SetFan(level=0)\"}", "{\"reject\":true,\"command\":\"SetFan(level=0)\"}"),
        ).second.single() shouldContain
            "expected must be"
        labels(good.replace("{\"command\":\"SetFan(level=0)\"}", "{}")).second.single() shouldContain "expected must be"
        val confirm = good.replace("\"Allow\"", "\"Confirm\"").replace("\"AllowVoiceOnly\"", "\"Confirm\"")
        labels(confirm.replace("{\"command\":\"SetFan(level=0)\"}", "{\"lm\":true}")).first.single().expected shouldBe Expected.Lm(null)
    }

    @Test
    fun `a policy entry may be a set of acceptable verdicts`() {
        val set = good.replace("\"PARKED\":\"Allow\"", "\"PARKED\":[\"Allow\",\"Confirm\"]")
        val (clips, problems) = labels(set)
        problems shouldBe emptyList()
        clips.single().policy[DrivingState.PARKED] shouldBe setOf("Allow", "Confirm")
        labels(good.replace("\"PARKED\":\"Allow\"", "\"PARKED\":[]")).second.first() shouldContain "is not a verdict name or a list"
        labels(good.replace("\"PARKED\":\"Allow\"", "\"PARKED\":[\"Allow\",3]")).second.first() shouldContain "is not a verdict name"
        labels(good.replace("\"PARKED\":\"Allow\"", "\"PARKED\":\"Refuse(SOMETIMES)\"")).second.first() shouldContain
            "is not a verdict name"
        labels(good.replace("\"PARKED\":\"Allow\"", "\"PARKED\":\"Refuse(OUT_OF_DOMAIN)\"")).second shouldBe emptyList()
    }

    @Test
    fun `reject reasons and language-model intents are parsed and checked`() {
        val reject = """{"reject":true,"reason":"rejected"}"""
        val refused = """"policy":{"PARKED":"Refuse","MOVING":"Refuse","UNKNOWN":"Refuse"}"""
        val base = good.replace("{\"command\":\"SetFan(level=0)\"}", reject).replace(Regex("\"policy\":\\{[^}]*\\}"), refused)
        labels(base).first.single().expected shouldBe Expected.Reject(RejectReason.RULES_REJECTED)
        labels(base.replace("rejected", "maybe")).second.single() shouldContain "expected.reason must be one of"
        labels(base.replace(",\"reason\":\"rejected\"", ",\"why\":1")).second.single() shouldContain "unknown field 'expected.why'"

        val lm = """{"lm":true,"command":"AdjustTemp(delta=-2)","lm_intent":["cooler","ac_on"]}"""
        val lmLine = base.replace(reject, lm).replace("Refuse", "Confirm")
        labels(lmLine).first.single().expected shouldBe
            Expected.Lm("AdjustTemp(delta=-2)", setOf("AdjustTemp(delta=-2)", "SetAc(on=true)"))
        labels(lmLine.replace("\"ac_on\"", "\"seat_on\"")).second.single() shouldContain "is not a label of LmWireFormat"
        labels(lmLine.replace("\"cooler\",", "")).second.single() shouldContain "is not what any of expected.lm_intent maps to"
    }

    @Test
    fun `verdicts must fit the expectation`() {
        // A rejection that is allowed somewhere would hide a false action in the label itself.
        val allowedReject = good.replace("{\"command\":\"SetFan(level=0)\"}", "{\"reject\":true}")
        labels(allowedReject).second.single() shouldContain "only an expected command can be allowed"
        val oorWithoutReason =
            allowedReject.replace(
                Regex("\"policy\":\\{[^}]*\\}"),
                "\"policy\":{\"PARKED\":\"OutOfRange\",\"MOVING\":\"OutOfRange\",\"UNKNOWN\":\"OutOfRange\"}",
            )
        labels(oorWithoutReason).second.single() shouldContain "OutOfRange is only for"
        val oorRefused =
            allowedReject
                .replace(
                    "{\"reject\":true}",
                    "{\"reject\":true,\"reason\":\"out_of_range\"}",
                ).replace(
                    Regex("\"policy\":\\{[^}]*\\}"),
                    "\"policy\":{\"PARKED\":\"Refuse\",\"MOVING\":\"Refuse\",\"UNKNOWN\":\"Refuse\"}",
                )
        labels(oorRefused).second.single() shouldContain "must expect OutOfRange in every driving state"
    }

    @Test
    fun `the car suite loads and its labels are consistent`() {
        val car = Paths.get(System.getProperty("user.dir")).resolve("../testset/car").normalize()
        val s = TestSetLoader.load(car)
        s.clips.size shouldBe
            s.clips
                .map { it.id }
                .toSet()
                .size
        s.clips.all { it.source == ClipSource.SYNTHETIC } shouldBe true
        s.pendingSkipped shouldBe 0
        s.labelsSha256.length shouldBe 64
        // Nothing is recorded yet: every line is pending, so loading the recorded labels is refused.
        val e = shouldThrow<TestSetException> { TestSetLoader.load(car, "labels-recorded.jsonl") }
        e.message!! shouldContain "all 63 lines are tagged pending-recording"
    }

    @Test
    fun `lines still pending a recording are skipped and counted`(
        @TempDir dir: Path,
    ) {
        Files.copy(fixtureDir().resolve("suite.yaml"), dir.resolve("suite.yaml"))
        val recorded = good.replace("\"synthetic\"", "\"recorded\"")
        val pending = { id: String ->
            recorded.replace("\"id\":\"a\"", "\"id\":\"$id\"").replace("[\"fan\"]", "[\"fan\",\"pending-recording\"]")
        }
        Files.writeString(dir.resolve("partial.jsonl"), listOf(recorded, pending("b"), pending("c")).joinToString("\n"))
        val s = TestSetLoader.load(dir, "partial.jsonl")
        s.clips.map { it.id } shouldBe listOf("a")
        s.pendingSkipped shouldBe 2
        s.labelsFile shouldBe "partial.jsonl"
        // A pending line is still checked: a broken one is reported, not silently skipped.
        Files.writeString(
            dir.resolve("broken.jsonl"),
            listOf(recorded, pending("b").replace("SetFan(level=0)", "SetFan(level=9)")).joinToString("\n"),
        )
        shouldThrow<TestSetException> { TestSetLoader.load(dir, "broken.jsonl") }.message!! shouldContain "SetFan(level=9)"
        Files.writeString(dir.resolve("none.jsonl"), pending("b"))
        shouldThrow<TestSetException> { TestSetLoader.load(dir, "none.jsonl") }.message!! shouldContain "all 1 lines are tagged"
    }

    @Test
    fun `front defrost not stated is unknown, the policy's fail-safe`() {
        yaml("name: car").first.frontDefrostOn shouldBe null
        yaml("name: car\ncontext:\n  front_defrost: off").first.frontDefrostOn shouldBe false
        Fixtures.suite.frontDefrostOn shouldBe false
    }

    @Test
    fun `suite yaml accepts noise profiles as names or mappings`() {
        val (s, problems) =
            yaml(
                """
                name: car
                noise:
                  - name: hum
                    file: hum.wav
                  - road
                snr_db: [20, 10]
                context:
                  front_defrost: unknown
                thresholds:
                  confidence: 0.6
                  policy_correctness: 1.0
                  false_action_rate: 0
                """.trimIndent(),
            )
        problems shouldBe emptyList()
        s.noiseProfiles shouldBe listOf("hum", "road")
        s.snrDb shouldBe listOf(20, 10)
        s.frontDefrostOn shouldBe null
        s.thresholds.confidence shouldBe 0.6f
    }

    @Test
    fun `the fixed gates cannot be loosened and typos are caught`() {
        val (_, problems) =
            yaml(
                """
                name: car
                thresholds:
                  policy_correctness: 0.95
                  false_action_rate: 0.01
                  max_wer_rise: 5
                """.trimIndent(),
            )
        problems shouldBe
            listOf(
                "suite.yaml: thresholds.policy_correctness is fixed at 1.0",
                "suite.yaml: thresholds.false_action_rate is fixed at 0",
                "suite.yaml: unknown threshold 'max_wer_rise'",
            )
    }

    @Test
    fun `noisy audio paths follow the pattern`() {
        val s = Fixtures.suite
        s.cleanAudio(s.clips.first()) shouldBe s.dir.resolve("audio/clean/fx-001.wav")
        s.noisyAudio(s.clips.first(), "cabin_hum", 10) shouldBe s.dir.resolve("audio/snr10/cabin_hum/clean/fx-001.wav")
    }

    @Test
    fun `a broken suite throws every problem at once`(
        @TempDir dir: Path,
    ) {
        Files.writeString(dir.resolve("suite.yaml"), "domain: x\n")
        Files.writeString(dir.resolve("labels.jsonl"), "{}\n")
        val e = shouldThrow<TestSetException> { TestSetLoader.load(dir) }
        e.message!! shouldContain "suite.yaml: missing name"
        e.message!! shouldContain "labels.jsonl line 1: missing id"
    }
}
