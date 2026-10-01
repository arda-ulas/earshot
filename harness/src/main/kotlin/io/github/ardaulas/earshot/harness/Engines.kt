package io.github.ardaulas.earshot.harness

import io.github.ardaulas.earshot.core.interpret.Example
import io.github.ardaulas.earshot.core.interpret.LmEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** One transcript from a speech-to-text source. [ms] is the time the engine reports for itself. */
data class SourceTranscript(
    val text: String,
    /** Mean token probability in 0..1, or null when the engine does not know. */
    val confidence: Float?,
    val ms: Double?,
    val error: String? = null,
)

/** Where transcripts come from: the labels themselves, or a speech-to-text engine run on the audio. */
interface TranscriptSource : Closeable {
    val name: String

    /** True if this source reads audio; false for text-only sources, which run once per clip. */
    val needsAudio: Boolean

    fun transcribe(
        clip: Clip,
        audio: Path?,
    ): SourceTranscript

    override fun close() {}
}

/** The reference transcript from the labels, at full confidence. Tests the text path without models. */
class ReferenceSource : TranscriptSource {
    override val name = "reference"
    override val needsAudio = false

    override fun transcribe(
        clip: Clip,
        audio: Path?,
    ) = SourceTranscript(clip.transcript, 1.0f, null)
}

class HostProcessException(
    message: String,
) : Exception(message)

/**
 * A host binary that reads one request per stdin line and writes one JSON object per stdout line.
 * A reader thread feeds a queue, so a wait can time out or be interrupted. Standard error is passed
 * through to this process's standard error.
 */
class JsonLinesProcess(
    command: List<String>,
) : Closeable {
    private val process: Process =
        ProcessBuilder(command)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
    private val stdin: BufferedWriter = process.outputStream.bufferedWriter(Charsets.UTF_8)
    private val lines = LinkedBlockingQueue<String>()
    private val reader =
        Thread({
            process.inputStream.bufferedReader(Charsets.UTF_8).useLines { seq -> seq.forEach { lines.put(it) } }
            lines.put(EOF)
        }, "host-stdout").apply {
            isDaemon = true
            start()
        }

    /** Responses still owed for requests whose caller gave up waiting; skipped before the next answer. */
    private var stale = 0

    val description: String = command.joinToString(" ")

    /** Sends one request line and waits for its JSON response. */
    @Synchronized
    fun request(
        line: String,
        timeoutMs: Long,
    ): JsonObject {
        require('\n' !in line && '\r' !in line) { "a request must be one line" }
        while (stale > 0) {
            take(timeoutMs)
            stale--
        }
        try {
            stdin.write(line)
            stdin.newLine()
            stdin.flush()
        } catch (e: java.io.IOException) {
            // The host already exited (broken pipe): report that, like a reply that never comes.
            throw HostProcessException("$description: exited with ${process.waitFor()} (${e.message})")
        }
        stale++
        val response = take(timeoutMs)
        stale--
        return try {
            Json.parseToJsonElement(response) as? JsonObject
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            null
        } ?: throw HostProcessException("$description: not a JSON object: ${response.take(MAX_ECHO)}")
    }

    private fun take(timeoutMs: Long): String {
        val line = lines.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: throw HostProcessException("$description: no response in $timeoutMs ms")
        if (line === EOF) {
            lines.put(EOF)
            throw HostProcessException("$description: exited with ${process.waitFor()}")
        }
        return line
    }

    override fun close() {
        runCatching { stdin.close() }
        if (!process.waitFor(CLOSE_WAIT_S, TimeUnit.SECONDS)) process.destroyForcibly()
        reader.join(TimeUnit.SECONDS.toMillis(CLOSE_WAIT_S))
    }

    private companion object {
        val EOF = String(charArrayOf('\u0000'))
        const val MAX_ECHO = 200
        const val CLOSE_WAIT_S = 5L
    }
}

/**
 * Speech-to-text through a host binary (whisperhost). Request: the absolute WAV path. Response:
 * `{"text":..., "confidence": <0..1 or null>, "ms": <float>}`. An `"error"` field, a malformed reply
 * or a timeout gives an empty transcript with unknown confidence, which the policy re-prompts on.
 */
