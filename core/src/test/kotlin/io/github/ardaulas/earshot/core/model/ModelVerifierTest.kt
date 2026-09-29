package io.github.ardaulas.earshot.core.model

import io.github.ardaulas.earshot.core.requirements.Verifies
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@Verifies("SR-12")
class ModelVerifierTest {
    @Test
    fun `a file matching size and hash is Ok`(
        @TempDir dir: File,
    ) {
        val file = File(dir, "model.bin").apply { writeBytes("hello model".toByteArray()) }
        val spec = spec(sizeBytes = file.length(), sha256 = ModelVerifier.sha256(file))
        ModelVerifier.verify(spec, file) shouldBe ModelCheck.Ok
    }

    @Test
    fun `a missing file is Missing`(
        @TempDir dir: File,
    ) {
        val file = File(dir, "does-not-exist.bin")
        val spec = spec(sizeBytes = 10, sha256 = "0".repeat(64))
        ModelVerifier.verify(spec, file) shouldBe ModelCheck.Missing
    }

    @Test
    fun `a file of the wrong size is SizeMismatch`(
        @TempDir dir: File,
    ) {
        val file = File(dir, "model.bin").apply { writeBytes("12345".toByteArray()) }
        val spec = spec(sizeBytes = 999, sha256 = "0".repeat(64))
        ModelVerifier.verify(spec, file) shouldBe ModelCheck.SizeMismatch(expected = 999, actual = 5)
    }

    @Test
    fun `a file of the right size but wrong content is HashMismatch`(
        @TempDir dir: File,
    ) {
        val content = "abcabc".toByteArray()
        val file = File(dir, "model.bin").apply { writeBytes(content) }
        val wrongHash = "0".repeat(64)
        val spec = spec(sizeBytes = content.size.toLong(), sha256 = wrongHash)
        val result = ModelVerifier.verify(spec, file)
        (result is ModelCheck.HashMismatch) shouldBe true
        (result as ModelCheck.HashMismatch).actual shouldBe ModelVerifier.sha256(file)
    }

    @Test
    fun `an unreadable file is Unreadable`(
        @TempDir dir: File,
    ) {
        val file = File(dir, "model.bin").apply { writeBytes("hello model".toByteArray()) }
        val spec = spec(sizeBytes = file.length(), sha256 = ModelVerifier.sha256(file))
        try {
            file.setReadable(false)
            if (file.canRead()) return // Running as a user that ignores the permission bit; nothing to test here.
            val result = ModelVerifier.verify(spec, file)
            (result is ModelCheck.Unreadable) shouldBe true
        } finally {
            file.setReadable(true)
        }
    }

    @Test
    fun `parses a manifest, defaulting required to true`() {
        val text =
            """
            {"models":[
              {"id":"stt-a","role":"stt","file":"a.bin","url":"https://x/a","sizeBytes":10,"sha256":"aa","license":"MIT"},
              {"id":"lm-a","role":"lm","file":"b.bin","url":"https://x/b","sizeBytes":20,"sha256":"bb","license":"MIT","required":false}
            ]}
            """.trimIndent()
        val manifest = ModelManifest.parse(text)
        manifest.models.size shouldBe 2
        manifest.models[0].required shouldBe true
        manifest.models[1].required shouldBe false
        manifest.models[0].role shouldBe "stt"
        manifest.models[1].role shouldBe "lm"
    }

    private fun spec(
        sizeBytes: Long,
        sha256: String,
    ) = ModelSpec(
        id = "test-model",
        role = "stt",
        file = "model.bin",
        url = "https://example.invalid/model.bin",
        sizeBytes = sizeBytes,
        sha256 = sha256,
        license = "MIT",
    )
}
