package io.github.ardaulas.earshot.core.interpret

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TextNormalizerTest {
    @Test
    fun `folds number words into digits`() {
        TextNormalizer.normalize("twenty one") shouldBe "21"
        TextNormalizer.normalize("thirty") shouldBe "30"
        TextNormalizer.normalize("forty two") shouldBe "42"
        TextNormalizer.normalize("Set the Temperature to Twenty-One degrees!") shouldBe "set the temperature to 21 degrees"
    }

    @Test
    fun `leaves a standalone unit word as its digit`() {
        TextNormalizer.normalize("two") shouldBe "2"
        TextNormalizer.normalize("nineteen") shouldBe "19"
    }

    @Test
    fun `a tens word with no following unit stays a bare ten`() {
        TextNormalizer.normalize("twenty please") shouldBe "20 please"
    }

    @Test
    fun `folds every a slash c spelling to ac`() {
        TextNormalizer.normalize("turn on the a/c") shouldBe "turn on the ac"
        TextNormalizer.normalize("turn on the a / c") shouldBe "turn on the ac"
        TextNormalizer.normalize("A.C.") shouldBe "ac"
        TextNormalizer.normalize("turn on the a c") shouldBe "turn on the ac"
        TextNormalizer.normalize("air conditioning") shouldBe "ac"
        TextNormalizer.normalize("air conditioner") shouldBe "ac"
        TextNormalizer.normalize("air-con") shouldBe "ac"
        TextNormalizer.normalize("aircon") shouldBe "ac"
    }

    @Test
    fun `folds defrost synonyms`() {
        TextNormalizer.normalize("defogger") shouldBe "defrost"
        TextNormalizer.normalize("defog") shouldBe "defrost"
        TextNormalizer.normalize("defroster") shouldBe "defrost"
        TextNormalizer.normalize("wind shield") shouldBe "windshield"
    }

    @Test
    fun `folds temp to temperature and never mind to one word`() {
        TextNormalizer.normalize("set the temp") shouldBe "set the temperature"
        TextNormalizer.normalize("never mind") shouldBe "nevermind"
        TextNormalizer.normalize("never-mind") shouldBe "nevermind"
    }

    @Test
    fun `strips punctuation and the degree sign`() {
        TextNormalizer.normalize("What's the temperature?") shouldBe "whats the temperature"
        TextNormalizer.normalize("21°") shouldBe "21 degrees"
        TextNormalizer.normalize("21degrees") shouldBe "21 degrees"
    }

    @Test
    fun `collapses whitespace and empty input`() {
        TextNormalizer.normalize("   ") shouldBe ""
        TextNormalizer.normalize("") shouldBe ""
        TextNormalizer.normalize("fan   off") shouldBe "fan off"
    }

    @Test
    fun `passes unrecognized words through unchanged`() {
        TextNormalizer.normalize("please") shouldBe "please"
    }
}