class SubprocessSource(
    override val name: String,
    private val process: JsonLinesProcess,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : TranscriptSource {
    override val needsAudio = true

    override fun transcribe(
        clip: Clip,
        audio: Path?,
    ): SourceTranscript {
        requireNotNull(audio) { "clip ${clip.id} has no audio" }
        return try {
            val obj = process.request(audio.toAbsolutePath().toString(), timeoutMs)
            parse(obj)
        } catch (e: HostProcessException) {
            SourceTranscript("", null, null, e.message)
        }
    }

    override fun close() = process.close()

    companion object {
        const val DEFAULT_TIMEOUT_MS = 60_000L

        fun parse(obj: JsonObject): SourceTranscript {
            val error = (obj["error"] as? JsonPrimitive)?.content
            if (error != null) return SourceTranscript("", null, null, error)
            val text = (obj["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val confidence = (obj["confidence"] as? JsonPrimitive)?.floatOrNull?.takeIf { it.isFinite() && it in 0f..1f }
            val ms = (obj["ms"] as? JsonPrimitive)?.doubleOrNull
            return if (text == null) SourceTranscript("", null, ms, "no text in reply") else SourceTranscript(text, confidence, ms)
        }
    }
}

/**
 * Core's [LmEngine] over the lmhost binary. lmhost is started once with a spec file (system prompt,
 * examples, assistant prefix) and a grammar file, and keeps that prefix in its KV cache, so every call
 * must use the same prompt: a call with a different one fails rather than silently using the wrong
 * prompt. Request: the user text on one line. Response: `{"raw": "<generated>", "ms": <float>}`.
 */
class HostLmEngine private constructor(
    private val process: JsonLinesProcess,
    private val prompt: Prompt,
    private val timeoutMs: Long,
) : LmEngine,
    Closeable {
    data class Prompt(
        val system: String,
        val examples: List<Example>,
        val grammar: String,
        val assistantPrefix: String,
        val maxTokens: Int,
    )

    /** Time lmhost reported for the most recent call, for the latency table. */
    @Volatile var lastMs: Double? = null
        private set

    override suspend fun complete(
        system: String,
        examples: List<Example>,
        user: String,
        grammar: String,
        maxTokens: Int,
        assistantPrefix: String,
    ): String {
        check(Prompt(system, examples, grammar, assistantPrefix, maxTokens) == prompt) { "lmhost was started with a different prompt" }
        lastMs = null
        // Interruptible, so LmInterpreter's timeout stops the wait; lmhost's late answer is skipped later.
        val obj = runInterruptible(Dispatchers.IO) { process.request(oneLine(user), timeoutMs) }
        (obj["error"] as? JsonPrimitive)?.let { throw HostProcessException("lmhost: ${it.content}") }
        lastMs = (obj["ms"] as? JsonPrimitive)?.doubleOrNull
        return obj["raw"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: throw HostProcessException("lmhost: no raw in reply")
    }

    override fun close() = process.close()

    companion object {
        /**
         * Writes [prompt] as lmhost's spec and grammar files into [workDir] and starts
         * `binary [options] model spec grammar`.
         */
        fun start(
            binary: File,
            model: File,
            prompt: Prompt,
            workDir: Path,
            options: List<String> = emptyList(),
            timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        ): HostLmEngine {
            Files.createDirectories(workDir)
            val spec = workDir.resolve("lm.spec")
            val grammar = workDir.resolve("lm.gbnf")
            Files.writeString(spec, specText(prompt))
            Files.writeString(grammar, prompt.grammar)
            val command = listOf(binary.path) + options + listOf(model.path, spec.toString(), grammar.toString())
            return HostLmEngine(JsonLinesProcess(command), prompt, timeoutMs)
        }

        const val DEFAULT_TIMEOUT_MS = 30_000L

        /**
         * lmhost's spec format (tools/host/lmhost/README.md): `key<TAB>value` lines, with backslash,
         * tab and newline written as `\\`, `\t` and `\n`.
         */
        fun specText(prompt: Prompt): String =
            buildString {
                fun line(
                    key: String,
                    value: String,
                ) {
                    val escaped = value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
                    require('\r' !in escaped) { "a spec value cannot contain a carriage return" }
                    append(key).append('\t').append(escaped).append('\n')
                }
                line("system", prompt.system)
                prompt.examples.forEach {
                    line("user", it.user)
                    line("assistant", it.assistant)
                }
                if (prompt.assistantPrefix.isNotEmpty()) line("assistant_prefix", prompt.assistantPrefix)
                line("max_tokens", prompt.maxTokens.toString())
            }

        /** A transcript is sent as one line; line breaks become spaces. */
        fun oneLine(text: String): String = text.replace(Regex("[\r\n]+"), " ")
    }
}

/**
 * What a host binary says about itself with `--info` (engine, version, source SHA-256, threads, CPU
 * features), as flat strings for the report. Empty if the binary does not answer.
 */
fun hostEngineInfo(
    binary: File,
    options: List<String> = emptyList(),
): Map<String, String> =
    runCatching {
        val p = ProcessBuilder(listOf(binary.path) + options + "--info").redirectErrorStream(false).start()
        val line = p.inputStream.bufferedReader().readLine()
        p.waitFor(INFO_WAIT_S, TimeUnit.SECONDS)
        (Json.parseToJsonElement(line) as JsonObject).mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: v.toString() }
    }.getOrDefault(emptyMap())

private const val INFO_WAIT_S = 10L
