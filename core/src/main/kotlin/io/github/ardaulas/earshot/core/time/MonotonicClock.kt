package io.github.ardaulas.earshot.core.time

/** Monotonic time source. Injected everywhere so timing rules are tested with a fake clock, not sleeps. */
fun interface MonotonicClock {
    fun nanoTime(): Long

    fun millis(): Long = nanoTime() / 1_000_000

    companion object {
        val System = MonotonicClock { java.lang.System.nanoTime() }
    }
}
