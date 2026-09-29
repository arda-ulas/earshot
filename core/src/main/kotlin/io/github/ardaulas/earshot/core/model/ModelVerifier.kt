package io.github.ardaulas.earshot.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** One entry of `models/manifest.json`. The same file drives the download script and the app. */
@Serializable
data class ModelSpec(
    val id: String,
    /** "stt" or "lm". */
    val role: String,
    val file: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val license: String,
    val required: Boolean = true,
)

@Serializable
data class ModelManifest(
    val models: List<ModelSpec>,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): ModelManifest = json.decodeFromString(serializer(), text)
    }
}

sealed interface ModelCheck {
    data object Ok : ModelCheck

    data object Missing : ModelCheck

    data class SizeMismatch(
        val expected: Long,
        val actual: Long,
    ) : ModelCheck

    data class HashMismatch(
        val actual: String,
    ) : ModelCheck

    data class Unreadable(
        val error: String,
    ) : ModelCheck

    val isOk: Boolean get() = this == Ok
}

/**
 * Checks a model file against its pinned size and SHA-256 before it is loaded (TH-4). The download
 * script checks the same hashes; this check catches a file that was swapped or damaged since.
 */
object ModelVerifier {
    fun verify(
        spec: ModelSpec,
        file: File,
    ): ModelCheck {
        if (!file.isFile) return ModelCheck.Missing
        val size = file.length()
        if (size != spec.sizeBytes) return ModelCheck.SizeMismatch(spec.sizeBytes, size)
        return try {
            val actual = sha256(file)
            if (actual.equals(spec.sha256, ignoreCase = true)) ModelCheck.Ok else ModelCheck.HashMismatch(actual)
        } catch (e: IOException) {
            ModelCheck.Unreadable(e.javaClass.simpleName)
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER).use { input ->
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private const val BUFFER = 1 shl 20
}
