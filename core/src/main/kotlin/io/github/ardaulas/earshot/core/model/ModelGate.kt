package io.github.ardaulas.earshot.core.model

import java.io.File

/** Whether the assistant can run, decided from the model checks before anything is loaded. */
sealed interface AssistantStatus {
    /** Speech-to-text is usable. [lm] is null when the language-model fallback is disabled. */
    data class Ready(
        val stt: File,
        val lm: File?,
        val lmProblem: String?,
    ) : AssistantStatus

    /** No usable speech model: the assistant is disabled with a clear on-screen error, no crash. */
    data class Disabled(
        val reason: String,
    ) : AssistantStatus
}

/**
 * Picks the models to load. The first speech model that passes verification wins; with none, the
 * assistant is disabled. A language model that fails verification only disables the fallback: the
 * rules keep working.
 */
object ModelGate {
    /**
     * With [privateDir] set (the app does this), models are loaded only from a verified snapshot in
     * app-private storage, which no other app can write, so the bytes that were checked are the bytes
     * that are loaded (audit #15). The shared [dir] is only a place to copy from.
     */
    fun check(
        manifest: ModelManifest,
        dir: File,
        privateDir: File? = null,
        verify: (ModelSpec, File) -> ModelCheck = ModelVerifier::verify,
    ): AssistantStatus {
        fun resolve(spec: ModelSpec): Pair<File, ModelCheck> =
            if (privateDir == null) {
                File(dir, spec.file).let { it to verify(spec, it) }
            } else {
                ModelStore.snapshot(spec, dir, privateDir, verify)
            }
        val sttChecks = manifest.models.filter { it.role == "stt" }.map { spec -> spec to resolve(spec) }
        val stt =
            sttChecks.firstOrNull { it.second.second.isOk }
                ?: return AssistantStatus.Disabled(
                    if (sttChecks.isEmpty()) {
                        "No speech model is listed in the manifest."
                    } else {
                        "Speech model ${sttChecks.first().first.file}: ${describe(sttChecks.first().second.second)}"
                    },
                )
        val lmSpec = manifest.models.firstOrNull { it.role == "lm" }
        val lm = lmSpec?.let(::resolve)
        return AssistantStatus.Ready(
            stt = stt.second.first,
            lm = if (lm?.second?.isOk == true) lm.first else null,
            lmProblem =
                when {
                    lmSpec == null -> "No language model is listed in the manifest."
                    lm?.second?.isOk == true -> null
                    else -> "Language model ${lmSpec.file}: ${describe(lm!!.second)}"
                },
        )
    }

    fun describe(check: ModelCheck): String =
        when (check) {
            ModelCheck.Ok -> "verified"
            ModelCheck.Missing -> "missing. Run scripts/fetch-models.sh and scripts/push-models.sh."
            is ModelCheck.SizeMismatch -> "wrong size (${check.actual} bytes, expected ${check.expected})."
            is ModelCheck.HashMismatch -> "SHA-256 mismatch; the file was changed or damaged."
            is ModelCheck.Unreadable -> "unreadable (${check.error})."
        }
}
