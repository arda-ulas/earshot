package io.github.ardaulas.earshot.harness

import io.github.ardaulas.earshot.core.interpret.LmInterpreter
import io.github.ardaulas.earshot.core.interpret.LmWireFormat
import io.github.ardaulas.earshot.core.model.ModelGate
import io.github.ardaulas.earshot.core.model.ModelManifest
import io.github.ardaulas.earshot.core.model.ModelVerifier
import io.github.ardaulas.earshot.core.policy.Policy
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

private const val USAGE = """Usage:
  harness validate --suite DIR [--labels labels.jsonl]
  harness run --suite DIR --engine reference|whisper --lm none|qwen3 --out DIR
      [--labels labels.jsonl] [--snr all|clean|20|10|5 (comma list)] [--threads 2] [--models models]
      [--whisper-bin tools/host/build/whisperhost/whisperhost] [--whisper-model ggml-tiny.en.bin]
      [--lm-bin tools/host/build/lmhost/lmhost] [--lm-model Qwen3-0.6B-Q4_0.gguf] [--lm-timeout-ms 10000]
  harness compare --baseline FILE --report FILE[,FILE...] [--allow-partial] [--allow-new-groups]
      exit 1 when a gate fails; --allow-partial notes (instead of failing) baseline groups the reports
      lack and skipped conditions; --allow-new-groups notes report groups with no baseline
  harness baseline --report FILE[,FILE...] --out FILE   keep reports' gated numbers as the baseline
Paths are relative to the repository root."""

/** Exit codes: 0 ok, 1 a gate failed, 2 bad usage or input. */
fun main(args: Array<String>) {
    val code =
        try {
            Cli.run(args.toList(), ::println)
        } catch (e: UsageException) {
            System.err.println(e.message)
            System.err.println(USAGE)
            2
        } catch (e: TestSetException) {
            System.err.println(e.message)
            2
        }
    exitProcess(code)
}

class UsageException(
    message: String,
) : Exception(message)

object Cli {
    private const val DEFAULT_THREADS = 2

    fun run(
        args: List<String>,
        out: (String) -> Unit,
    ): Int {
        val command = args.firstOrNull() ?: throw UsageException("no command")
        val opts = options(args.drop(1))
        return when (command) {
            "validate" -> validate(opts, out)
            "run" -> runSuite(opts, out)
            "compare" -> compare(opts, out)
            "baseline" -> baseline(opts, out)
            else -> throw UsageException("unknown command '$command'")
        }
    }

    /** Options that take no value. */
    private val FLAGS = setOf("allow-partial", "allow-new-groups")

    internal fun options(args: List<String>): Map<String, String> {
        val map = mutableMapOf<String, String>()
        var i = 0
        while (i < args.size) {
            val key = args[i]
            if (key.removePrefix("--") in FLAGS && key.startsWith("--")) {
                map[key.removePrefix("--")] = "true"
                i += 1
                continue
            }
            if (!key.startsWith("--") || i + 1 >= args.size) throw UsageException("expected --option value at '$key'")
            map[key.removePrefix("--")] = args[i + 1]
            i += 2
        }
        return map
    }

    private fun Map<String, String>.need(key: String) = this[key] ?: throw UsageException("missing --$key")

    private fun validate(
        opts: Map<String, String>,
        out: (String) -> Unit,
    ): Int {
        val suite = TestSetLoader.load(Paths.get(opts.need("suite")), opts["labels"] ?: "labels.jsonl")
        val kinds = suite.clips.groupingBy { Scoring.expectedLabel(it.expected).substringBefore(':') }.eachCount()
        val pending = if (suite.pendingSkipped > 0) ", ${suite.pendingSkipped} pending-recording line(s) skipped" else ""
        out("${suite.name}: ${suite.clips.size} clips $kinds, noise ${suite.noiseProfiles}, SNR ${suite.snrDb} dB$pending")
        return 0
    }

