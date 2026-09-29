package io.github.ardaulas.earshot.core.speech

/** A transcript and how sure the engine is of it, in 0..1. Null confidence means unknown (no action). */
data class Transcript(
    val text: String,
    val confidence: Float?,
)

/** Offline speech-to-text over one push-to-talk utterance: 16 kHz mono PCM as floats in -1..1. */
interface SpeechEngine {
    suspend fun transcribe(pcm16k: FloatArray): Transcript
}
