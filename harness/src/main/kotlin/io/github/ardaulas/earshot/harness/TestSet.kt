package io.github.ardaulas.earshot.harness

import io.github.ardaulas.earshot.core.command.Bounds
import io.github.ardaulas.earshot.core.command.Command
import io.github.ardaulas.earshot.core.command.Window
import io.github.ardaulas.earshot.core.interpret.LmWireFormat
import io.github.ardaulas.earshot.core.policy.DrivingState
import io.github.ardaulas.earshot.core.policy.Policy
import io.github.ardaulas.earshot.core.policy.RefuseReason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.nio.file.Files
import java.nio.file.Path

/** Where a clip's audio came from. Results from either are "clip" results, never microphone results. */
enum class ClipSource(
    val label: String,
) {
    SYNTHETIC("synthetic"),
    RECORDED("recorded"),
}

/** Why a `reject` clip must not become a command. */
enum class RejectReason(
    val label: String,
) {
    /** Nothing in the domain: the rules miss it (and the language model, if on, should say out of domain). */
    OUT_OF_DOMAIN("out_of_domain"),

    /** Core's rules refuse it themselves (`RuleResult.Rejected`: negated, a question, an unsupported target, more than one action), without asking the language model. */
    RULES_REJECTED("rejected"),

    /** A value outside its bounds, or signed or fractional: `RuleResult.OutOfRange`, answered before the policy. */
    OUT_OF_RANGE("out_of_range"),

    /** A mumble: no command, whether the engine is sure of its transcript or not. */
    UNCLEAR("unclear"),
}

/** What the pipeline should make of a clip. */
sealed interface Expected {
    /** One exact command, in core's `Command.toString()` form, e.g. `SetTemp(celsius=21)`. */
    data class Action(
        val command: String,
    ) : Expected

    /** No command. */
    data class Reject(
        val reason: RejectReason = RejectReason.OUT_OF_DOMAIN,
    ) : Expected

    /**
     * An indirect request the rules should miss, for the language-model fallback. [command] is the
     * preferred command; [acceptable] every command that counts as right (from `lm_intent`, mapped
     * through core's `LmWireFormat`), always including [command] when it is given.
     */
    data class Lm(
        val command: String?,
        val acceptable: Set<String> = setOfNotNull(command),
    ) : Expected
}

data class Clip(
    val id: String,
    /** Path relative to the suite's `audio/` folder; null for a text-only clip. */
    val audio: String?,
    val source: ClipSource,
    val voice: String?,
    val transcript: String,
    val expected: Expected,
    /**
     * Acceptable verdict names per driving state, e.g. `{Allow}`, `{Refuse}` or
     * `{Refuse(OUT_OF_DOMAIN), Reprompt}`. Any one of them is a correct decision.
     */
    val policy: Map<DrivingState, Set<String>>,
    val tags: List<String>,
)

/** Numbers from `suite.yaml`. The policy-correctness and false-action gates are fixed and not here. */
data class Thresholds(
    /** Speech-to-text confidence threshold handed to core's [Policy]. */
    val confidence: Float = Policy.DEFAULT_CONFIDENCE_THRESHOLD,
    val maxIntentDropPoints: Double = DEFAULT_MAX_INTENT_DROP_POINTS,
    val maxWerRisePoints: Double = DEFAULT_MAX_WER_RISE_POINTS,
) {
    companion object {
        const val DEFAULT_MAX_INTENT_DROP_POINTS = 2.0
        const val DEFAULT_MAX_WER_RISE_POINTS = 3.0
    }
}

