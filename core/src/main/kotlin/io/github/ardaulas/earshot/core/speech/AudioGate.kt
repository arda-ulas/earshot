package io.github.ardaulas.earshot.core.speech

import kotlin.math.sqrt

/**
 * Cheap checks before and after speech-to-text. Whisper tends to invent text for silence ("Thank
 * you."), so near-silent or very short audio is never transcribed, and bracketed non-speech tags
 * such as "[BLANK_AUDIO]" are stripped. Either case yields confidence 0: re-prompt, never guess (SG-1).
 */
object AudioGate {
    const val SAMPLE_RATE = 16_000
    const val MIN_SECONDS = 0.3
    const val MAX_SECONDS = 8.0
    const val MIN_RMS = 0.003

    fun rms(pcm: FloatArray): Double {
        if (pcm.isEmpty()) return 0.0
        var sum = 0.0
        for (s in pcm) sum += s.toDouble() * s
        return sqrt(sum / pcm.size)
    }

    /** True when the audio is long and loud enough to be worth transcribing. */
    fun hasSpeech(pcm: FloatArray): Boolean = pcm.size >= MIN_SECONDS * SAMPLE_RATE && rms(pcm) >= MIN_RMS

    /** Keeps at most [MAX_SECONDS] of audio (bounded utterance length). */
    fun bound(pcm: FloatArray): FloatArray {
        val max = (MAX_SECONDS * SAMPLE_RATE).toInt()
        return if (pcm.size <= max) pcm else pcm.copyOf(max)
    }

    private val DELIMITED = Regex("""\[([^\]]*)]|\(([^)]*)\)|\*([^*]*)\*""")

    /** Whisper's own non-speech annotations. Anything else in brackets may be words and is kept. */
    private val NON_SPEECH_TAG =
        Regex(
            """^\s*(blank_audio|silence|music|noise|applause|laughter|laughs|inaudible|static|wind|beep|coughs?|sighs?|""" +
                """(soft |upbeat |dramatic |gentle )?music( playing)?|no speech|foreign language|speaking in foreign language|""" +
                """[a-z ]*(blowing|playing|chirping|humming|buzzing|ringing|clicking|rustling|running|honking))\s*$""",
            RegexOption.IGNORE_CASE,
        )

    /**
     * Removes whisper's known non-speech tags ("[BLANK_AUDIO]", "(music)"). Other delimited text is
     * kept as words, so "(no, cancel that)" is never erased before the rules see it (re-audit 4, N18).
     */
    fun clean(text: String): String =
        DELIMITED
            .replace(text) { m ->
                val inner = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: ""
                if (inner.isBlank() || NON_SPEECH_TAG.matches(inner)) " " else " $inner "
            }.replace(Regex("""\s+"""), " ")
            .trim()

    /** Applies the gate around an engine's raw result. */
    fun transcript(
        text: String,
        confidence: Float?,
    ): Transcript {
        val cleaned = clean(text)
        return if (cleaned.none { it.isLetterOrDigit() }) Transcript("", 0f) else Transcript(cleaned, confidence)
    }
}
