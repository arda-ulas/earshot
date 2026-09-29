package io.github.ardaulas.earshot.app

import android.content.Context
import java.io.File

/** Release builds have no clip input: audio only ever comes from the microphone. */
object ClipProvider {
    @Suppress("UNUSED_PARAMETER")
    fun clips(context: Context): List<File> = emptyList()
}
