package io.github.ardaulas.earshot.core.concurrent

import io.github.ardaulas.earshot.core.requirements.Verifies
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AbortableTest {
    @Test
    fun `a call that finishes normally is never aborted`() =
        runBlocking {
            val aborts = AtomicInteger()
            runAbortable(Dispatchers.IO, abort = { aborts.incrementAndGet() }) { 42 } shouldBe 42
            aborts.get() shouldBe 0
        }

    @Test
    @Verifies("SR-11")
    fun `cancelling aborts the blocking call, which then returns`() =
        runBlocking {
            val released = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val result =
                withTimeoutOrNull(200) {
                    runAbortable(Dispatchers.IO, abort = { released.countDown() }) {
                        // Stands in for a native loop that polls its abort flag.
                        released.await(10, TimeUnit.SECONDS)
                        finished.countDown()
                    }
                }
            result shouldBe null
            finished.await(0, TimeUnit.SECONDS) shouldBe true
        }
}
