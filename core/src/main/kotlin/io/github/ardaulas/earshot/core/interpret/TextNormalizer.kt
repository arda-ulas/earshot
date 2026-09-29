package io.github.ardaulas.earshot.core.interpret

/**
 * Turns a raw transcript into the form the rules match against: lower case, no punctuation, number
 * words as digits ("twenty one" -> "21"), and a few spoken spellings folded together ("a/c" -> "ac").
 */
object TextNormalizer {
    private val UNITS =
        mapOf(
            "zero" to 0,
            "one" to 1,
            "two" to 2,
            "three" to 3,
            "four" to 4,
            "five" to 5,
            "six" to 6,
            "seven" to 7,
            "eight" to 8,
            "nine" to 9,
            "ten" to 10,
            "eleven" to 11,
            "twelve" to 12,
            "thirteen" to 13,
            "fourteen" to 14,
            "fifteen" to 15,
            "sixteen" to 16,
            "seventeen" to 17,
            "eighteen" to 18,
            "nineteen" to 19,
        )
    private val TENS =
        mapOf(
            "twenty" to 20,
            "thirty" to 30,
            "forty" to 40,
            "fifty" to 50,
            "sixty" to 60,
            "seventy" to 70,
            "eighty" to 80,
            "ninety" to 90,
        )

    fun normalize(raw: String): String {
        val folded =
            raw
                .lowercase()
                .replace("°", " degrees ")
                .replace(Regex("""\ba\s*/\s*c\b"""), "ac")
                .replace(Regex("""\ba\.c\.?"""), "ac")
                .replace(Regex("""\ba c\b"""), "ac")
                .replace(Regex("""\bair[\s-]?con(ditioning|ditioner)?\b"""), "ac")
                .replace(Regex("""\bnever[\s-]?mind\b"""), "nevermind")
                .replace(Regex("""\bwind\s?shield\b"""), "windshield")
                .replace(Regex("""\bdefog(ger)?\b"""), "defrost")
                .replace(Regex("""\bdefroster\b"""), "defrost")
                .replace(Regex("""\btemp\b"""), "temperature")
                .replace(Regex("""(\d)\s*degrees?"""), "$1 degrees")
                .replace('-', ' ')
                .replace(Regex("""[^a-z0-9\s']"""), " ")
                .replace("'", "")
        return joinNumberWords(folded.split(Regex("""\s+""")).filter { it.isNotEmpty() }).joinToString(" ")
    }

    private fun joinNumberWords(words: List<String>): List<String> {
        val out = ArrayList<String>(words.size)
        var i = 0
        while (i < words.size) {
            val w = words[i]
            val tens = TENS[w]
            val unit = UNITS[w]
            when {
                tens != null -> {
                    val next = words.getOrNull(i + 1)?.let { UNITS[it] }
                    if (next != null && next in 1..9) {
                        out += (tens + next).toString()
                        i += 2
                    } else {
                        out += tens.toString()
                        i += 1
                    }
                }

                unit != null -> {
                    out += unit.toString()
                    i += 1
                }

                else -> {
                    out += w
                    i += 1
                }
            }
        }
        return out
    }
}