data class Suite(
    val name: String,
    val domain: String,
    val dir: Path,
    /** The audio format as `suite.yaml` states it; the host binaries check the files themselves. */
    val audioFormat: String,
    val noiseProfiles: List<String>,
    val snrDb: List<Int>,
    val thresholds: Thresholds,
    /**
     * Front defrost state handed to the policy (it decides whether "fan off" reduces visibility). Null
     * means unknown, which the policy treats as on.
     */
    val frontDefrostOn: Boolean?,
    /**
     * Where a clip mixed with noise lives, relative to `audio/`: `{snr}`, `{noise}` and `{audio}` (the
     * clip's own path, e.g. `clean/u01.wav`) are filled in.
     */
    val noisyAudioPattern: String,
    val clips: List<Clip>,
    /** The labels file the clips came from, relative to [dir], and its SHA-256 (recorded in reports and the baseline). */
    val labelsFile: String = "labels.jsonl",
    val labelsSha256: String = "",
    /** Label lines skipped because they are still tagged [PENDING_RECORDING] (no audio yet). */
    val pendingSkipped: Int = 0,
) {
    val audioDir: Path get() = dir.resolve("audio")

    fun cleanAudio(clip: Clip): Path? = clip.audio?.let { audioDir.resolve(it) }

    fun noisyAudio(
        clip: Clip,
        noise: String,
        snr: Int,
    ): Path? =
        clip.audio?.let {
            audioDir.resolve(
                noisyAudioPattern
                    .replace("{noise}", noise)
                    .replace("{snr}", snr.toString())
                    .replace("{audio}", it),
            )
        }

    companion object {
        const val DEFAULT_NOISY_PATTERN = "snr{snr}/{noise}/{audio}"

        /** Tag of a recorded-voice line whose clip has not been recorded yet; the loader skips such lines. */
        const val PENDING_RECORDING = "pending-recording"
    }
}

class TestSetException(
    message: String,
) : Exception(message)

/**
 * Every command string core can produce, mapped to its command. Finite because every value is
 * bounded, so an expected command in a label is checked against it and a typo is caught at load time.
 */
object KnownCommands {
    val all: Map<String, Command> =
        buildList {
            Bounds.TEMP_C.forEach { add(Command.SetTemp(it)) }
            Bounds.TEMP_DELTA.forEach {
                add(Command.AdjustTemp(it))
                add(Command.AdjustTemp(-it))
            }
            Bounds.FAN_LEVEL.forEach { add(Command.SetFan(it)) }
            Window.entries.forEach { w -> listOf(true, false).forEach { add(Command.SetDefrost(w, it)) } }
            add(Command.SetAc(true))
            add(Command.SetAc(false))
            add(Command.QuerySpeed)
            add(Command.QueryGear)
            add(Command.QueryCabin)
            add(Command.ShowClimate)
            add(Command.Cancel)
            add(Command.Help)
            add(Command.Answer(true))
            add(Command.Answer(false))
            add(Command.OutOfDomain)
        }.associateBy { it.toString() }
}

/** Verdict names a label may use: core's verdicts, plus `OutOfRange` for a value the rules rejected before the policy ran. */
val VERDICT_NAMES = setOf("Allow", "AllowVoiceOnly", "Confirm", "Reprompt", "Stop", "Refuse", "OutOfRange")

object TestSetLoader {
    private val json = Json { ignoreUnknownKeys = false }

    /** Loads `suite.yaml` and [labels] from [dir]. Collects every problem and throws them together. */
    fun load(
        dir: Path,
        labels: String = "labels.jsonl",
    ): Suite {
        val yamlFile = dir.resolve("suite.yaml")
        val labelsFile = dir.resolve(labels)
        if (!Files.isRegularFile(yamlFile)) throw TestSetException("$yamlFile not found")
        if (!Files.isRegularFile(labelsFile)) throw TestSetException("$labelsFile not found")
        val problems = mutableListOf<String>()
        val suite = parseSuiteYaml(Files.readString(yamlFile), dir, problems)
        val bytes = Files.readAllBytes(labelsFile)
        val pending = mutableListOf<String>()
        val clips = parseLabels(String(bytes, Charsets.UTF_8).lines(), problems, pending)
        if (problems.isNotEmpty()) {
            throw TestSetException(
                "test set ${dir.fileName} has ${problems.size} problem(s):\n" + problems.joinToString("\n") { "  - $it" },
            )
        }
        return suite.copy(clips = clips, labelsFile = labels, labelsSha256 = sha256(bytes), pendingSkipped = pending.size)
    }

    fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    internal fun parseSuiteYaml(
        text: String,
        dir: Path,
        problems: MutableList<String>,
    ): Suite {
        val root =
            try {
                Load(LoadSettings.builder().build()).loadFromString(text)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                problems += "suite.yaml: ${e.message?.lineSequence()?.firstOrNull()}"
                null
            }
        val map = root as? Map<*, *> ?: emptyMap<Any?, Any?>().also { if (root != null) problems += "suite.yaml: not a mapping" }
        val name = map["name"]?.toString() ?: "".also { problems += "suite.yaml: missing name" }
        val domain = map["domain"]?.toString() ?: ""
        val audio =
            when (val a = map["audio"]) {
                null -> "16 kHz mono 16-bit WAV"
                is Map<*, *> -> a.entries.joinToString(", ") { "${it.key}: ${it.value}" }
                else -> a.toString()
            }
        val noise =
            when (val n = map["noise"] ?: map["noise_profiles"]) {
                null -> {
                    emptyList()
                }

                is List<*> -> {
                    n.mapNotNull { item ->
                        when (item) {
                            is Map<*, *> -> {
                                item["name"]?.toString().also {
                                    if (it ==
                                        null
                                    ) {
                                        problems += "suite.yaml: noise profile without a name"
                                    }
                                }
                            }

                            else -> {
                                item?.toString()
                            }
                        }
                    }
                }

                else -> {
                    problems += "suite.yaml: noise must be a list"
                    emptyList()
                }
            }
        val snr =
            when (val s = map["snr_db"]) {
                null -> {
                    emptyList()
                }

                is List<*> -> {
                    s.mapNotNull { v ->
                        (v as? Number)?.toInt().also { if (it == null) problems += "suite.yaml: snr_db value '$v' is not a number" }
                    }
                }

                else -> {
                    problems += "suite.yaml: snr_db must be a list"
                    emptyList()
                }
            }
        val thresholds = parseThresholds(map["thresholds"], problems)
        // Not stated means unknown, which the policy handles as on: the app's fail-safe, never the less
        // conservative "off".
        val frontDefrost =
            when (val d = (map["context"] as? Map<*, *>)?.get("front_defrost")?.toString()) {
                "off" -> {
                    false
                }

                "on" -> {
                    true
                }

                null, "unknown" -> {
                    null
                }

                else -> {
                    problems += "suite.yaml: context.front_defrost must be on, off or unknown, not '$d'"
                    null
                }
            }

        fun pattern(
            key: String,
            default: String,
        ): String {
            val p = map[key]?.toString() ?: default
            if ("{audio}" !in p) problems += "suite.yaml: $key must contain {audio}"
            if (p.startsWith("/") || p.split('/').contains("..")) problems += "suite.yaml: $key must stay under audio/"
            return p
        }
        return Suite(
            name = name,
            domain = domain,
            dir = dir,
            audioFormat = audio,
            noiseProfiles = noise,
            snrDb = snr,
            thresholds = thresholds,
            frontDefrostOn = frontDefrost,
            noisyAudioPattern = pattern("noisy_audio", Suite.DEFAULT_NOISY_PATTERN),
            clips = emptyList(),
        )
    }

    private fun parseThresholds(
        raw: Any?,
        problems: MutableList<String>,
    ): Thresholds {
        if (raw == null) return Thresholds()
        val map = raw as? Map<*, *> ?: return Thresholds().also { problems += "suite.yaml: thresholds must be a mapping" }
        var t = Thresholds()
        for ((k, v) in map) {
            val n = (v as? Number)?.toDouble()
            if (n == null) {
                problems += "suite.yaml: thresholds.$k is not a number"
                continue
            }
            when (k) {
                "confidence" -> {
                    if (n in
                        0.0..1.0
                    ) {
                        t = t.copy(confidence = n.toFloat())
                    } else {
                        problems += "suite.yaml: thresholds.confidence must be in 0..1"
                    }
                }

                "max_intent_drop_points" -> {
                    t = t.copy(maxIntentDropPoints = n)
                }

                "max_wer_rise_points" -> {
                    t = t.copy(maxWerRisePoints = n)
                }

                // Fixed gates: accepted so the file can state them, but they cannot be loosened.
                "policy_correctness" -> {
                    if (n != 1.0) problems += "suite.yaml: thresholds.policy_correctness is fixed at 1.0"
                }

                "false_action_rate" -> {
                    if (n != 0.0) problems += "suite.yaml: thresholds.false_action_rate is fixed at 0"
                }

                else -> {
                    problems += "suite.yaml: unknown threshold '$k'"
                }
            }
        }
        return t
    }

