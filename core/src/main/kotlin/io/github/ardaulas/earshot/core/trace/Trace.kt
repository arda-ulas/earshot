package io.github.ardaulas.earshot.core.trace

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Whether a turn's audio came from the microphone or from a pre-recorded clip. */
@Serializable
enum class InputSource { MIC, CLIP }

/**
 * Where the latency numbers were measured. Every trace carries it so no figure is ever read as an
 * in-vehicle measurement.
 */
@Serializable
data class HostInfo(
    val device: String,
    val emulator: Boolean,
    val abi: String,
    val note: String = "Latency measured on this host; not an in-vehicle figure.",
)

@Serializable
data class StageTiming(
    val stage: String,
    /** Start, in ms since the turn began (monotonic clock). */
    val startMs: Double,
    val durationMs: Double,
)

/** One push-to-talk turn, written as one JSONL line. */
@Serializable
data class TurnTrace(
    val turnId: String,
    val inputSource: InputSource,
    val host: HostInfo,
    val drivingState: String,
    val transcript: String?,
    val asrConfidence: Float?,
    val command: String?,
    val commandSource: String?,
    val lmOutcome: String?,
    val verdict: String?,
    val outcome: String,
    val spoken: String,
    val stages: List<StageTiming>,
    val totalMs: Double,
)

fun interface TraceSink {
    fun write(trace: TurnTrace)
}

/**
 * Appends traces as JSONL, one file per [fileKey] (for example the date), keeping only the newest
 * [retainFiles] files. The directory should be app-private storage.
 */
class JsonlTraceWriter(
    private val dir: File,
    private val fileKey: () -> String,
    private val retainFiles: Int = 7,
    /** Files older than this are deleted on every write (audit #18). */
    private val maxAgeMs: Long = 7L * 24 * 60 * 60 * 1000,
    /**
     * No file grows past this size: a turn that would cross it is not stored, and [write] throws so
     * the turn reports it (re-audit 3, N16).
     */
    private val maxFileBytes: Long = 1_000_000,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
) : TraceSink {
    @Synchronized
    override fun write(trace: TurnTrace) {
        dir.mkdirs()
        val file = File(dir, "${fileKey()}.jsonl")
        val line = (json.encodeToString(TurnTrace.serializer(), trace) + "\n").toByteArray()
        if (file.length() + line.size > maxFileBytes) throw java.io.IOException("trace file full; this turn was not stored")
        file.appendBytes(line)
        purge()
    }

    /**
     * Deletes files beyond [retainFiles] or older than [maxAgeMs]. Runs after every write; the app
     * also calls it at start-up, so old traces go even if no turn follows.
     */
    @Synchronized
    fun purge() {
        val now = wallClockMs()
        val files = dir.listFiles { f -> f.name.endsWith(".jsonl") }.orEmpty().sortedByDescending { it.name }
        val expired = files.drop(retainFiles) + files.filter { now - it.lastModified() > maxAgeMs }
        val failed = expired.distinct().filterNot { it.delete() || !it.exists() }
        if (failed.isNotEmpty()) throw java.io.IOException("could not delete ${failed.size} old trace file(s)")
    }

    companion object {
        val json = Json { encodeDefaults = true }
    }
}
