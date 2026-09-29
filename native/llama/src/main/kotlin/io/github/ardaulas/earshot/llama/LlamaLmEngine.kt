package io.github.ardaulas.earshot.llama

import io.github.ardaulas.earshot.core.concurrent.runAbortable
import io.github.ardaulas.earshot.core.interpret.Example
import io.github.ardaulas.earshot.core.interpret.LmEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * llama.cpp language model. Load it only from a model file that passed `ModelVerifier`. The system
 * prompt and examples are formatted with the model's chat template and kept decoded between calls;
 * each call only decodes the user's turn. Cancelling the coroutine aborts generation.
 */
class LlamaLmEngine private constructor(
    private val handle: Long,
    private val dispatcher: CoroutineDispatcher,
) : LmEngine,
    Closeable {
    private val mutex = Mutex()

    override suspend fun complete(
        system: String,
        examples: List<Example>,
        user: String,
        grammar: String,
        maxTokens: Int,
        assistantPrefix: String,
    ): String {
        val prefixRoles = mutableListOf("system")
        val prefixContents = mutableListOf(system)
        for (e in examples) {
            prefixRoles += listOf("user", "assistant")
            prefixContents += listOf(e.user, e.assistant)
        }
        // One native context: calls (including a warm-up) must not overlap.
        return mutex.withLock {
            runAbortable(dispatcher, abort = { LlamaNative.abort(handle) }) {
                val prefix =
                    LlamaNative.formatChat(handle, prefixRoles.toTypedArray(), prefixContents.toTypedArray(), false)
                        ?: throw IOException("chat template failed")
                // assistantPrefix (e.g. Qwen3's empty think block) goes right after the assistant marker.
                val full =
                    (
                        LlamaNative.formatChat(handle, (prefixRoles + "user").toTypedArray(), (prefixContents + user).toTypedArray(), true)
                            ?: throw IOException("chat template failed")
                    ) + assistantPrefix
                // The cached prefix is only reusable when the full prompt really starts with it.
                val (cached, rest) = if (full.startsWith(prefix)) prefix to full.removePrefix(prefix) else "" to full
                LlamaNative.generate(handle, cached, rest, grammar, maxTokens) ?: throw IOException("generation failed or aborted")
            }
        }
    }

    override fun close() = LlamaNative.free(handle)

    companion object {
        /** Loads the model; null if llama.cpp could not read it. Blocking: call off the main thread. */
        fun load(
            model: File,
            contextTokens: Int = 1024,
            threads: Int = 2,
            dispatcher: CoroutineDispatcher = Dispatchers.Default,
        ): LlamaLmEngine? {
            val handle = LlamaNative.load(model.absolutePath, contextTokens, threads)
            return if (handle == 0L) null else LlamaLmEngine(handle, dispatcher)
        }
    }
}
