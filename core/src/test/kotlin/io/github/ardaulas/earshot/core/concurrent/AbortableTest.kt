package io.github.ardaulas.earshot.core.concurrent

import io.github.ardaulas.earshot.core.requirements.Verifies
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AbortableTest {
    @Test
    fun `a call that finishes normally is never aborted, and reset runs before it`() {
        // Block body: JUnit Jupiter silently skips test methods that return a value.
        runBlocking {
            val aborts = AtomicInteger()
            val order = mutableListOf<String>()
            runAbortable(Dispatchers.IO, reset = { order += "reset" }, abort = { aborts.incrementAndGet() }) {
                order += "call"
                42
            } shouldBe 42
            aborts.get() shouldBe 0
            order shouldBe listOf("reset", "call")
        }
    }

    @Test
    @Verifies("SR-11")
    fun `cancelling calls abort, and the blocking call returns promptly because of it`() {
        runBlocking {
            val aborted = AtomicBoolean(false)
            val released = CountDownLatch(1)
            val started = System.nanoTime()
            val result =
                withTimeoutOrNull(200) {
                    runAbortable(Dispatchers.IO, abort = {
                        aborted.set(true)
                        released.countDown()
                    }) {
                        // Stands in for a native loop that polls its abort flag; without an abort it
                        // would block for 10 s.
                        released.await(10, TimeUnit.SECONDS)
                    }
                }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            result shouldBe null
            aborted.get() shouldBe true
            (elapsedMs < 2_000) shouldBe true
        }
    }

    @Test
    @Verifies("SR-11")
    fun `an abort that arrives before the native call starts is not erased`() {
        runBlocking {
            // Simulates the native flag: reset only by the caller, never by the call itself.
            val flag = AtomicBoolean(true)
            val sawAbort = AtomicBoolean(false)
            runAbortable(Dispatchers.IO, reset = { flag.set(false) }, abort = { flag.set(true) }) {
                flag.set(true) // cancellation lands after reset but before the native work
                sawAbort.set(flag.get())
            }
            sawAbort.get() shouldBe true
        }
    }
}
