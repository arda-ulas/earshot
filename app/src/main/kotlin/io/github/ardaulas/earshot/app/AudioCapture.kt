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
 * Push-to-talk capture: 16 kHz mono float PCM, at most [AudioGate.MAX_SECONDS] per utterance (bounded
 * buffer). Each utterance is its own [Session] with its own buffer and reader, so an old reader can
 * never touch a new capture (re-audit #10). At the limit the reader stops and releases the recorder
 * itself and marks the overflow, so a long hold never turns old words into a fresh command (audit #9).
 * The recorder is released exactly once, also on cancellation or lifecycle loss. Nothing is written to
 * storage, and buffers are cleared after use.
 */
class AudioCapture(
    private val clock: MonotonicClock,
) {
    private class Session(
        val record: AudioRecord,
        val startMs: Long,
    ) {
        val buffer = FloatArray((AudioGate.MAX_SECONDS * AudioGate.SAMPLE_RATE).toInt())

        @Volatile var length = 0

        @Volatile var limitReachedAtMs: Long? = null
        var job: Job? = null
        private var released = false

        @Synchronized
        fun release() {
            if (released) return
            released = true
            runCatching { record.stop() }
            record.release()
        }
    }

    @Volatile private var session: Session? = null

    /** Starts capturing. The caller must hold RECORD_AUDIO. Returns false if the microphone is unavailable. */
    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope): Boolean {
        if (session != null) return true
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
        val s = Session(r, clock.millis())
        r.startRecording()
        session = s
        s.job =
            scope.launch(Dispatchers.IO) {
                try {
                    while (isActive && s.length < s.buffer.size) {
                        val n = r.read(s.buffer, s.length, minOf(CHUNK, s.buffer.size - s.length), AudioRecord.READ_BLOCKING)
                        if (n <= 0) break
                        s.length += n
                    }
                    if (s.length >= s.buffer.size) s.limitReachedAtMs = clock.millis()
                } finally {
                    // At the limit (or on any exit) the microphone is let go at once, even if key-up never comes.
                    if (s.limitReachedAtMs != null) s.release()
                }
            }
        return true
    }

    /** Stops capturing and returns what was heard, with the actual capture end time. */
    suspend fun stop(): Captured {
        val s = session ?: return Captured(FloatArray(0), clock.millis(), clock.millis(), false)
        session = null
        val releasedAt = clock.millis()
        try {
            runCatching { s.record.stop() }
            withContext(NonCancellable) { s.job?.join() }
        } finally {
            s.release()
        }
        val pcm = s.buffer.copyOf(s.length)
        s.buffer.fill(0f)
        val limit = s.limitReachedAtMs
        return Captured(pcm, s.startMs, limit ?: releasedAt, overflowed = limit != null)
    }

    /** Lifecycle loss (activity stopped, key-up never came): stop and release, keep nothing. */
    fun abort() {
        val s = session ?: return
        session = null
        s.job?.cancel()
        s.release()
        s.buffer.fill(0f)
    }

    private companion object {
        const val CHUNK = 1_600
    }
}
