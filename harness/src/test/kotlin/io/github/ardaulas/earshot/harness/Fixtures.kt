package io.github.ardaulas.earshot.harness

import io.github.ardaulas.earshot.core.interpret.Example
import io.github.ardaulas.earshot.core.interpret.LmEngine
import java.nio.file.Path
import java.nio.file.Paths

/** The tiny text-only suite in src/test/resources/fixture. */
fun fixtureDir(): Path = Paths.get(checkNotNull(Fixtures::class.java.getResource("/fixture/suite.yaml")).toURI()).parent

object Fixtures {
    val suite: Suite by lazy { TestSetLoader.load(fixtureDir()) }
}

/** A language model that always answers [raw]. */
class ConstantLm(
    private val raw: String,
) : LmEngine {
    var calls = 0

    override suspend fun complete(
        system: String,
        examples: List<Example>,
        user: String,
        grammar: String,
        maxTokens: Int,
        assistantPrefix: String,
    ): String {
        calls++
        return raw
    }
}

/** Transcripts from a map by clip id, falling back to the reference; for simulating misrecognition. */
class ScriptedSource(
    private val texts: Map<String, Pair<String, Float?>>,
) : TranscriptSource {
    override val name = "scripted"
    override val needsAudio = false

    override fun transcribe(
        clip: Clip,
        audio: Path?,
    ): SourceTranscript {
        val (text, confidence) = texts[clip.id] ?: (clip.transcript to 1.0f)
        return SourceTranscript(text, confidence, 1.0)
    }
}
