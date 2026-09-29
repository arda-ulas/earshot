package io.github.ardaulas.earshot.core.concurrent

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a blocking native call on [dispatcher] so that cancelling the coroutine calls [abort], which
 * must make [block] return early. Without this a timed-out model call would keep the CPU busy.
 *
 * [abort] is never called after [block] has returned, and this function does not return until any
 * [abort] call has finished, so the native side may reset its abort flag at the start of each call.
 */
suspend fun <T> runAbortable(
    dispatcher: CoroutineDispatcher,
    abort: () -> Unit,
    block: () -> T,
): T =
    coroutineScope {
        val done = AtomicBoolean(false)
        val watcher =
            launch {
                try {
                    awaitCancellation()
                } finally {
                    if (!done.get()) abort()
                }
            }
        try {
            withContext(dispatcher) { block() }
        } finally {
            done.set(true)
            withContext(NonCancellable) { watcher.cancelAndJoin() }
        }
    }