    /**
     * Parses and checks every line. A line tagged [Suite.PENDING_RECORDING] is checked like any other,
     * then left out (its id goes to [pending]): it has no audio yet. If every line is pending, that is
     * a problem, so a run never silently covers nothing.
     */
    internal fun parseLabels(
        lines: List<String>,
        problems: MutableList<String>,
        pending: MutableList<String> = mutableListOf(),
    ): List<Clip> {
        val clips = mutableListOf<Clip>()
        val seen = mutableSetOf<String>()
        lines.forEachIndexed { index, line ->
            if (line.isBlank()) return@forEachIndexed
            val where = "labels.jsonl line ${index + 1}"
            val obj =
                try {
                    json.parseToJsonElement(line) as? JsonObject
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    null
                }
            if (obj == null) {
                problems += "$where: not a JSON object"
                return@forEachIndexed
            }
            parseClip(obj, where, problems)?.let {
                when {
                    !seen.add(it.id) -> problems += "$where: duplicate id '${it.id}'"
                    Suite.PENDING_RECORDING in it.tags -> pending += it.id
                    else -> clips += it
                }
            }
        }
        if (clips.isEmpty() && problems.isEmpty()) {
            problems +=
                if (pending.isEmpty()) {
                    "labels.jsonl: no clips"
                } else {
                    "labels.jsonl: all ${pending.size} lines are tagged ${Suite.PENDING_RECORDING}; nothing has been recorded yet"
                }
        }
        return clips
    }

    private fun parseClip(
        obj: JsonObject,
        where: String,
        problems: MutableList<String>,
    ): Clip? {
        val before = problems.size
        val allowed = setOf("id", "audio", "source", "voice", "transcript", "expected", "policy", "tags")
        (obj.keys - allowed).forEach { problems += "$where: unknown field '$it'" }
        val id = obj.string("id") ?: "".also { problems += "$where: missing id" }
        val audio = obj.string("audio")
        if (audio != null && (audio.startsWith("/") || audio.split('/').contains(".."))) {
            problems += "$where: audio must be a relative path under audio/"
        }
        val source =
            when (val s = obj.string("source")) {
                "synthetic" -> {
                    ClipSource.SYNTHETIC
                }

                "recorded" -> {
                    ClipSource.RECORDED
                }

                else -> {
                    problems += "$where: source must be \"synthetic\" or \"recorded\", not ${s ?: "missing"}"
                    ClipSource.SYNTHETIC
                }
            }
        val transcript = obj.string("transcript") ?: "".also { problems += "$where: missing transcript" }
        val expected = parseExpected(obj["expected"], where, problems)
        val policy = parsePolicy(obj["policy"], where, problems)
        val tags =
            when (val t = obj["tags"]) {
                null, JsonNull -> {
                    emptyList()
                }

                is JsonArray -> {
                    t.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                }

                else -> {
                    problems += "$where: tags must be a list"
                    emptyList()
                }
            }
        if (problems.size > before) return null
        val clip = Clip(id, audio, source, obj.string("voice"), transcript, checkNotNull(expected), policy, tags)
        checkConsistent(clip, where, problems)
        return clip.takeIf { problems.size == before }
    }

    private val EXPECTED_KEYS = setOf("command", "reject", "reason", "lm", "lm_intent")

    private fun parseExpected(
        element: JsonElement?,
        where: String,
        problems: MutableList<String>,
    ): Expected? {
        val obj = element as? JsonObject
        if (obj == null) {
            problems += "$where: expected must be an object"
            return null
        }
        val before = problems.size
        (obj.keys - EXPECTED_KEYS).forEach { problems += "$where: unknown field 'expected.$it'" }
        val command = obj.string("command")
        if (command != null && command !in KnownCommands.all) {
            problems += "$where: expected.command '$command' is not a command core can produce"
        }
        val reject = (obj["reject"] as? JsonPrimitive)?.booleanOrNull == true
        val lm = (obj["lm"] as? JsonPrimitive)?.booleanOrNull == true
        val reasonText = obj.string("reason")
        val reason = reasonText?.let { r -> RejectReason.entries.firstOrNull { it.label == r } }
        if (reasonText != null && reason == null) {
            problems += "$where: expected.reason must be one of ${RejectReason.entries.map { it.label }}, not '$reasonText'"
        }
        if (reasonText != null && !reject) problems += "$where: expected.reason only goes with reject"
        val lmIntents = parseLmIntents(obj["lm_intent"], where, problems)
        if (lmIntents != null && !lm) problems += "$where: expected.lm_intent only goes with lm"
        if (problems.size > before) return null
        return when {
            lm && !reject -> {
                val fromIntents = lmIntents.orEmpty().map { it.command.toString() }
                if (command != null && lmIntents != null && command !in fromIntents) {
                    problems += "$where: expected.command '$command' is not what any of expected.lm_intent maps to"
                    return null
                }
                Expected.Lm(command ?: fromIntents.firstOrNull(), (fromIntents + listOfNotNull(command)).toSet())
            }

            reject && command == null && !lm -> {
                Expected.Reject(reason ?: RejectReason.OUT_OF_DOMAIN)
            }

            command != null && !reject -> {
                Expected.Action(command)
            }

            else -> {
                problems += "$where: expected must be {\"command\": ...}, {\"reject\": true} or {\"lm\": true}"
                null
            }
        }
    }

