package io.github.ardaulas.earshot.whisper

import io.github.ardaulas.earshot.core.concurrent.runAbortable
import io.github.ardaulas.earshot.core.speech.AudioGate
import io.github.ardaulas.earshot.core.speech.SpeechEngine
import io.github.ardaulas.earshot.core.speech.Transcript
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.Closeable
import java.io.File

/**
 * whisper.cpp speech-to-text. Load it only from a model file that passed `ModelVerifier`.
 * Confidence is the mean probability of the transcribed text tokens.
 */
class WhisperSpeechEngine private constructor(
    private val handle: Long,
    private val threads: Int,
    private val dispatcher: CoroutineDispatcher,
) : SpeechEngine,
    Closeable {
    override suspend fun transcribe(pcm16k: FloatArray): Transcript {
        if (!AudioGate.hasSpeech(pcm16k)) return Transcript("", 0f)
        val audio = AudioGate.bound(pcm16k)
        val confidence = FloatArray(1)
        val text =
            runAbortable(dispatcher, abort = { WhisperNative.abort(handle) }) {
                WhisperNative.transcribe(handle, audio, threads, confidence)
            } ?: return Transcript("", null)
        return AudioGate.transcript(text, confidence[0].takeIf { it >= 0f })
    }

    override fun close() = WhisperNative.free(handle)

    companion object {
        /** Loads the model; null if whisper.cpp could not read it. Blocking: call off the main thread. */
        fun load(
            model: File,
            threads: Int = 4,
            dispatcher: CoroutineDispatcher = Dispatchers.Default,
        ): WhisperSpeechEngine? {
            val handle = WhisperNative.init(model.absolutePath)
            return if (handle == 0L) null else WhisperSpeechEngine(handle, threads, dispatcher)
        }
    }
}
