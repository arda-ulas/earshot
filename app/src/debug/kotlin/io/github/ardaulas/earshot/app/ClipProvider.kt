package io.github.ardaulas.earshot.app

import android.content.Context
import java.io.File

/**
 * Debug builds only: lists WAV clips pushed by `scripts/push-models.sh --clips`, so the pipeline can be
 * exercised on an emulator without a microphone. Turns fed this way are traced as `CLIP`.
 */
object ClipProvider {
    fun clips(context: Context): List<File> =
        context
            .getExternalFilesDir("clips")
            ?.listFiles { f -> f.extension.equals("wav", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()

    /**
     * What a clip says, from `captions.tsv` written by `scripts/make-clips.sh`, for the recording
     * caption. Null if unknown.
     */
    fun caption(
        context: Context,
        clip: String,
    ): String? =
        context
            .getExternalFilesDir("clips")
            ?.resolve("captions.tsv")
            ?.takeIf { it.isFile }
            ?.readLines()
            ?.map { it.split('\t') }
            ?.firstOrNull { it.size == 3 && it[0] == clip }
            ?.let { (_, voice, text) -> "$text ($voice)" }
}
