package io.github.ardaulas.earshot.whisper

/** The JNI surface of `libearshot_whisper.so`. Use [WhisperSpeechEngine], not this. */
internal object WhisperNative {
    init {
        System.loadLibrary("earshot_whisper")
    }

    /** Returns a handle, or 0 if the model could not be loaded. */
    @JvmStatic external fun init(modelPath: String): Long

    @JvmStatic external fun free(handle: Long)

    @JvmStatic external fun abort(handle: Long)

    @JvmStatic external fun resetAbort(handle: Long)

    @JvmStatic external fun transcribe(
        handle: Long,
        pcm16k: FloatArray,
        threads: Int,
        outConfidence: FloatArray,
    ): String?
}
