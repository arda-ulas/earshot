package io.github.ardaulas.earshot.core.model

import io.github.ardaulas.earshot.core.requirements.Verifies
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

@Verifies("SR-12")
class ModelGateTest {
    private val dir = File("unused-in-these-tests")

    private fun sttSpec(id: String = "stt-1") =
        ModelSpec(id, role = "stt", file = "$id.bin", url = "https://x/$id", sizeBytes = 1, sha256 = "s", license = "MIT")

    private fun lmSpec(id: String = "lm-1") =
        ModelSpec(id, role = "lm", file = "$id.bin", url = "https://x/$id", sizeBytes = 1, sha256 = "l", license = "MIT")

    @Test
    fun `no models listed at all disables the assistant`() {
        val manifest = ModelManifest(emptyList())
        val status = ModelGate.check(manifest, dir) { _, _ -> ModelCheck.Ok }
        (status is AssistantStatus.Disabled) shouldBe true
        (status as AssistantStatus.Disabled).reason shouldBe "No speech model is listed in the manifest."
    }

    @Test
    fun `a speech model that fails verification, with no other candidate, disables the assistant`() {
        val stt = sttSpec()
        val manifest = ModelManifest(listOf(stt))
        val status = ModelGate.check(manifest, dir) { _, _ -> ModelCheck.Missing }
        (status is AssistantStatus.Disabled) shouldBe true
        (status as AssistantStatus.Disabled).reason shouldBe
            "Speech model ${stt.file}: missing. Run scripts/fetch-models.sh and scripts/push-models.sh."
    }

    @Test
    fun `the first speech model that passes verification wins`() {
        val bad = sttSpec("stt-bad")
        val good = sttSpec("stt-good")
        val manifest = ModelManifest(listOf(bad, good))
        val status =
            ModelGate.check(manifest, dir) { spec, _ ->
                if (spec.id == "stt-good") ModelCheck.Ok else ModelCheck.Missing
            }
        (status is AssistantStatus.Ready) shouldBe true
        (status as AssistantStatus.Ready).stt shouldBe File(dir, good.file)
    }

    @Test
    fun `speech ok and no language model listed disables only the fallback`() {
        val stt = sttSpec()
        val manifest = ModelManifest(listOf(stt))
        val status = ModelGate.check(manifest, dir) { _, _ -> ModelCheck.Ok }
        (status is AssistantStatus.Ready) shouldBe true
        val ready = status as AssistantStatus.Ready
        ready.lm shouldBe null
        ready.lmProblem shouldBe "No language model is listed in the manifest."
    }

    @Test
    @Verifies("SR-12")
    fun `speech ok and a language model that fails verification disables only the fallback`() {
        val stt = sttSpec()
        val lm = lmSpec()
        val manifest = ModelManifest(listOf(stt, lm))
        val status =
            ModelGate.check(manifest, dir) { spec, _ ->
                if (spec.role == "stt") ModelCheck.Ok else ModelCheck.HashMismatch("deadbeef")
            }
        (status is AssistantStatus.Ready) shouldBe true
        val ready = status as AssistantStatus.Ready
        ready.stt shouldBe File(dir, stt.file)
        ready.lm shouldBe null
        ready.lmProblem shouldBe "Language model ${lm.file}: SHA-256 mismatch; the file was changed or damaged."
    }

    @Test
    fun `speech and language model both ok, both are ready`() {
        val stt = sttSpec()
        val lm = lmSpec()
        val manifest = ModelManifest(listOf(stt, lm))
        val status = ModelGate.check(manifest, dir) { _, _ -> ModelCheck.Ok }
        (status is AssistantStatus.Ready) shouldBe true
        val ready = status as AssistantStatus.Ready
        ready.stt shouldBe File(dir, stt.file)
        ready.lm shouldBe File(dir, lm.file)
        ready.lmProblem shouldBe null
    }
}
