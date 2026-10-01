package io.github.ardaulas.earshot.app

import android.content.Context
import android.content.Intent
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
            ?.takeIf { it.isFile && it.length() <= MAX_CAPTIONS_BYTES }
            ?.let { f -> runCatching { f.readLines() }.getOrNull() }
            ?.map { it.split('\t') }
            ?.firstOrNull { it.size == 3 && it[0] == clip }
            ?.let { (_, voice, text) -> "$text ($voice)" }

    /**
     * The clip named by a test driver in the launch intent (`--es earshot.debug.clip NAME`), so the
     * moving rows of the manual test plan need no on-screen controls while the platform restricts
     * the UI (re-audit 5, N6). Only a file already in the app's clip folder can be named.
     */
    fun requestedClip(intent: Intent?): String? = intent?.getStringExtra(EXTRA_CLIP)?.takeIf { it.endsWith(".wav") && '/' !in it }

    private const val EXTRA_CLIP = "earshot.debug.clip"

    private const val MAX_CAPTIONS_BYTES = 64_000L
}
