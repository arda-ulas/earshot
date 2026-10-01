package io.github.ardaulas.earshot.harness

import io.github.ardaulas.earshot.core.command.Command
import io.github.ardaulas.earshot.core.interpret.RuleResult
import io.github.ardaulas.earshot.core.policy.Source
import io.github.ardaulas.earshot.core.policy.Verdict
import java.util.Locale
import kotlin.math.ceil

/** Word error counts for one reference/hypothesis pair. */
data class WordErrors(
    val edits: Int,
    val referenceWords: Int,
)

/**
 * The harness's own text normalisation for WER, frozen here on purpose: it does not use core's
 * `TextNormalizer`, whose synonym folding ("defogger" -> "defrost", "temp" -> "temperature") and
 * number joining are there for the rules and may change with them, which would move WER against the
 * baseline without any change in speech-to-text. [ID] is stored in every report and baseline; `compare`
 * fails when it differs, so a change here needs a new baseline.
 */
object WerNormaliser {
    const val ID = "harness-wer-1"
    const val DESCRIPTION =
        "lower case; apostrophes dropped; other punctuation and hyphens separate words, except a decimal point " +
            "between digits and a minus sign before a number; number words from zero to ninety-nine as digits"

    private val UNITS =
        listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
            .withIndex()
            .associate { it.value to it.index }
    private val TEENS =
        listOf("ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
            .withIndex()
            .associate { it.value to it.index + TEN }
    private val TENS =
        listOf("twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
            .withIndex()
            .associate { it.value to (it.index + 2) * TEN }

    fun words(text: String): List<String> {
        val t = text.lowercase(Locale.ROOT).replace("'", "").replace("\u2019", "")
        val cleaned = StringBuilder()
        for ((i, c) in t.withIndex()) {
            val prev = t.getOrNull(i - 1)
            val next = t.getOrNull(i + 1)
            val keep =
                c.isLetterOrDigit() ||
                    (c == '.' && prev?.isDigit() == true && next?.isDigit() == true) ||
                    (c == '-' && next?.isDigit() == true && (prev == null || prev.isWhitespace()))
            cleaned.append(if (keep) c else ' ')
        }
        val raw = cleaned.split(' ').filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        var i = 0
        while (i < raw.size) {
            val w = raw[i]
            val tens = TENS[w]
            val unit = raw.getOrNull(i + 1)?.let { UNITS[it] }
            when {
                tens != null && unit != null && unit > 0 -> {
                    out += (tens + unit).toString()
                    i += 2
                }

                tens != null -> {
                    out += tens.toString()
                    i++
                }

                else -> {
                    out += (TEENS[w] ?: UNITS[w])?.toString() ?: w
                    i++
                }
            }
        }
        return out
    }

    private const val TEN = 10
}

object Wer {
    /** Word-level Levenshtein distance (substitutions + deletions + insertions) after [WerNormaliser]. */
    fun errors(
        reference: String,
        hypothesis: String,
    ): WordErrors {
        val ref = words(reference)
        val hyp = words(hypothesis)
        return WordErrors(levenshtein(ref, hyp), ref.size)
    }

    /** Corpus WER: total edits over total reference words. 0 for an empty corpus. */
    fun rate(all: List<WordErrors>): Double {
        val words = all.sumOf { it.referenceWords }
        val edits = all.sumOf { it.edits }
        return when {
            words > 0 -> edits.toDouble() / words
            edits > 0 -> 1.0
            else -> 0.0
        }
    }

    fun words(text: String): List<String> = WerNormaliser.words(text)

    fun <T> levenshtein(
        a: List<T>,
        b: List<T>,
    ): Int {
        var prev = IntArray(b.size + 1) { it }
        var cur = IntArray(b.size + 1)
        for (i in 1..a.size) {
            cur[0] = i
            for (j in 1..b.size) {
                val sub = prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(sub, prev[j] + 1, cur[j - 1] + 1)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.size]
    }
}

/** Nearest-rank percentile; null for no samples. */
fun percentile(
    values: List<Double>,
    p: Double,
): Double? {
    if (values.isEmpty()) return null
    require(p in 0.0..100.0)
    val sorted = values.sorted()
    val rank = ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
    return sorted[rank - 1]
}

data class IntentScore(
    /** The command class is right (for a rejection or fallback clip: the kind of outcome is right). */
    val intent: Boolean,
    /** The whole command, values included, is right. */
    val slots: Boolean,
)

object Scoring {
    private val ALLOWING = setOf("Allow", "AllowVoiceOnly")

    /**
     * Commands that write to the vehicle when allowed. Queries, the screen and conversation commands
     * do not. A yes (`Answer`) only acts on a pending confirmation, which the harness never has (each
     * clip is a fresh exchange), so it is not a write here.
     */
    fun isWrite(command: Command?): Boolean =
        when (command) {
            is Command.SetTemp, is Command.AdjustTemp, is Command.SetFan, is Command.SetDefrost, is Command.SetAc -> true
            else -> false
        }

    /** The pipeline would reach `VehicleGateway.write`: an allowing verdict on a write command. */
    fun reachesWrite(
        command: Command?,
        verdict: String,
    ): Boolean = isWrite(command) && verdictBase(verdict) in ALLOWING

    /**
     * A false action: the pipeline wrote, or would write, to the vehicle, but the label does not expect
     * that same write in this driving state. A write counts when the verdict allows a write command, or
     * when core's `TurnEngine` actually wrote ([observedWrite]). That covers a rejection or fallback clip
     * that got a write, a different command, the right command where no acceptable verdict allows it
     * (the label wants Confirm, Refuse or Reprompt), and any write at all on an unconfident transcript
     * ([confident] false), whatever the label says: below the threshold the policy must re-prompt
     * (SG-1). Anything that does not reach a write (a question, a refusal, a re-prompt, a query) is
     * never a false action.
     */
    fun isFalseAction(
        expected: Expected,
        acceptable: Set<String>,
        command: Command?,
        verdict: String,
        confident: Boolean = true,
        observedWrite: Boolean = false,
    ): Boolean {
        if (!reachesWrite(command, verdict) && !observedWrite) return false
        if (!confident) return true
        val expectedWrite =
            expected is Expected.Action &&
                expected.command == command.toString() &&
                acceptable.any { verdictBase(it) in ALLOWING }
        return !expectedWrite
    }

    /** The commands a clip's label counts as right: its command, or every acceptable model command. */
    fun expectedCommands(expected: Expected): Set<String> =
        when (expected) {
            is Expected.Action -> setOf(expected.command)
            is Expected.Lm -> expected.acceptable
            is Expected.Reject -> emptySet()
        }

    /**
     * A confirmation question about a write the label does not expect: one spoken "yes" would carry it
     * out. Not a false action (nothing was written), but counted and reported per group.
     */
    fun isWrongConfirmation(
        expected: Expected,
        command: Command?,
        verdict: String,
    ): Boolean = verdict == "Confirm" && isWrite(command) && command.toString() !in expectedCommands(expected)

    /**
     * A wrong confirmation that goes the opposite way to what the label asks for: warmer when the label
     * wants cooler or the AC on, or the reverse.
     */
    fun isWrongDirection(
        expected: Expected,
        command: Command?,
        verdict: String,
    ): Boolean {
        if (!isWrongConfirmation(expected, command, verdict)) return false
        val direction = command?.let(::direction) ?: return false
        val wanted = expectedCommands(expected).mapNotNull { KnownCommands.all[it]?.let(::direction) }.toSet()
        return wanted.isNotEmpty() && direction !in wanted
    }

    /** +1 for warming, -1 for cooling (a lower temperature or the AC on), null otherwise. */
    private fun direction(command: Command): Int? =
        when (command) {
            is Command.AdjustTemp -> if (command.delta > 0) 1 else -1
            is Command.SetAc -> if (command.on) -1 else null
            else -> null
        }

    /**
     * Intent and slot correctness.
     *
     * - A command: the class, then the whole command with its values.
     * - A rejection: the intent is right when no command comes out; the slots when it is the kind of
     *   rejection the label names: `OutOfRange` for `out_of_range`, a rule rejection (without the
     *   language model) for `rejected`, otherwise out of domain.
     * - A fallback clip: the rules must miss it; when the model ran and the label names commands, the
     *   model's command must be one of them.
     */
    fun intent(
        expected: Expected,
        interpretation: Interpretation,
    ): IntentScore {
        val predicted = interpretation.predicted
        return when (expected) {
            is Expected.Action -> {
                IntentScore(intentOf(predicted) == intentOf(expected.command), predicted == expected.command)
            }

            is Expected.Reject -> {
                val outOfDomain = Command.OutOfDomain.toString()
                val rejected = predicted == Interpretation.OUT_OF_RANGE || predicted == outOfDomain
                val kind =
                    when (expected.reason) {
                        RejectReason.OUT_OF_RANGE -> predicted == Interpretation.OUT_OF_RANGE
                        RejectReason.RULES_REJECTED -> interpretation.rule is RuleResult.Rejected
                        RejectReason.OUT_OF_DOMAIN, RejectReason.UNCLEAR -> predicted == outOfDomain
                    }
                IntentScore(rejected, rejected && kind)
            }

            is Expected.Lm -> {
                val rulesMissed = interpretation.rule == RuleResult.NoMatch
                if (interpretation.source == Source.LM && expected.acceptable.isNotEmpty()) {
                    val intents = expected.acceptable.map(::intentOf).toSet()
                    IntentScore(intentOf(predicted) in intents, predicted in expected.acceptable)
                } else {
                    IntentScore(rulesMissed, rulesMissed)
                }
            }
        }
    }

    /**
     * Verdicts that count as correct for a label-scored decision (confident transcript, command exactly
     * right). For most clips that is the label's set.
     *
     * A fallback clip's label states the verdict for a model command it accepts; that set is used
     * exactly when the model produced one of them. When the model did not run (`--lm none`, so the rule
     * miss is out of domain) or produced `OutOfDomain`, only `Refuse(OUT_OF_DOMAIN)` is correct.
     *
     * A command clip's label states what the rules' verdict should be. When the rules missed a
     * misheard command and the language model produced it instead ([source] `LM`), core's policy
     * raises the verdict to at least a confirmation (SG-7), so the label's verdicts are raised the same
     * way, with core's [Verdict.atLeast]: an `Allow` becomes `Confirm`, and a model command that is
     * allowed without a spoken yes is both wrong and a false action.
     */
    fun acceptedVerdicts(
        expected: Expected,
        labelled: Set<String>,
        lmEnabled: Boolean,
        source: Source = Source.RULES,
        predicted: String? = null,
    ): Set<String> =
        when {
            expected is Expected.Lm && lmEnabled && source == Source.LM && predicted != Command.OutOfDomain.toString() -> labelled
            expected is Expected.Lm -> setOf(REFUSE_OUT_OF_DOMAIN)
            source == Source.LM -> labelled.map(::atLeastConfirm).toSet()
            else -> labelled
        }

    const val REFUSE_OUT_OF_DOMAIN = "Refuse(OUT_OF_DOMAIN)"

    /** A verdict name raised to at least `Confirm` by core's [Verdict.atLeast]; `Refuse` and `OutOfRange` stay. */
    private fun atLeastConfirm(name: String): String {
        val verdict =
            when (name) {
                "Allow" -> Verdict.Allow
                "AllowVoiceOnly" -> Verdict.AllowVoiceOnly
                "Confirm" -> Verdict.Confirm
                "Reprompt" -> Verdict.Reprompt
                "Stop" -> Verdict.Stop
                else -> return name
            }
        return Pipeline.verdictName(verdict.atLeast(Verdict.Confirm))
    }

    /** `Refuse` matches any refusal; `Refuse(OUT_OF_DOMAIN)` only that one. */
    fun verdictMatches(
        expected: String,
        actual: String,
    ): Boolean = if ('(' in expected) expected == actual else verdictBase(expected) == verdictBase(actual)

    /** True when [actual] matches any verdict in [accepted]. */
    fun verdictAccepted(
        accepted: Set<String>,
        actual: String,
    ): Boolean = accepted.any { verdictMatches(it, actual) }

    /** `SetTemp(celsius=21)` -> `SetTemp`. */
    fun intentOf(command: String): String = command.substringBefore('(')

    /** The label a clip's expectation gets in the confusion table. */
    fun expectedLabel(expected: Expected): String =
        when (expected) {
            is Expected.Action -> intentOf(expected.command)
            is Expected.Reject -> "reject:" + expected.reason.label
            is Expected.Lm -> "lm" + (expected.command?.let { ":" + intentOf(it) } ?: "")
        }
}
