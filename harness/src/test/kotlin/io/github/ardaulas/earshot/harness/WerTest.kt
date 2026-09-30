package io.github.ardaulas.earshot.harness

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class WerTest {
    @Test
    fun `identical text has no errors`() {
        Wer.errors("set the temperature to 21", "set the temperature to 21") shouldBe WordErrors(0, 5)
    }

    @Test
    fun `case, punctuation and number words are normalized first`() {
        Wer.errors("Set the temperature to twenty one.", "set the temperature to 21") shouldBe WordErrors(0, 5)
    }

    @Test
    fun `the WER normaliser is the harness's own and does not fold the rules' synonyms`() {
        Wer.words("Turn on the defogger") shouldBe listOf("turn", "on", "the", "defogger")
        Wer.words("Set the temp, never mind") shouldBe listOf("set", "the", "temp", "never", "mind")
        Wer.words("It's twenty-one degrees") shouldBe listOf("its", "21", "degrees")
        Wer.words("Ninety nine, forty, fifteen, zero") shouldBe listOf("99", "40", "15", "0")
        Wer.words("Set it to -21 or 21.5, please.") shouldBe listOf("set", "it", "to", "-21", "or", "21.5", "please")
        Wer.words("A/C on - now") shouldBe listOf("a", "c", "on", "now")
        WerNormaliser.ID shouldBe "harness-wer-1"
    }

    @Test
    fun `one substitution`() {
        Wer.errors("turn on the ac", "turn off the ac") shouldBe WordErrors(1, 4)
    }

    @Test
    fun `deletions and insertions`() {
        Wer.errors("turn on the rear defrost", "turn on defrost") shouldBe WordErrors(2, 5)
        Wer.errors("fan off", "the fan off please") shouldBe WordErrors(2, 2)
    }

    @Test
    fun `empty hypothesis deletes every word`() {
        Wer.errors("what gear am i in", "") shouldBe WordErrors(5, 5)
    }

    @Test
    fun `levenshtein on plain lists`() {
        Wer.levenshtein("kitten".toList(), "sitting".toList()) shouldBe 3
        Wer.levenshtein(emptyList<Char>(), "abc".toList()) shouldBe 3
        Wer.levenshtein("abc".toList(), emptyList()) shouldBe 3
    }

    @Test
    fun `corpus rate is total edits over total reference words`() {
        Wer.rate(listOf(WordErrors(1, 4), WordErrors(0, 6))) shouldBe (0.1 plusOrMinus 1e-12)
        Wer.rate(emptyList()) shouldBe 0.0
        Wer.rate(listOf(WordErrors(2, 0))) shouldBe 1.0
    }

    @Test
    fun `nearest-rank percentiles`() {
        val v = (1..20).map { it.toDouble() }
        percentile(v, 50.0) shouldBe 10.0
        percentile(v, 95.0) shouldBe 19.0
        percentile(listOf(7.0), 95.0) shouldBe 7.0
        percentile(emptyList(), 50.0) shouldBe null
    }
}
