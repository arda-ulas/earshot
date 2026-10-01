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

/**
 * One push-to-talk utterance: samples, when capture started and ended, whether it hit the limit, and
 * whether the microphone failed before key-up (then the samples are only a prefix and must not be used).
 */
class Captured(
    val pcm: FloatArray,
    val startMs: Long,
    val endMs: Long,
    val overflowed: Boolean,
    val failed: Boolean = false,
)

/**
 * Push-to-talk capture: 16 kHz mono float PCM, at most [AudioGate.MAX_SECONDS] per utterance (bounded
 * buffer). Each utterance is its own [Session] with its own buffer and reader, so an old reader can
 * never touch a new capture (re-audit #10). At the limit the reader stops and releases the recorder
 * itself and marks the overflow, so a long hold never turns old words into a fresh command (audit #9).
 * A read error before key-up does the same and marks the utterance failed: a prefix is never passed on
 * as a whole request (re-audit 3, N13).
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

        @Volatile var failedAtMs: Long? = null

        /** When the first samples arrived: recorder start-up time is not audio missing. */
        @Volatile var firstSamplesAtMs: Long? = null
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
        try {
            r.startRecording()
        } catch (e: IllegalStateException) {
            s.release()
            return false
        }
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            s.release()
            return false
        }
        session = s
        s.job =
            scope.launch(Dispatchers.IO) {
                try {
                    while (isActive && s.length < s.buffer.size) {
                        val n = r.read(s.buffer, s.length, minOf(CHUNK, s.buffer.size - s.length), AudioRecord.READ_BLOCKING)
                        if (n <= 0) {
                            // An error, or the recorder stopped. After key-up that is the normal end;
                            // before it, the utterance is incomplete (re-audit 3, N13).
                            // A negative value is an error code, whatever key-up did meanwhile; zero
                            // is the normal end only once key-up has taken the session.
                            if (n < 0 || session === s) s.failedAtMs = clock.millis()
                            break
                        }
                        if (s.firstSamplesAtMs == null) s.firstSamplesAtMs = clock.millis() - n * 1000L / AudioGate.SAMPLE_RATE
                        s.length += n
                    }
                    if (s.length >= s.buffer.size) s.limitReachedAtMs = clock.millis()
                } finally {
                    // At the limit or on a failure the microphone is let go at once, even if key-up never comes.
                    if (s.limitReachedAtMs != null || s.failedAtMs != null) s.release()
                }
            }
        return true
    }

    /** Stops capturing and returns what was heard, with the actual capture end time. */
    suspend fun stop(): Captured {
        val s = session ?: return Captured(FloatArray(0), clock.millis(), clock.millis(), false)
        session = null
        val releasedAt = clock.millis()
        // Asked before stopping it, and independent of the reader thread: a recorder that is no
        // longer recording at key-up stopped on its own, so the audio is incomplete whatever the
        // reader had time to note (re-audit 7, N13).
        val stoppedByItself =
            s.limitReachedAtMs == null && runCatching { s.record.recordingState != AudioRecord.RECORDSTATE_RECORDING }.getOrDefault(true)
        try {
            runCatching { s.record.stop() }
            withContext(NonCancellable) { s.job?.join() }
        } finally {
            s.release()
        }
        val pcm = s.buffer.copyOf(s.length)
        s.buffer.fill(0f)
        val limit = s.limitReachedAtMs
        // Audio missing from the hold is a failure too, whatever the reader saw: a read that ended
        // early, recorded or not before key-up, leaves the samples short of the time since the first
        // samples arrived (re-audit 4 to 6, N13). 150 ms (one 100 ms read and margin) covers
        // buffering, so a lost correction would have to be shorter than that; a recorder that never
        // delivered anything during a hold of more than 300 ms has failed as well.
        val since = s.firstSamplesAtMs ?: s.startMs
        val heldSamples = (releasedAt - since) * AudioGate.SAMPLE_RATE / 1000
        val missing = limit == null && s.length < heldSamples - AudioGate.SAMPLE_RATE * 15 / 100
        val failed = s.failedAtMs != null || missing || (stoppedByItself && limit == null)
        if (failed) pcm.fill(0f)
        return Captured(pcm, s.startMs, limit ?: s.failedAtMs ?: releasedAt, overflowed = limit != null, failed = failed)
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
