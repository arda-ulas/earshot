package io.github.ardaulas.earshot.core.model

import io.github.ardaulas.earshot.core.requirements.Verifies
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@Verifies("SR-12")
class ModelStoreTest {
    @TempDir
    lateinit var tmp: File

    private val bytes = "model-bytes".toByteArray()

    private fun spec(content: ByteArray = bytes): ModelSpec {
        val f = File(tmp, "probe").apply { writeBytes(content) }
        return ModelSpec("m", "stt", "m.bin", "https://example.invalid/m.bin", content.size.toLong(), ModelVerifier.sha256(f), "MIT")
    }

    private val shared get() = File(tmp, "shared").apply { mkdirs() }
    private val private get() = File(tmp, "private")

    @Test
    fun `a verified shared model is copied into private storage and loaded from there`() {
        val s = spec()
        File(shared, "m.bin").writeBytes(bytes)
        val (file, check) = ModelStore.snapshot(s, shared, private)
        check shouldBe ModelCheck.Ok
        file shouldBe File(private, "m.bin")
        file.readBytes().toList() shouldBe bytes.toList()
    }

    @Test
    fun `swapping the shared file after the snapshot does not change what is loaded`() {
        val s = spec()
        File(shared, "m.bin").writeBytes(bytes)
        ModelStore.snapshot(s, shared, private)
        File(shared, "m.bin").writeBytes("evil-bytes!".toByteArray())
        val (file, check) = ModelStore.snapshot(s, shared, private)
        check shouldBe ModelCheck.Ok
        file.readBytes().toList() shouldBe bytes.toList()
    }

    @Test
    fun `a damaged private copy is replaced from a good shared copy`() {
        val s = spec()
        File(shared, "m.bin").writeBytes(bytes)
        private.mkdirs()
        File(private, "m.bin").writeBytes("damaged!!!!".toByteArray())
        val (_, check) = ModelStore.snapshot(s, shared, private)
        check shouldBe ModelCheck.Ok
    }

    @Test
    fun `a tampered shared copy with no private copy is refused`() {
        val s = spec()
        File(shared, "m.bin").writeBytes("evil-bytes!".toByteArray())
        val (_, check) = ModelStore.snapshot(s, shared, private)
        check.shouldBeInstanceOf<ModelCheck.HashMismatch>()
        File(private, "m.bin").exists() shouldBe false
    }

    @Test
    fun `the gate returns private paths when given a private directory`() {
        val s = spec()
        File(shared, "m.bin").writeBytes(bytes)
        val status = ModelGate.check(ModelManifest(listOf(s)), shared, private)
        status.shouldBeInstanceOf<AssistantStatus.Ready>()
        status.stt shouldBe File(private, "m.bin")
    }
}
