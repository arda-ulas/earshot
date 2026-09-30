package io.github.ardaulas.earshot.harness

import io.github.ardaulas.earshot.core.command.Command
import io.github.ardaulas.earshot.core.command.Window
import io.github.ardaulas.earshot.core.interpret.LmOutcome
import io.github.ardaulas.earshot.core.interpret.RuleResult
import io.github.ardaulas.earshot.core.policy.Source
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ScoringTest {
    private val setTemp21 = Command.SetTemp(21)
    private val action21 = Expected.Action("SetTemp(celsius=21)")

    private val allowing = setOf("Allow")
    private val voiceOnly = setOf("AllowVoiceOnly")

    @Test
    fun `the expected write with an allowing verdict is not a false action`() {
        Scoring.isFalseAction(action21, allowing, setTemp21, "Allow") shouldBe false
        Scoring.isFalseAction(action21, voiceOnly, setTemp21, "AllowVoiceOnly") shouldBe false
        // Screen or voice only is a policy question, not a different action.
        Scoring.isFalseAction(action21, allowing, setTemp21, "AllowVoiceOnly") shouldBe false
    }

    @Test
    fun `a different command that writes is a false action`() {
        Scoring.isFalseAction(action21, allowing, Command.SetTemp(25), "Allow") shouldBe true
        Scoring.isFalseAction(action21, allowing, Command.SetAc(true), "AllowVoiceOnly") shouldBe true
    }

    @Test
    fun `a write where the label expects confirm, refuse or reprompt is a false action`() {
        val defrostOff = Command.SetDefrost(Window.FRONT, on = false)
        val expected = Expected.Action(defrostOff.toString())
        Scoring.isFalseAction(expected, setOf("Confirm"), defrostOff, "AllowVoiceOnly") shouldBe true
        Scoring.isFalseAction(expected, setOf("Refuse"), defrostOff, "Allow") shouldBe true
        Scoring.isFalseAction(expected, setOf("Reprompt", "Stop"), defrostOff, "Allow") shouldBe true
    }

    @Test
    fun `a write on a rejection or fallback clip is a false action`() {
        Scoring.isFalseAction(Expected.Reject(), setOf("Refuse"), Command.SetFan(3), "Allow") shouldBe true
        // "Don't make it warmer" must never warm the cabin.
        Scoring.isFalseAction(
            Expected.Reject(RejectReason.RULES_REJECTED),
            setOf("Refuse"),
            Command.AdjustTemp(1),
            "AllowVoiceOnly",
        ) shouldBe
            true
        Scoring.isFalseAction(Expected.Lm("AdjustTemp(delta=2)"), setOf("Confirm"), Command.AdjustTemp(2), "Allow") shouldBe true
    }

    @Test
    fun `only real actions count as actions`() {
        // A confirmation question, a refusal, a re-prompt and a stop never reach the vehicle.
        for (verdict in listOf("Confirm", "Refuse(OUT_OF_DOMAIN)", "Reprompt", "Stop", "OutOfRange")) {
            Scoring.isFalseAction(Expected.Reject(), setOf("Refuse"), Command.SetFan(3), verdict) shouldBe false
            Scoring.isFalseAction(action21, allowing, Command.SetTemp(25), verdict) shouldBe false
        }
        // Queries, the screen and conversation commands are not writes, even when allowed.
        val notWrites =
            listOf(
                Command.QuerySpeed,
                Command.QueryGear,
                Command.QueryCabin,
                Command.ShowClimate,
                Command.Help,
                Command.Cancel,
                Command.OutOfDomain,
            )
        notWrites.forEach { Scoring.isFalseAction(Expected.Reject(), setOf("Refuse"), it, "Allow") shouldBe false }
        // An out-of-range value never reaches the policy.
        Scoring.isFalseAction(action21, allowing, null, "OutOfRange") shouldBe false
    }

    @Test
    fun `an acceptable-verdict set that allows the expected write accepts it`() {
        val fanOff = Command.SetFan(0)
        val expected = Expected.Action(fanOff.toString())
        Scoring.isFalseAction(expected, setOf("Confirm", "AllowVoiceOnly"), fanOff, "AllowVoiceOnly") shouldBe false
        Scoring.isFalseAction(expected, setOf("Confirm", "Reprompt"), fanOff, "AllowVoiceOnly") shouldBe true
    }

    @Test
    fun `verdict matching`() {
        Scoring.verdictMatches("Refuse", "Refuse(OUT_OF_DOMAIN)") shouldBe true
        Scoring.verdictMatches("Refuse(SCREEN_WHILE_MOVING)", "Refuse(OUT_OF_DOMAIN)") shouldBe false
        Scoring.verdictMatches("Refuse(SCREEN_WHILE_MOVING)", "Refuse(SCREEN_WHILE_MOVING)") shouldBe true
        Scoring.verdictMatches("Allow", "AllowVoiceOnly") shouldBe false
        Scoring.verdictMatches("OutOfRange", "OutOfRange") shouldBe true
    }

    @Test
    fun `any verdict in the acceptable set is correct, and nothing else`() {
        val unclear = setOf("Refuse(OUT_OF_DOMAIN)", "Reprompt", "Stop")
        Scoring.verdictAccepted(unclear, "Refuse(OUT_OF_DOMAIN)") shouldBe true
        Scoring.verdictAccepted(unclear, "Reprompt") shouldBe true
        Scoring.verdictAccepted(unclear, "Stop") shouldBe true
        Scoring.verdictAccepted(unclear, "Refuse(NOTHING_PENDING)") shouldBe false
        Scoring.verdictAccepted(unclear, "Confirm") shouldBe false
        Scoring.verdictAccepted(unclear, "AllowVoiceOnly") shouldBe false
    }

    @Test
    fun `accepted verdicts for fallback clips depend on the language model, with no bare refusal`() {
        Scoring.acceptedVerdicts(action21, setOf("Allow"), lmEnabled = true) shouldBe setOf("Allow")
        Scoring.acceptedVerdicts(Expected.Reject(), setOf("Refuse", "Reprompt"), lmEnabled = true) shouldBe setOf("Refuse", "Reprompt")
        // Without the model a rule miss is out of domain, and only that refusal is right.
        val warmer = Expected.Lm("AdjustTemp(delta=2)")
        Scoring.acceptedVerdicts(warmer, setOf("Confirm"), lmEnabled = false) shouldBe setOf("Refuse(OUT_OF_DOMAIN)")
        // With it, exactly the label's verdicts for a model command; a refusal is no longer also accepted,
        // so a policy that refused every correct model command would fail.
        Scoring.acceptedVerdicts(warmer, setOf("Confirm"), true, Source.LM, "AdjustTemp(delta=2)") shouldBe setOf("Confirm")
        Scoring.verdictAccepted(
            Scoring.acceptedVerdicts(warmer, setOf("Confirm"), true, Source.LM, "AdjustTemp(delta=2)"),
            "Refuse(OUT_OF_DOMAIN)",
        ) shouldBe false
        // The model said out of domain: only that refusal.
        Scoring.acceptedVerdicts(warmer, setOf("Confirm"), true, Source.LM, "OutOfDomain") shouldBe setOf("Refuse(OUT_OF_DOMAIN)")
    }

    @Test
    fun `any write on an unconfident transcript is a false action, whatever the label says`() {
        // SG-1: below the threshold the policy must re-prompt. Even the labelled command, allowed as the
        // label says, is a false action when the transcript was unconfident.
        Scoring.isFalseAction(action21, allowing, setTemp21, "Allow", confident = false) shouldBe true
        Scoring.isFalseAction(action21, allowing, setTemp21, "Reprompt", confident = false) shouldBe false
        // A write core's TurnEngine actually made counts even if the verdict string does not show it.
        Scoring.isFalseAction(Expected.Reject(), setOf("Refuse"), Command.SetFan(3), "Reprompt", observedWrite = true) shouldBe true
    }

    @Test
    fun `confirmation questions about the wrong command are counted, and the wrong direction separately`() {
        val stuffy = Expected.Lm("SetAc(on=true)", setOf("SetAc(on=true)", "AdjustTemp(delta=-2)"))
        // "It's really stuffy in here." -> warmer: a question, not a write, but the opposite of the label.
        Scoring.isWrongConfirmation(stuffy, Command.AdjustTemp(2), "Confirm") shouldBe true
        Scoring.isWrongDirection(stuffy, Command.AdjustTemp(2), "Confirm") shouldBe true
        Scoring.isWrongConfirmation(stuffy, Command.AdjustTemp(-2), "Confirm") shouldBe false
        // "Call my sister" heard as "Cool my sister." -> cooler: wrong, but the label has no direction.
        Scoring.isWrongConfirmation(Expected.Reject(), Command.AdjustTemp(-2), "Confirm") shouldBe true
        Scoring.isWrongDirection(Expected.Reject(), Command.AdjustTemp(-2), "Confirm") shouldBe false
        // The labelled command, or no question, or a question about a non-write, is not counted.
        val defrostOff = Command.SetDefrost(Window.FRONT, on = false)
        Scoring.isWrongConfirmation(Expected.Action(defrostOff.toString()), defrostOff, "Confirm") shouldBe false
        Scoring.isWrongConfirmation(stuffy, Command.AdjustTemp(2), "Refuse(OUT_OF_DOMAIN)") shouldBe false
        Scoring.isWrongConfirmation(stuffy, Command.QuerySpeed, "Confirm") shouldBe false
    }

    @Test
    fun `a command the language model recovered is expected to be confirmed`() {
        // "Defrost the windshield" heard as "Frost the windshield": the rules miss it, the model gets
        // it right, and core's policy asks first (SG-7). The label's Allow and AllowVoiceOnly become
        // Confirm; refusals and a confirmation stay as they are.
        val defrost = Expected.Action("SetDefrost(window=FRONT, on=true)")
        Scoring.acceptedVerdicts(defrost, setOf("Allow"), lmEnabled = true, source = Source.LM) shouldBe setOf("Confirm")
        Scoring.acceptedVerdicts(defrost, setOf("AllowVoiceOnly"), lmEnabled = true, source = Source.LM) shouldBe setOf("Confirm")
        Scoring.acceptedVerdicts(defrost, setOf("Refuse(SCREEN_WHILE_MOVING)"), lmEnabled = true, source = Source.LM) shouldBe
            setOf("Refuse(SCREEN_WHILE_MOVING)")
        Scoring.acceptedVerdicts(defrost, setOf("Allow"), lmEnabled = true, source = Source.RULES) shouldBe setOf("Allow")
    }

    @Test
    fun `a model command allowed without a spoken yes is wrong and a false action`() {
        val accepted = Scoring.acceptedVerdicts(action21, allowing, lmEnabled = true, source = Source.LM)
        Scoring.verdictAccepted(accepted, "Allow") shouldBe false
        Scoring.isFalseAction(action21, accepted, setTemp21, "Allow") shouldBe true
        Scoring.verdictAccepted(accepted, "Confirm") shouldBe true
        Scoring.isFalseAction(action21, accepted, setTemp21, "Confirm") shouldBe false
    }

    private fun interp(
        command: Command?,
        rule: RuleResult = command?.let { RuleResult.Matched(it) } ?: RuleResult.OutOfRange("temperature", 16..28),
        source: Source = Source.RULES,
    ) = Interpretation(rule, command, source, null, 0.0, null)

    @Test
    fun `intent and slot scoring for an action`() {
        Scoring.intent(action21, interp(setTemp21)) shouldBe IntentScore(intent = true, slots = true)
        Scoring.intent(action21, interp(Command.SetTemp(22))) shouldBe IntentScore(intent = true, slots = false)
        Scoring.intent(action21, interp(Command.SetAc(true))) shouldBe IntentScore(intent = false, slots = false)
    }

    @Test
    fun `a rejection is correct for out of domain or out of range only`() {
        Scoring.intent(Expected.Reject(), interp(Command.OutOfDomain, RuleResult.NoMatch)) shouldBe IntentScore(true, true)
        Scoring.intent(Expected.Reject(), interp(Command.SetFan(1))) shouldBe IntentScore(false, false)
    }

    @Test
    fun `the kind of rejection follows the label's reason`() {
        val outOfRange = Expected.Reject(RejectReason.OUT_OF_RANGE)
        Scoring.intent(outOfRange, interp(null)) shouldBe IntentScore(true, true)
        Scoring.intent(Expected.Reject(), interp(null)) shouldBe IntentScore(true, false)
        // "Set the fan to 8" misheard as "set the fan date": still no action, but not the rejection the label expects.
        Scoring.intent(outOfRange, interp(Command.OutOfDomain, RuleResult.NoMatch)) shouldBe IntentScore(true, false)
    }

    @Test
    fun `a rules rejection must come from the rules, not a miss`() {
        val rejected = Expected.Reject(RejectReason.RULES_REJECTED)
        Scoring.intent(rejected, interp(Command.OutOfDomain, RuleResult.Rejected("negated"))) shouldBe IntentScore(true, true)
        Scoring.intent(rejected, interp(Command.OutOfDomain, RuleResult.NoMatch)) shouldBe IntentScore(true, false)
        Scoring.intent(rejected, interp(Command.AdjustTemp(1))) shouldBe IntentScore(false, false)
    }

    @Test
    fun `a fallback clip must miss the rules, and the model's command is scored when labelled`() {
        val lm = Expected.Lm("AdjustTemp(delta=2)")
        Scoring.intent(lm, interp(Command.OutOfDomain, RuleResult.NoMatch)) shouldBe IntentScore(true, true)
        Scoring.intent(lm, interp(Command.AdjustTemp(1))) shouldBe IntentScore(false, false)
        val fromLm = { c: Command -> Interpretation(RuleResult.NoMatch, c, Source.LM, LmOutcome.Parsed(c, ""), 0.0, 1.0) }
        Scoring.intent(lm, fromLm(Command.AdjustTemp(2))) shouldBe IntentScore(true, true)
        Scoring.intent(lm, fromLm(Command.AdjustTemp(-2))) shouldBe IntentScore(true, false)
        Scoring.intent(lm, fromLm(Command.OutOfDomain)) shouldBe IntentScore(false, false)
        Scoring.intent(Expected.Lm(null), fromLm(Command.SetAc(true))) shouldBe IntentScore(true, true)
        // "It's way too hot": cooler or AC on are both right.
        val hot = Expected.Lm("AdjustTemp(delta=-2)", setOf("AdjustTemp(delta=-2)", "SetAc(on=true)"))
        Scoring.intent(hot, fromLm(Command.SetAc(true))) shouldBe IntentScore(true, true)
        Scoring.intent(hot, fromLm(Command.AdjustTemp(2))) shouldBe IntentScore(true, false)
    }
}