    private fun runSuite(
        opts: Map<String, String>,
        out: (String) -> Unit,
    ): Int {
        val suite = TestSetLoader.load(Paths.get(opts.need("suite")), opts["labels"] ?: "labels.jsonl")
        val engine = opts.need("engine")
        val lmName = opts.need("lm")
        val outDir = Paths.get(opts.need("out"))
        val threads = opts["threads"]?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_THREADS
        val threadArgs = listOf("--threads", threads.toString())
        val engines = mutableListOf<Map<String, String>>()
        val modelsDir = Paths.get(opts["models"] ?: "models")
        val (includeClean, snrs) = snrSelection(opts["snr"] ?: "all", suite.snrDb)
        val models = mutableListOf<ModelUsed>()
        val closeables = mutableListOf<Closeable>()
        try {
            val source: TranscriptSource =
                when (engine) {
                    "reference" -> {
                        ReferenceSource()
                    }

                    "whisper" -> {
                        val model = verifiedModel(modelsDir, opts["whisper-model"] ?: "ggml-tiny.en.bin", "stt", models)
                        val bin = binary(opts["whisper-bin"] ?: "tools/host/build/whisperhost/whisperhost")
                        engines += hostEngineInfo(bin, threadArgs)
                        SubprocessSource("whisper", JsonLinesProcess(listOf(bin.path) + threadArgs + model.path))
                    }

                    else -> {
                        throw UsageException("unknown engine '$engine'")
                    }
                }.also { closeables += it }
            val lm: HostLmEngine? =
                when (lmName) {
                    "none" -> {
                        null
                    }

                    "qwen3" -> {
                        val model = verifiedModel(modelsDir, opts["lm-model"] ?: "Qwen3-0.6B-Q4_0.gguf", "lm", models)
                        val bin = binary(opts["lm-bin"] ?: "tools/host/build/lmhost/lmhost")
                        engines += hostEngineInfo(bin, threadArgs)
                        val prompt =
                            HostLmEngine.Prompt(
                                LmWireFormat.SYSTEM_PROMPT,
                                LmWireFormat.EXAMPLES,
                                LmWireFormat.GRAMMAR,
                                LmWireFormat.ASSISTANT_PREFIX,
                                LmWireFormat.MAX_TOKENS,
                            )
                        HostLmEngine.start(bin, model, prompt, outDir.resolve("lmhost"), threadArgs).also { closeables += it }
                    }

                    else -> {
                        throw UsageException("unknown --lm '$lmName'")
                    }
                }
            val timeout = opts["lm-timeout-ms"]?.toLongOrNull() ?: LmInterpreter.DEFAULT_TIMEOUT_MS
            val pipeline =
                Pipeline(
                    policy = Policy(suite.thresholds.confidence),
                    lm = lm?.let { LmInterpreter(it, timeout) },
                    lmHostMs = { lm?.lastMs },
                    frontDefrostOn = suite.frontDefrostOn,
                )
            if (suite.pendingSkipped > 0) out("${suite.pendingSkipped} label line(s) still tagged pending-recording were skipped")
            val runner = Runner(suite, source, pipeline, lmName) { System.err.println(it) }
            val (conditions, skipped) = runner.plan(includeClean, snrs)
            val groups = conditions.map { runner.run(it) }
            val report =
                Report(
                    suite = suite.name,
                    clips = suite.clips.size,
                    sources = suite.clips.groupingBy { it.source.label }.eachCount(),
                    labelsFile = suite.labelsFile,
                    labelsSha256 = suite.labelsSha256,
                    pendingSkipped = suite.pendingSkipped,
                    host = HostInfo.detect(threads.takeIf { engine != "reference" || lm != null }),
                    thresholds =
                        ReportThresholds(
                            suite.thresholds.confidence,
                            suite.thresholds.maxIntentDropPoints,
                            suite.thresholds.maxWerRisePoints,
                        ),
                    frontDefrost = suite.frontDefrostOn?.let { if (it) "on" else "off" } ?: "unknown",
                    code = CodeVersion.detect(listOf("core/src/main", "harness/src/main", suiteGitPath(suite))),
                    models = models,
                    engines = engines,
                    skipped = skipped,
                    groups = groups,
                )
            ReportWriter.write(report, outDir)
            groups.forEach {
                out(
                    "${it.key}: WER ${pct(it.wer)}, intent ${pct(it.intentAccuracy)}, " +
                        "policy ${it.policyCorrect}/${it.policyScored}, false actions ${it.falseActions}, " +
                        "wrong confirmations ${it.wrongConfirmations}, TurnEngine mismatches ${it.turnEngineMismatches}",
                )
            }
            out("Wrote ${outDir.resolve("report.json")} and report.md")
            return 0
        } finally {
            closeables.asReversed().forEach { runCatching { it.close() } }
        }
    }

    /**
     * `--snr`: `all` (clean and every SNR in the suite, the default), `clean`, one SNR from the suite
     * (`20`), or a comma list of those. A text source ignores it: it has no audio conditions.
     */
    internal fun snrSelection(
        spec: String,
        suiteSnrs: List<Int>,
    ): Pair<Boolean, List<Int>> {
        var clean = false
        val snrs = linkedSetOf<Int>()
        for (item in spec.split(',').map { it.trim() }) {
            when {
                item == "all" -> {
                    clean = true
                    snrs += suiteSnrs
                }

                item == "clean" -> {
                    clean = true
                }

                item.toIntOrNull() != null -> {
                    val snr = item.toInt()
                    if (snr !in suiteSnrs) throw UsageException("--snr $snr is not in the suite's snr_db $suiteSnrs")
                    snrs += snr
                }

                else -> {
                    throw UsageException("bad --snr '$spec': use all, clean, an SNR from $suiteSnrs, or a comma list")
                }
            }
        }
        return clean to snrs.toList()
    }

