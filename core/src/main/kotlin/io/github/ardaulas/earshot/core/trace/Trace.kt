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
) : TraceSink {
    @Synchronized
    override fun write(trace: TurnTrace) {
        dir.mkdirs()
        File(dir, "${fileKey()}.jsonl").appendText(json.encodeToString(TurnTrace.serializer(), trace) + "\n")
        dir
            .listFiles { f -> f.name.endsWith(".jsonl") }
            ?.sortedByDescending { it.name }
            ?.drop(retainFiles)
            ?.forEach { it.delete() }
    }

    companion object {
        val json = Json { encodeDefaults = true }
    }
}