    /** `lm_intent`: labels of core's `LmWireFormat.Intent`, each mapped to its fixed command. */
    private fun parseLmIntents(
        element: JsonElement?,
        where: String,
        problems: MutableList<String>,
    ): List<LmWireFormat.Intent>? {
        if (element == null || element == JsonNull) return null
        val array = element as? JsonArray
        if (array == null || array.isEmpty()) {
            problems += "$where: expected.lm_intent must be a non-empty list"
            return null
        }
        return array.mapNotNull { item ->
            val label = (item as? JsonPrimitive)?.takeIf { it.isString }?.content
            LmWireFormat.Intent.entries.firstOrNull { it.label == label }.also {
                if (it == null) problems += "$where: expected.lm_intent '$item' is not a label of LmWireFormat"
            }
        }
    }

    private fun parsePolicy(
        element: JsonElement?,
        where: String,
        problems: MutableList<String>,
    ): Map<DrivingState, Set<String>> {
        val obj = element as? JsonObject
        if (obj == null) {
            problems += "$where: policy must be an object"
            return emptyMap()
        }
        val out = mutableMapOf<DrivingState, Set<String>>()
        for ((k, v) in obj) {
            val state = DrivingState.entries.firstOrNull { it.name == k }
            // One verdict name, or a list of acceptable ones.
            val names =
                when (v) {
                    is JsonPrimitive -> {
                        listOfNotNull(v.takeIf { it.isString }?.content).takeIf { it.isNotEmpty() }
                    }

                    is JsonArray -> {
                        v
                            .map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                            .takeIf { l ->
                                l.isNotEmpty() &&
                                    l.all { it != null }
                            }?.filterNotNull()
                    }

                    else -> {
                        null
                    }
                }
            when {
                state == null -> {
                    problems += "$where: policy key '$k' is not a driving state"
                }

                names == null || names.any { !isVerdictName(it) } -> {
                    problems +=
                        "$where: policy.$k '$v' is not a verdict name or a list of them"
                }

                else -> {
                    out[state] = names.toSet()
                }
            }
        }
        (DrivingState.entries.toSet() - out.keys).forEach { problems += "$where: policy has no verdict for ${it.name}" }
        return out
    }

    /** `Allow`, ..., `Refuse`, `Refuse(<RefuseReason>)`, `OutOfRange`. */
    private fun isVerdictName(name: String): Boolean {
        val base = verdictBase(name)
        if (base !in VERDICT_NAMES) return false
        if (name == base) return true
        val reason = name.removePrefix("$base(").removeSuffix(")")
        return base == "Refuse" && name.endsWith(")") && RefuseReason.entries.any { it.name == reason }
    }

    /**
     * Cross-field checks: the label's verdicts must fit its expectation, so a label cannot, for
     * example, expect a refusal and an action at once.
     */
    private fun checkConsistent(
        clip: Clip,
        where: String,
        problems: MutableList<String>,
    ) {
        val all =
            clip.policy.values
                .flatten()
                .map(::verdictBase)
                .toSet()
        val outOfRange = (clip.expected as? Expected.Reject)?.reason == RejectReason.OUT_OF_RANGE
        if (outOfRange && clip.policy.values.any { it != setOf(Interpretation.OUT_OF_RANGE) }) {
            problems += "$where: an out_of_range rejection must expect OutOfRange in every driving state"
        }
        if (!outOfRange && Interpretation.OUT_OF_RANGE in all) {
            problems += "$where: OutOfRange is only for {\"reject\": true, \"reason\": \"out_of_range\"}"
        }
        val command = (clip.expected as? Expected.Action)?.command?.let { KnownCommands.all[it] }
        val allowing = all.intersect(setOf("Allow", "AllowVoiceOnly"))
        if (clip.expected !is Expected.Action && allowing.isNotEmpty()) {
            problems += "$where: only an expected command can be allowed (found $allowing)"
        }
        if (command == Command.OutOfDomain) problems += "$where: use {\"reject\": true} rather than the OutOfDomain command"
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/** `Refuse(OUT_OF_DOMAIN)` -> `Refuse`. */
fun verdictBase(name: String): String = name.substringBefore('(')
