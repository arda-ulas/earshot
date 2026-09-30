package io.github.ardaulas.earshot.core.speech

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavReaderTest {
    private fun wav(
        samples: ShortArray,
        rate: Int,
        channels: Int,
    ): ByteArray {
        val data = samples.size * 2
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray())
        b.putInt(36 + data)
        b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray())
        b.putInt(16)
        b.putShort(1)
        b.putShort(channels.toShort())
        b.putInt(rate)
        b.putInt(rate * channels * 2)
        b.putShort((channels * 2).toShort())
        b.putShort(16)
        b.put("data".toByteArray())
        b.putInt(data)
        samples.forEach { b.putShort(it) }
        return b.array()
    }

    @Test
    fun `reads 16 kHz mono as floats`() {
        val pcm = WavReader.read(wav(shortArrayOf(0, 16384, -32768), 16_000, 1))
        pcm.toList() shouldBe listOf(0f, 0.5f, -1f)
    }

    @Test
    fun `mixes stereo down and resamples to 16 kHz`() {
        val stereo = ShortArray(32_000 * 2) { if (it % 2 == 0) 8192 else 0 }
        val pcm = WavReader.read(wav(stereo, 32_000, 2))
        pcm.size shouldBe 16_000
        pcm[100] shouldBe 0.125f
    }

    @Test
    fun `rejects non-WAV and non-PCM input`() {
        assertThrows<IOException> { WavReader.read(ByteArray(10)) }
        val float = wav(shortArrayOf(0), 16_000, 1).also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(20, 3) }
        assertThrows<IOException> { WavReader.read(float) }
    }

    @Test
    fun `malformed headers become IOException, never a crash`() {
        val good = wav(shortArrayOf(0, 1, 2), 16_000, 1)
        // fmt chunk claiming 4 bytes: fields would be read past the chunk.
        val shortFmt = good.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 4) }
        assertThrows<IOException> { WavReader.read(shortFmt) }
        // A sample rate of 1 Hz would ask the resampler for a 16 000x larger buffer.
        val tinyRate = good.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(24, 1) }
        assertThrows<IOException> { WavReader.read(tinyRate) }
        // Truncated anywhere: always IOException.
        for (n in 0 until good.size) {
            try {
                WavReader.read(good.copyOf(n))
            } catch (e: IOException) {
                // expected
            }
        }
    }
}