    private fun verifiedModel(
        modelsDir: Path,
        file: String,
        role: String,
        used: MutableList<ModelUsed>,
    ): File {
        val manifestFile = modelsDir.resolve("manifest.json")
        if (!Files.isRegularFile(manifestFile)) throw UsageException("$manifestFile not found")
        val spec =
            ModelManifest.parse(Files.readString(manifestFile)).models.firstOrNull { it.file == file && it.role == role }
                ?: throw UsageException("$file is not a $role model in $manifestFile")
        val path = modelsDir.resolve(file).toFile()
        val check = ModelVerifier.verify(spec, path)
        if (!check.isOk) throw UsageException("$path: ${ModelGate.describe(check)}")
        used += ModelUsed(role, file, spec.sha256)
        return path
    }

    private fun binary(path: String): File {
        val f = File(path)
        if (!f.isFile || !f.canExecute()) throw UsageException("$path is not an executable; build the host tools first")
        return f
    }

    private fun compare(
        opts: Map<String, String>,
        out: (String) -> Unit,
    ): Int {
        val baseline = reportJson.decodeFromString(Baseline.serializer(), Files.readString(Paths.get(opts.need("baseline"))))
        val reports = opts.need("report").split(',').map { ReportWriter.read(Paths.get(it.trim())) }
        val options = CompareOptions(allowPartial = "allow-partial" in opts, allowNewGroups = "allow-new-groups" in opts)
        val comparison = Gates.compare(baseline, reports, options)
        out(Gates.render(comparison).trimEnd())
        return if (comparison.passed) 0 else 1
    }

    private fun baseline(
        opts: Map<String, String>,
        out: (String) -> Unit,
    ): Int {
        val reports = opts.need("report").split(',').map { ReportWriter.read(Paths.get(it.trim())) }
        val baseline = Baseline.merge(reports.map(Baseline::from))
        val target = Paths.get(opts.need("out"))
        target.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.writeString(target, reportJson.encodeToString(Baseline.serializer(), baseline) + "\n")
        out("Wrote $target (${baseline.groups.size} groups)")
        return 0
    }
}

/** The suite folder relative to the repository root (the working directory), for its git tree hash. */
private fun suiteGitPath(suite: Suite): String {
    val cwd = Paths.get("").toAbsolutePath()
    val dir = suite.dir.toAbsolutePath().normalize()
    return if (dir.startsWith(cwd)) cwd.relativize(dir).toString() else dir.toString()
}

/** Reads the git commit and tree hashes of the working tree; null outside a git checkout. */
fun CodeVersion.Companion.detect(dirs: List<String>): CodeVersion? =
    runCatching {
        val commit = git(emptyMap(), "rev-parse", "HEAD") ?: return null
        val dirty = !git(emptyMap(), "status", "--porcelain").isNullOrEmpty()
        // A throw-away index: stage the whole working tree into it and write its tree, without touching
        // the real index or creating a commit.
        val index = Files.createTempFile("harness-index", "")
        try {
            val env = mapOf("GIT_INDEX_FILE" to index.toString())
            Files.delete(index)
            git(env, "read-tree", "HEAD") ?: return null
            git(env, "add", "-A") ?: return null
            val tree = git(env, "write-tree") ?: return null
            val trees = dirs.associateWith { git(emptyMap(), "rev-parse", "$tree:$it") ?: "(not in git)" }
            CodeVersion(commit, dirty, trees)
        } finally {
            Files.deleteIfExists(index)
        }
    }.getOrNull()

private fun git(
    env: Map<String, String>,
    vararg args: String,
): String? {
    val pb = ProcessBuilder(listOf("git") + args).redirectErrorStream(true)
    pb.environment().putAll(env)
    val p = pb.start()
    val out =
        p.inputStream
            .bufferedReader()
            .readText()
            .trim()
    return if (p.waitFor() == 0) out else null
}

object HostInfo {
    fun detect(threads: Int?): HostLabel =
        HostLabel(
            os = System.getProperty("os.name"),
            osVersion = System.getProperty("os.version"),
            arch = System.getProperty("os.arch"),
            cpu = cpuName(),
            logicalCores = Runtime.getRuntime().availableProcessors(),
            threads = threads,
            jvm = System.getProperty("java.version"),
        )

    private fun cpuName(): String {
        val os = System.getProperty("os.name").lowercase()
        val name =
            runCatching {
                when {
                    "mac" in os -> {
                        ProcessBuilder("sysctl", "-n", "machdep.cpu.brand_string")
                            .start()
                            .inputStream
                            .bufferedReader()
                            .readText()
                    }

                    "linux" in os -> {
                        File("/proc/cpuinfo").useLines { lines ->
                            lines.firstOrNull { it.startsWith("model name") }?.substringAfter(':') ?: ""
                        }
                    }

                    else -> {
                        ""
                    }
                }
            }.getOrDefault("").trim()
        return name.ifEmpty { "unknown CPU" }
    }
}
