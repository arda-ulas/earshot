package io.github.ardaulas.earshot.core.speech

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads 16-bit PCM WAV into 16 kHz mono floats in -1..1, mixing channels down and resampling
 * linearly if needed. Used for pre-recorded clips; live audio comes straight from the microphone.
 */
object WavReader {
    /** Throws [IOException] for anything that is not a sane 16-bit PCM WAV; never another exception. */
    fun read(bytes: ByteArray): FloatArray =
        try {
            parse(bytes)
        } catch (e: IOException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            throw IOException("malformed WAV: ${e.javaClass.simpleName}")
        }

    private fun parse(bytes: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < HEADER || tag(buf, 0) != "RIFF" || tag(buf, 8) != "WAVE") throw IOException("not a WAV file")
        var pos = 12
        var channels = 0
        var rate = 0
        var bits = 0
        var format = 0
        while (pos + 8 <= bytes.size) {
            val id = tag(buf, pos)
            val size = buf.getInt(pos + 4)
            if (size < 0 || pos + 8 + size.toLong() > bytes.size) throw IOException("truncated chunk $id")
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    if (size < FMT_MIN) throw IOException("fmt chunk too short: $size")
                    format = buf.getShort(body).toInt()
                    channels = buf.getShort(body + 2).toInt()
                    rate = buf.getInt(body + 4)
                    bits = buf.getShort(body + 14).toInt()
                }

                "data" -> {
                    if (format != 1 || bits != 16 || channels !in 1..MAX_CHANNELS || rate !in RATES) {
                        throw IOException("need 16-bit PCM, got format=$format bits=$bits channels=$channels")
                    }
                    val frames = size / (2 * channels)
                    val mono = FloatArray(frames)
                    for (f in 0 until frames) {
                        var sum = 0f
                        for (c in 0 until channels) sum += buf.getShort(body + 2 * (f * channels + c)) / 32768f
                        mono[f] = sum / channels
                    }
                    return resample(mono, rate, AudioGate.SAMPLE_RATE)
                }
            }
            pos = body + size + (size and 1)
        }
        throw IOException("no data chunk")
    }

    private fun resample(
        input: FloatArray,
        from: Int,
        to: Int,
    ): FloatArray {
        if (from == to || input.isEmpty()) return input
        val out = FloatArray((input.size.toLong() * to / from).toInt())
        for (i in out.indices) {
            val x = i.toDouble() * from / to
            val i0 = x.toInt().coerceAtMost(input.size - 1)
            val i1 = (i0 + 1).coerceAtMost(input.size - 1)
            val f = (x - i0).toFloat()
            out[i] = input[i0] * (1 - f) + input[i1] * f
        }
        return out
    }

    private fun tag(
        buf: ByteBuffer,
        at: Int,
    ) = String(ByteArray(4) { buf.get(at + it) }, Charsets.US_ASCII)

    private const val HEADER = 12
    private const val FMT_MIN = 16
    private const val MAX_CHANNELS = 8

    /** Bounds the resampling ratio, so a hostile header cannot ask for a huge output buffer. */
    private val RATES = 8_000..96_000
}
