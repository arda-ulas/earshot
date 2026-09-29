package io.github.ardaulas.earshot.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import io.github.ardaulas.earshot.core.speech.AudioGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Push-to-talk capture: 16 kHz mono float PCM into a fixed buffer of at most
 * [AudioGate.MAX_SECONDS] (bounded audio buffer). Nothing is written to storage.
 */
class AudioCapture {
    private var record: AudioRecord? = null
    private var job: Job? = null
    private val buffer = FloatArray((AudioGate.MAX_SECONDS * AudioGate.SAMPLE_RATE).toInt())

    @Volatile private var length = 0

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
        r.startRecording()
        record = r
        job =
            scope.launch(Dispatchers.IO) {
                while (isActive && length < buffer.size) {
                    val n = r.read(buffer, length, minOf(CHUNK, buffer.size - length), AudioRecord.READ_BLOCKING)
                    if (n <= 0) break
                    length += n
                }
            }
        return true
    }

    /** Stops capturing and returns what was heard. */
    suspend fun stop(): FloatArray {
        val r = record ?: return FloatArray(0)
        record = null
        r.stop()
        job?.join()
        job = null
        r.release()
        return buffer.copyOf(length)
    }

    private companion object {
        const val CHUNK = 1_600
    }
}
