package io.github.ardaulas.earshot.core.model

import java.io.File
import java.io.IOException

/**
 * Keeps a verified copy of each model in app-private storage and hands out only that copy.
 *
 * 1. If the private copy exists and verifies, use it.
 * 2. Otherwise verify the shared copy, copy it to a temporary private file, move it into place, and
 *    verify the private file again. Use it only if that second check passes.
 */
object ModelStore {
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
        return try {
            privateDir.mkdirs()
            val tmp = File(privateDir, spec.file + ".part")
            shared.copyTo(tmp, overwrite = true)
            if (!tmp.renameTo(private)) {
                private.delete()
                if (!tmp.renameTo(private)) throw IOException("rename failed")
            }
            private to verify(spec, private)
        } catch (e: IOException) {
            private to ModelCheck.Unreadable(e.javaClass.simpleName)
        }
    }
}
