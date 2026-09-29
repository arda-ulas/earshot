package io.github.ardaulas.earshot.core.speech

import io.github.ardaulas.earshot.core.requirements.Verifies
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioGateTest {
    private fun tone(
        seconds: Double,
        amplitude: Float,
    ) = FloatArray((seconds * AudioGate.SAMPLE_RATE).toInt()) { i ->
        amplitude * sin(2 * PI * 220 * i / AudioGate.SAMPLE_RATE).toFloat()
    }

    @Test
    @Verifies("SR-1", "SR-2")
    fun `near-silence and very short audio are not worth transcribing`() {
        AudioGate.hasSpeech(FloatArray(0)) shouldBe false
        AudioGate.hasSpeech(tone(2.0, 0.001f)) shouldBe false
        AudioGate.hasSpeech(tone(0.1, 0.5f)) shouldBe false
        AudioGate.hasSpeech(tone(1.0, 0.2f)) shouldBe true
    }

    @Test
    fun `audio is bounded to the maximum utterance length`() {
        AudioGate.bound(tone(20.0, 0.2f)).size shouldBe (AudioGate.MAX_SECONDS * AudioGate.SAMPLE_RATE).toInt()
        AudioGate.bound(tone(1.0, 0.2f)).size shouldBe AudioGate.SAMPLE_RATE
    }

    @Test
    @Verifies("SR-2")
    fun `non-speech tags are stripped and an empty result has zero confidence`() {
        AudioGate.transcript("[BLANK_AUDIO]", 0.9f) shouldBe Transcript("", 0f)
        AudioGate.transcript(" (wind blowing) ", 0.9f) shouldBe Transcript("", 0f)
        AudioGate.transcript("Set the fan to 3. [MUSIC]", 0.8f) shouldBe Transcript("Set the fan to 3.", 0.8f)
        AudioGate.transcript("...", 0.9f) shouldBe Transcript("", 0f)
    }
}
