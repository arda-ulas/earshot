package io.github.ardaulas.earshot.core.trace

import io.github.ardaulas.earshot.core.requirements.Verifies
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

private fun trace(turnId: String = "turn-1") =
    TurnTrace(
        turnId = turnId,
        inputSource = InputSource.CLIP,
        host = HostInfo(device = "pixel-8", emulator = false, abi = "arm64-v8a"),
        drivingState = "PARKED",
        transcript = "set the temperature to 21",
        asrConfidence = 0.9f,
        command = "SetTemp(celsius=21)",
        commandSource = "RULES",
        lmOutcome = null,
        verdict = "Allow",
        outcome = "ACTED",
        spoken = "Temperature is now 21 degrees.",
        stages = listOf(StageTiming("stt", 0.0, 5.0)),
        totalMs = 5.0,
    )

@Verifies("SR-21")
class JsonlTraceWriterTest {
    @Test
    fun `appends one JSON line per trace, with the input source and host note`(
        @TempDir dir: File,
    ) {
        val writer = JsonlTraceWriter(dir, fileKey = { "2026-01-01" })
        writer.write(trace("turn-1"))
        writer.write(trace("turn-2"))

        val file = File(dir, "2026-01-01.jsonl")
        val lines = file.readLines().filter { it.isNotBlank() }
        lines.size shouldBe 2
        for (line in lines) {
            (line.contains("\"inputSource\":\"CLIP\"")) shouldBe true
            (line.contains("Latency measured on this host; not an in-vehicle figure.")) shouldBe true
        }
        (lines[0].contains("\"turnId\":\"turn-1\"")) shouldBe true
        (lines[1].contains("\"turnId\":\"turn-2\"")) shouldBe true
    }

    @Test
    fun `keeps only the newest N files`(
        @TempDir dir: File,
    ) {
        val keys = listOf("d1", "d2", "d3", "d4")
        var index = 0
        val writer = JsonlTraceWriter(dir, fileKey = { keys[index] }, retainFiles = 2)
        for (key in keys) {
            index = keys.indexOf(key)
            writer.write(trace(key))
        }

        val remaining =
            dir
                .listFiles { f -> f.name.endsWith(".jsonl") }
                .orEmpty()
                .map { it.nameWithoutExtension }
                .toSet()
        remaining shouldBe setOf("d3", "d4")
    }
}
