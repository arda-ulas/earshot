package io.github.ardaulas.earshot.core.interpret

import io.github.ardaulas.earshot.core.command.Command
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** What the language-model fallback produced, for the trace. */
sealed interface LmOutcome {
    data class Parsed(
        val command: Command,
        val raw: String,
    ) : LmOutcome

    data class Invalid(
        val raw: String,
    ) : LmOutcome

    data object TimedOut : LmOutcome

    data class Failed(
        val error: String,
    ) : LmOutcome
}

/**
 * Asks the language model to map an indirect request ("I'm freezing") onto the command schema. Any
 * timeout, error or invalid output is "not understood": the caller takes no action (SG-7).
 */
class LmInterpreter(
    private val engine: LmEngine,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    suspend fun interpret(transcript: String): LmOutcome =
        try {
            val raw =
                withTimeout(timeoutMs) {
                    engine.complete(
                        system = LmWireFormat.SYSTEM_PROMPT,
                        examples = LmWireFormat.EXAMPLES,
                        user = transcript.take(MAX_INPUT_CHARS),
                        grammar = LmWireFormat.GRAMMAR,
                        maxTokens = LmWireFormat.MAX_TOKENS,
                    )
                }
            LmWireFormat.parse(raw)?.let { LmOutcome.Parsed(it, raw) } ?: LmOutcome.Invalid(raw)
        } catch (e: TimeoutCancellationException) {
            LmOutcome.TimedOut
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            LmOutcome.Failed(e.javaClass.simpleName)
        }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L

        /** Bounds prompt length; an utterance is at most a few seconds of speech anyway. */
        const val MAX_INPUT_CHARS = 200
    }
}
