package io.github.ardaulas.earshot.core.turn

import io.github.ardaulas.earshot.core.interpret.Example
import io.github.ardaulas.earshot.core.interpret.LmEngine
import io.github.ardaulas.earshot.core.speech.SpeechEngine
import io.github.ardaulas.earshot.core.speech.Transcript
import io.github.ardaulas.earshot.core.trace.TraceSink
import io.github.ardaulas.earshot.core.trace.TurnTrace
import io.github.ardaulas.earshot.core.vehicle.ClimateProperty
import io.github.ardaulas.earshot.core.vehicle.ReadResult
import io.github.ardaulas.earshot.core.vehicle.SignalSample
import io.github.ardaulas.earshot.core.vehicle.VehicleGateway
import io.github.ardaulas.earshot.core.vehicle.WriteResult
import kotlinx.coroutines.delay

/** A scripted [SpeechEngine]: a queue of transcripts (each optionally delayed), or a thrown error. */
class FakeSpeech(
    private val results: MutableList<Result> = mutableListOf(),
) : SpeechEngine {
    sealed interface Result {
        data class Ok(
            val transcript: Transcript,
            val delayMs: Long = 0,
        ) : Result

        data class Throws(
            val error: Throwable,
        ) : Result
    }

    var callCount = 0
        private set

    fun queue(
        text: String,
        confidence: Float?,
        delayMs: Long = 0,
    ) = apply { results += Result.Ok(Transcript(text, confidence), delayMs) }

    fun queueThrow(error: Throwable = IllegalStateException("stt failure")) = apply { results += Result.Throws(error) }

    override suspend fun transcribe(pcm16k: FloatArray): Transcript {
        callCount++
        val next = if (results.isNotEmpty()) results.removeAt(0) else Result.Ok(Transcript("", null))
        return when (next) {
            is Result.Ok -> {
                if (next.delayMs > 0) delay(next.delayMs)
                next.transcript
            }

            is Result.Throws -> {
                throw next.error
            }
        }
    }
}

/** A scripted [LmEngine]: a queue of raw outputs, a hang, or a thrown error. */
class FakeLm(
    private val results: MutableList<Result> = mutableListOf(),
) : LmEngine {
    sealed interface Result {
        data class Returns(
            val text: String,
        ) : Result

        data object Hangs : Result

        data class Throws(
            val error: Throwable,
        ) : Result
    }

    var callCount = 0
        private set

    fun queueReturns(text: String) = apply { results += Result.Returns(text) }

    fun queueHangs() = apply { results += Result.Hangs }

    fun queueThrows(error: Throwable = IllegalStateException("lm failure")) = apply { results += Result.Throws(error) }

    override suspend fun complete(
        system: String,
        examples: List<Example>,
        user: String,
        grammar: String,
        maxTokens: Int,
        assistantPrefix: String,
    ): String {
        callCount++
        return when (val next = if (results.isNotEmpty()) results.removeAt(0) else Result.Returns("""{"intent":"out_of_domain"}""")) {
            is Result.Returns -> {
                next.text
            }

            Result.Hangs -> {
                delay(Long.MAX_VALUE)
                error("unreachable")
            }

            is Result.Throws -> {
                throw next.error
            }
        }
    }
}

/**
 * Wraps a real [VehicleGateway] with switches for fault injection: forcing the connection down,
 * rejecting or hanging writes, and overriding what a read reports (to test that the spoken
 * confirmation comes from the read-back, not the request).
 */
class FaultInjectingGateway(
    private val delegate: VehicleGateway,
) : VehicleGateway {
    var forceUnavailable = false
    var rejectWrites = false
    var hangWrites = false
    val readOverride: MutableMap<ClimateProperty, Int> = mutableMapOf()
    var signalsOverride: SignalSample? = null

    /** Virtual-time delay on every read (a slow gateway), and a hook that runs during each read. */
    var readDelayMs = 0L
    var onRead: (() -> Unit)? = null

    var writeCount = 0
        private set
    var readCount = 0
        private set

    override val isAvailable: Boolean get() = !forceUnavailable && delegate.isAvailable

    override suspend fun read(property: ClimateProperty): ReadResult {
        readCount++
        onRead?.invoke()
        if (readDelayMs > 0) delay(readDelayMs)
        if (forceUnavailable) return ReadResult.Unavailable
        readOverride[property]?.let { return ReadResult.Value(it) }
        return delegate.read(property)
    }

    override suspend fun write(
        property: ClimateProperty,
        value: Int,
    ): WriteResult {
        writeCount++
        return when {
            forceUnavailable -> {
                WriteResult.Unavailable
            }

            rejectWrites -> {
                WriteResult.Rejected
            }

            hangWrites -> {
                delay(Long.MAX_VALUE)
                error("unreachable")
            }

            else -> {
                delegate.write(property, value)
            }
        }
    }

    override fun latestSignals(): SignalSample? {
        if (forceUnavailable) return null
        signalsOverride?.let { return it }
        return delegate.latestSignals()
    }
}

/** Collects every trace written during a test, in order. */
class RecordingTraceSink : TraceSink {
    val traces = mutableListOf<TurnTrace>()

    /** When true, every write throws, like a full disk. */
    var fail = false

    override fun write(trace: TurnTrace) {
        if (fail) throw java.io.IOException("disk full")
        traces += trace
    }
}
