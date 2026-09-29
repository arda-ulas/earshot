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
    fun check(
        manifest: ModelManifest,
        dir: File,
        verify: (ModelSpec, File) -> ModelCheck = ModelVerifier::verify,
    ): AssistantStatus {
        val sttSpecs = manifest.models.filter { it.role == "stt" }
        val sttChecks = sttSpecs.map { it to verify(it, File(dir, it.file)) }
        val stt =
            sttChecks.firstOrNull { it.second.isOk }?.first
                ?: return AssistantStatus.Disabled(
                    if (sttChecks.isEmpty()) {
                        "No speech model is listed in the manifest."
                    } else {
                        "Speech model ${sttChecks.first().first.file}: ${describe(sttChecks.first().second)}"
                    },
                )
        val lmSpec = manifest.models.firstOrNull { it.role == "lm" }
        val lmCheck = lmSpec?.let { verify(it, File(dir, it.file)) }
        return AssistantStatus.Ready(
            stt = File(dir, stt.file),
            lm = if (lmSpec != null && lmCheck?.isOk == true) File(dir, lmSpec.file) else null,
            lmProblem =
                when {
                    lmSpec == null -> "No language model is listed in the manifest."
                    lmCheck?.isOk == true -> null
                    else -> "Language model ${lmSpec.file}: ${describe(lmCheck!!)}"
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
