package io.github.ardaulas.earshot.whisper

import io.github.ardaulas.earshot.core.concurrent.runAbortable
import io.github.ardaulas.earshot.core.speech.AudioGate
import io.github.ardaulas.earshot.core.speech.SpeechEngine
import io.github.ardaulas.earshot.core.speech.Transcript
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val mutex = Mutex()
    private val lock = Any()
    private var closed = false
    private var active = false
    private var freed = false

    override suspend fun transcribe(pcm16k: FloatArray): Transcript {
        if (!AudioGate.hasSpeech(pcm16k)) return Transcript("", 0f)
        val audio = AudioGate.bound(pcm16k)
        val confidence = FloatArray(1)
        return mutex.withLock {
            synchronized(lock) {
                if (closed) return@withLock Transcript("", null)
                // Reset before the call is marked active, so an abort from close() cannot be erased (re-audit N10).
                WhisperNative.resetAbort(handle)
                active = true
            }
            try {
                val text =
                    runAbortable(dispatcher, abort = { WhisperNative.abort(handle) }) {
                        WhisperNative.transcribe(handle, audio, threads, confidence)
                    }
                        ?: return@withLock Transcript("", null)
                AudioGate.transcript(text, confidence[0].takeIf { it >= 0f })
            } finally {
                synchronized(lock) {
                    active = false
                    if (closed) freeLocked()
                }
            }
        }
    }

    /**
     * Idempotent. Never frees under a running call: it aborts that call, which frees the handle on
     * its way out (audit #14).
     */
    override fun close() =
        synchronized(lock) {
            if (closed) return
            closed = true
            if (active) WhisperNative.abort(handle) else freeLocked()
        }

    private fun freeLocked() {
        if (!freed) {
            freed = true
            WhisperNative.free(handle)
        }
    }

    companion object {
        /** Loads the model; null if whisper.cpp could not read it. Blocking: call off the main thread. */
        fun load(
            model: File,
            threads: Int = 2,
            dispatcher: CoroutineDispatcher = Dispatchers.Default,
        ): WhisperSpeechEngine? {
            val handle = WhisperNative.init(model.absolutePath)
            return if (handle == 0L) null else WhisperSpeechEngine(handle, threads, dispatcher)
        }
    }
}
