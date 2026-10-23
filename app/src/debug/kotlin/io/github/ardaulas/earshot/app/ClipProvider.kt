package io.github.ardaulas.earshot.app

import android.content.Context
import android.content.Intent
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

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
     * the UI (re-audit 5, N6). The intent must also carry the token stored in the app's private
     * files (`--es earshot.debug.token TOKEN`), which another app cannot read but `adb shell run-as`
     * on this debuggable build can, so only the developer's test driver is obeyed (re-audit 6, N25).
     * Only a file already in the app's clip folder can be named.
     */
    fun requestedClip(
        context: Context,
        intent: Intent?,
    ): String? {
        val clip = intent?.getStringExtra(EXTRA_CLIP)?.takeIf { it.endsWith(".wav") && '/' !in it } ?: return null
        val given = intent.getStringExtra(EXTRA_TOKEN) ?: return null
        // No valid stored token, or a malformed one given: nothing is obeyed (re-audit 7, N31).
        val stored = runCatching { token(context) }.getOrNull() ?: return null
        if (!TOKEN_FORMAT.matches(given) || !TOKEN_FORMAT.matches(stored)) return null
        return clip.takeIf { MessageDigest.isEqual(given.toByteArray(), stored.toByteArray()) }
    }

    /** Creates the token at start-up, so a test driver can read it before its first request. */
    fun prepare(context: Context) {
        runCatching { token(context) }
    }

    /** Created once, 128 random bits, readable only by this app (and `run-as` on a debug build). */
    @Synchronized
    fun token(context: Context): String {
        val f = File(context.filesDir, TOKEN_FILE)
        val existing = if (f.isFile) runCatching { f.readText().trim() }.getOrNull() else null
        if (existing != null && TOKEN_FORMAT.matches(existing)) return existing
        // Missing, empty or damaged (for example the process died while writing): make a new one,
        // written to a temporary file and moved into place, so a partial token is never the token.
        val fresh = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val tmp = File(context.filesDir, "$TOKEN_FILE.tmp")
        tmp.writeText(fresh)
        if (!tmp.renameTo(f)) {
            f.delete()
            check(tmp.renameTo(f)) { "could not store the debug token" }
        }
        return fresh
    }

    private val TOKEN_FORMAT = Regex("[0-9a-f]{32}")

    private const val EXTRA_CLIP = "earshot.debug.clip"
    private const val EXTRA_TOKEN = "earshot.debug.token"
    private const val TOKEN_FILE = "debug-clip-token"

    private const val MAX_CAPTIONS_BYTES = 64_000L
}
