package io.github.ardaulas.earshot.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import io.github.ardaulas.earshot.core.speech.AudioGate
import io.github.ardaulas.earshot.core.time.MonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One push-to-talk utterance: samples, when capture started and ended, and whether it hit the limit. */
class Captured(
    val pcm: FloatArray,
    val startMs: Long,
    val endMs: Long,
    val overflowed: Boolean,
)

/**
 * Push-to-talk capture: 16 kHz mono float PCM into a fixed buffer of at most [AudioGate.MAX_SECONDS]
 * (bounded audio buffer). Capture stops by itself at the limit and reports the overflow, so a long
 * hold never turns old words into a fresh command (audit #9). The recorder is always released, also on
 * cancellation or lifecycle loss (audit #10). Nothing is written to storage, and the buffer is cleared
 * after each utterance.
 */
class AudioCapture(
    private val clock: MonotonicClock,
) {
    private var record: AudioRecord? = null
    private var job: Job? = null
    private val buffer = FloatArray((AudioGate.MAX_SECONDS * AudioGate.SAMPLE_RATE).toInt())

    @Volatile private var length = 0

    @Volatile private var startMs = 0L

    @Volatile private var limitReachedAtMs: Long? = null

    /** Starts capturing. The caller must hold RECORD_AUDIO. Returns false if the microphone is unavailable. */
    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope): Boolean {
        if (record != null) return true
        val minBuffer =
            AudioRecord.getMinBufferSize(AudioGate.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        if (minBuffer <= 0) return false
        val r =
            try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    AudioGate.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT,
                    minBuffer * 2,
                )
            } catch (e: IllegalArgumentException) {
                return false
            }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        length = 0
        limitReachedAtMs = null
        startMs = clock.millis()
        r.startRecording()
        record = r
        job =
            scope.launch(Dispatchers.IO) {
                while (isActive && length < buffer.size) {
                    val n = r.read(buffer, length, minOf(CHUNK, buffer.size - length), AudioRecord.READ_BLOCKING)
                    if (n <= 0) break
                    length += n
                }
                if (length >= buffer.size) {
                    // Limit reached: stop listening now; the utterance will be rejected as too long.
                    limitReachedAtMs = clock.millis()
                    runCatching { r.stop() }
                }
            }
        return true
    }

    /** Stops capturing and returns what was heard, with the actual capture end time. */
    suspend fun stop(): Captured {
        val r = record ?: return Captured(FloatArray(0), clock.millis(), clock.millis(), false)
        record = null
        val releasedAt = clock.millis()
        try {
            runCatching { r.stop() }
            withContext(NonCancellable) { job?.join() }
        } finally {
            job = null
            r.release()
        }
        val overflowed = limitReachedAtMs != null
        val pcm = buffer.copyOf(length)
        buffer.fill(0f, 0, length)
        length = 0
        return Captured(pcm, startMs, limitReachedAtMs ?: releasedAt, overflowed)
    }

    /** Lifecycle loss (activity stopped, key-up never came): stop and release, keep nothing. */
    fun abort() {
        val r = record ?: return
        record = null
        job?.cancel()
        job = null
        runCatching { r.stop() }
        r.release()
        buffer.fill(0f)
        length = 0
    }

    val isCapturing: Boolean get() = record != null

    private companion object {
        const val CHUNK = 1_600
    }
}
