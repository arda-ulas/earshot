@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.ardaulas.earshot.core.interpret

import io.github.ardaulas.earshot.core.command.Command
import io.github.ardaulas.earshot.core.requirements.Verifies
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** A scripted [LmEngine] for tests: returns a fixed string, hangs forever, or throws. */
private class FakeLmEngine(
    private val behavior: Behavior,
) : LmEngine {
    sealed interface Behavior {
        data class Returns(
            val text: String,
        ) : Behavior

        data object Hangs : Behavior

        data class Throws(
            val error: Throwable,
        ) : Behavior
    }

    var lastUser: String? = null
        private set

    override suspend fun complete(
        system: String,
        examples: List<Example>,
        user: String,
        grammar: String,
        maxTokens: Int,
    ): String {
        lastUser = user
        return when (val b = behavior) {
            is Behavior.Returns -> {
                b.text
            }

            Behavior.Hangs -> {
                delay(Long.MAX_VALUE)
                error("unreachable")
            }

            is Behavior.Throws -> {
                throw b.error
            }
        }
    }
}

@Verifies("SR-11")
class LmInterpreterTest {
    @Test
    fun `valid model output parses`() =
        runTest {
            val engine = FakeLmEngine(FakeLmEngine.Behavior.Returns("""{"cmd":"query_speed"}"""))
            val result = LmInterpreter(engine).interpret("how fast am I going")
            result shouldBe LmOutcome.Parsed(Command.QuerySpeed, """{"cmd":"query_speed"}""")
        }

    @Test
    fun `invalid model output is Invalid`() =
        runTest {
            val engine = FakeLmEngine(FakeLmEngine.Behavior.Returns("not json"))
            val result = LmInterpreter(engine).interpret("blah")
            result shouldBe LmOutcome.Invalid("not json")
        }

    @Test
    fun `an engine that never returns times out under the configured budget`() =
        runTest {
            val engine = FakeLmEngine(FakeLmEngine.Behavior.Hangs)
            val result = LmInterpreter(engine, timeoutMs = 10_000).interpret("I'm freezing")
            result shouldBe LmOutcome.TimedOut
        }

    @Test
    fun `an engine that throws is Failed`() =
        runTest {
            val engine = FakeLmEngine(FakeLmEngine.Behavior.Throws(IllegalStateException("boom")))
            val result = LmInterpreter(engine).interpret("blah")
            result shouldBe LmOutcome.Failed("IllegalStateException")
        }

    @Test
    fun `outer cancellation propagates and is not turned into a Failed outcome`() =
        runTest {
            val engine = FakeLmEngine(FakeLmEngine.Behavior.Hangs)
            val interpreter = LmInterpreter(engine, timeoutMs = 10_000)
            var completedNormally = false
            var caught: Throwable? = null
            val job =
                launch {
                    try {
                        interpreter.interpret("I'm freezing")
                        completedNormally = true
                    } catch (e: CancellationException) {
                        caught = e
                        throw e
                    }
                }
            runCurrent()
            job.cancel()
            job.join()
            completedNormally shouldBe false
            job.isCancelled shouldBe true
            (caught is CancellationException) shouldBe true
        }

    @Test
    fun `input is truncated to MAX_INPUT_CHARS before it reaches the engine`() =
        runTest {
            val engine = FakeLmEngine(FakeLmEngine.Behavior.Returns("""{"cmd":"out_of_domain"}"""))
            val longText = "a".repeat(LmInterpreter.MAX_INPUT_CHARS + 50)
            LmInterpreter(engine).interpret(longText)
            engine.lastUser?.length shouldBe LmInterpreter.MAX_INPUT_CHARS
        }
}
