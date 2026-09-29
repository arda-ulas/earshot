package io.github.ardaulas.earshot.core.interpret

/** One chat-style example: what the user said and the exact output wanted. */
data class Example(
    val user: String,
    val assistant: String,
)

/**
 * An on-device language model. Implementations run natively (llama.cpp over JNI) and must stop
 * generating when the coroutine is cancelled, so a timeout really frees the CPU.
 */
interface LmEngine {
    /**
     * Completes one user turn after [system] and [examples], with output constrained by the GBNF
     * [grammar]. Returns the raw generated text.
     */
    suspend fun complete(
        system: String,
        examples: List<Example>,
        user: String,
        grammar: String,
        maxTokens: Int,
    ): String
}
