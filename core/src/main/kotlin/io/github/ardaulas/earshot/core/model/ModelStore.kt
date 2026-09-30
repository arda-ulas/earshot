package io.github.ardaulas.earshot.core.model

import java.io.File
import java.io.IOException

/**
 * Keeps a verified copy of each model in app-private storage and hands out only that copy.
 *
 * 1. If the private copy exists and verifies, use it.
 * 2. Otherwise verify the shared copy, copy it to a new temporary private file, verify that file,
 *    and only then move it into place. A copy that fails its check is deleted, never published.
 *
 * Snapshots are serialised, so two callers can never publish over each other (re-audit 4, N17).
 */
object ModelStore {
    @Synchronized
    fun snapshot(
        spec: ModelSpec,
        sharedDir: File,
        privateDir: File,
        verify: (ModelSpec, File) -> ModelCheck = ModelVerifier::verify,
    ): Pair<File, ModelCheck> {
        val private = File(privateDir, spec.file)
        if (private.isFile && verify(spec, private).isOk) return private to ModelCheck.Ok
        val shared = File(sharedDir, spec.file)
        val sharedCheck = verify(spec, shared)
        if (!sharedCheck.isOk) return private to sharedCheck
        var tmp: File? = null
        return try {
            privateDir.mkdirs()
            val part = File.createTempFile(spec.file, ".part", privateDir)
            tmp = part
            shared.copyTo(part, overwrite = true)
            val check = verify(spec, part)
            if (!check.isOk) return private to check
            if (!part.renameTo(private)) {
                private.delete()
                if (!part.renameTo(private)) throw IOException("rename failed")
            }
            private to ModelCheck.Ok
        } catch (e: IOException) {
            private to ModelCheck.Unreadable(e.javaClass.simpleName)
        } finally {
            tmp?.takeIf { it.exists() }?.delete()
        }
    }
}
