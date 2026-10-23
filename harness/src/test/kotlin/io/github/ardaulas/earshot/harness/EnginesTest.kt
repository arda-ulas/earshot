package io.github.ardaulas.earshot.harness

import io.github.ardaulas.earshot.core.command.Command
import io.github.ardaulas.earshot.core.interpret.Example
import io.github.ardaulas.earshot.core.interpret.LmInterpreter
import io.github.ardaulas.earshot.core.interpret.LmOutcome
import io.github.ardaulas.earshot.core.interpret.LmWireFormat
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class EnginesTest {
    /** A host binary stand-in: a shell script answering each stdin line with [reply] (which may use $l). */
    private fun script(
        dir: Path,
        name: String,
        body: String,
    ): File {
        val f = dir.resolve(name).toFile()
        f.writeText("#!/bin/sh\n$body\n")
        f.setExecutable(true)
        return f
    }

    private val clip = Fixtures.suite.clips.first()

    @Test
    fun `whisper-style host replies are parsed`(
        @TempDir dir: Path,
    ) {
        val bin =
            script(
                dir,
                "stt.sh",
                """while IFS= read -r l; do printf '{"text":"heard %s","confidence":0.75,"ms":12.5}\n' "${'$'}(basename "${'$'}l")"; done""",
            )
        SubprocessSource("whisper", JsonLinesProcess(listOf(bin.path))).use { src ->
            src.transcribe(clip, dir.resolve("a.wav")) shouldBe SourceTranscript("heard a.wav", 0.75f, 12.5)
            src.transcribe(clip, dir.resolve("b.wav")).text shouldBe "heard b.wav"
        }
    }

    @Test
    fun `host errors, null confidence and bad replies`() {
        SubprocessSource.parse(obj("""{"error":"bad wav"}""")) shouldBe SourceTranscript("", null, null, "bad wav")
        SubprocessSource.parse(obj("""{"text":"x","confidence":null,"ms":1}""")) shouldBe SourceTranscript("x", null, 1.0)
        SubprocessSource.parse(obj("""{"text":"x","confidence":1.5}""")).confidence shouldBe null
        SubprocessSource.parse(obj("""{"confidence":0.9}""")).error shouldBe "no text in reply"
    }

    @Test
    fun `a host that dies gives an error transcript, not a crash`(
        @TempDir dir: Path,
    ) {
        val bin = script(dir, "dead.sh", "exit 3")
        SubprocessSource("whisper", JsonLinesProcess(listOf(bin.path)), timeoutMs = 5_000).use { src ->
            val t = src.transcribe(clip, dir.resolve("a.wav"))
            t.confidence shouldBe null
            t.error!! shouldContain "exited"
        }
    }

    @Test
    fun `the lmhost spec file matches lmhost's format`() {
        val spec =
            HostLmEngine.specText(
                HostLmEngine.Prompt(
                    "line one\nline\ttwo \\d",
                    listOf(Example("I'm cold", "{\"intent\":\"warmer\"}")),
                    "root ::= x\n",
                    "<think>\n\n</think>\n\n",
                    16,
                ),
            )
        spec shouldBe
            "system\tline one\\nline\\ttwo \\\\d\n" +
            "user\tI'm cold\n" +
            "assistant\t{\"intent\":\"warmer\"}\n" +
            "assistant_prefix\t<think>\\n\\n</think>\\n\\n\n" +
            "max_tokens\t16\n"
    }

    private val prompt =
        HostLmEngine.Prompt(
            LmWireFormat.SYSTEM_PROMPT,
            LmWireFormat.EXAMPLES,
            LmWireFormat.GRAMMAR,
            LmWireFormat.ASSISTANT_PREFIX,
            LmWireFormat.MAX_TOKENS,
        )

    @Test
    fun `core's LmInterpreter runs over the lmhost protocol`(
        @TempDir dir: Path,
    ) {
        // Checks it was started as "lmhost MODEL SPEC GRAMMAR" with readable files, then always answers "warmer".
        val bin =
            script(
                dir,
                "lm.sh",
                """[ -f "${'$'}2" ] && [ -f "${'$'}3" ] || exit 9
                while IFS= read -r l; do printf '{"raw":"{\\"intent\\":\\"warmer\\"}","ms":42.0}\n'; done""",
            )
        HostLmEngine.start(bin, File("model.gguf"), prompt, dir.resolve("work")).use { engine ->
            val outcome = runBlocking { LmInterpreter(engine).interpret("I'm freezing\nreally") }
            outcome shouldBe LmOutcome.Parsed(Command.AdjustTemp(2), "{\"intent\":\"warmer\"}")
            engine.lastMs shouldBe 42.0
            Files.readString(dir.resolve("work/lm.gbnf")) shouldBe LmWireFormat.GRAMMAR
        }
    }

    @Test
    fun `a call with a different prompt fails instead of using the cached one`(
        @TempDir dir: Path,
    ) {
        val bin = script(dir, "lm.sh", """while IFS= read -r l; do printf '{"raw":"x","ms":1}\n'; done""")
        HostLmEngine.start(bin, File("m"), prompt, dir.resolve("work")).use { engine ->
            val outcome = runBlocking { LmInterpreter(engine).interpret("hi") }
            outcome shouldBe LmOutcome.Invalid("x")
            val other = runCatching { runBlocking { engine.complete("other", emptyList(), "hi", LmWireFormat.GRAMMAR, 16, "") } }
            other.exceptionOrNull().shouldBeInstanceOf<IllegalStateException>()
        }
    }

    @Test
    fun `a timed-out answer is skipped so the next request gets its own`(
        @TempDir dir: Path,
    ) {
        // The first answer arrives after 1.5 s; later ones at once.
        val bin =
            script(
                dir,
                "slow.sh",
                """n=0
                while IFS= read -r l; do
                  n=${'$'}((n+1)); [ ${'$'}n -eq 1 ] && sleep 1.5
                  printf '{"raw":"{\\"intent\\":\\"%s\\"}","ms":1}\n' "${'$'}l"
                done""",
            )
        HostLmEngine.start(bin, File("m"), prompt, dir.resolve("work"), timeoutMs = 10_000).use { engine ->
            runBlocking { LmInterpreter(engine, timeoutMs = 300).interpret("cooler") } shouldBe LmOutcome.TimedOut
            // The late "cooler" answer is read and dropped; this call gets "ac_on".
            runBlocking { LmInterpreter(engine, timeoutMs = 5_000).interpret("ac_on") } shouldBe
                LmOutcome.Parsed(Command.SetAc(true), "{\"intent\":\"ac_on\"}")
        }
    }

    private fun obj(s: String) =
        kotlinx.serialization.json.Json
            .parseToJsonElement(s) as kotlinx.serialization.json.JsonObject
}
