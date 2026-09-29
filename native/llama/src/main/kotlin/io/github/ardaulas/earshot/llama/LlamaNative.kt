package io.github.ardaulas.earshot.llama

/** The JNI surface of `libearshot_llama.so`. Use [LlamaLmEngine], not this. */
internal object LlamaNative {
    init {
        System.loadLibrary("earshot_llama")
    }

    /** Returns a handle, or 0 if the model could not be loaded. */
    @JvmStatic external fun load(
        modelPath: String,
        nCtx: Int,
        threads: Int,
    ): Long

    @JvmStatic external fun free(handle: Long)

    @JvmStatic external fun abort(handle: Long)

    @JvmStatic external fun formatChat(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        addAssistant: Boolean,
    ): String?

    @JvmStatic external fun generate(
        handle: Long,
        prefix: String,
        suffix: String,
        grammar: String,
        maxTokens: Int,
    ): String?
}
