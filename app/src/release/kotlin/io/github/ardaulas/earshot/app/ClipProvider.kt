package io.github.ardaulas.earshot.app

import android.content.Context
import android.content.Intent
import java.io.File

/** Release builds have no clip input: audio only ever comes from the microphone. */
object ClipProvider {
    @Suppress("UNUSED_PARAMETER")
    fun clips(context: Context): List<File> = emptyList()

    @Suppress("UNUSED_PARAMETER")
    fun prepare(context: Context) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun requestedClip(
        context: Context,
        intent: Intent?,
    ): String? = null

    @Suppress("UNUSED_PARAMETER")
    fun caption(
        context: Context,
        clip: String,
    ): String? = null
}
