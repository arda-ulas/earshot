package io.github.ardaulas.earshot.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** Android text-to-speech, suspending until an utterance finishes so capture can stay off meanwhile (SG-8). */
class Speaker(
    context: Context,
) {
    private val _available = MutableStateFlow<Boolean?>(null)

    /** Null until the engine has initialized; false when there is no usable engine. */
    val available: StateFlow<Boolean?> = _available

    private val waiting = ConcurrentHashMap<String, (Boolean) -> Unit>()

    private val tts: TextToSpeech =
        TextToSpeech(context.applicationContext) { status ->
            _available.value = status == TextToSpeech.SUCCESS && configure()
        }

    /** Name of the offline voice in use, for the developer panel; null when none. */
    var voiceName: String? = null
        private set

    private fun configure(): Boolean {
        val lang = tts.setLanguage(Locale.US)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) return false
        // Offline only (audit #11): pick an installed English voice that needs no network, or fail
        // closed. Without INTERNET the app itself cannot send anything, but a network voice would run
        // in the speech engine's process under its own permissions.
        val offline =
            runCatching { tts.voices }
                .getOrNull()
                .orEmpty()
                .filter { v ->
                    v.locale.language == "en" &&
                        !v.isNetworkConnectionRequired &&
                        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features.orEmpty() &&
                        TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS !in v.features.orEmpty()
                }.sortedWith(compareBy({ it.locale != Locale.US }, { it.quality * -1 }))
        val voice = offline.firstOrNull() ?: return false
        if (tts.setVoice(voice) != TextToSpeech.SUCCESS || tts.voice?.isNetworkConnectionRequired != false) return false
        voiceName = voice.name
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) = Unit

                override fun onDone(utteranceId: String) {
                    waiting.remove(utteranceId)?.invoke(true)
                }

                override fun onStop(
                    utteranceId: String,
                    interrupted: Boolean,
                ) {
                    waiting.remove(utteranceId)?.invoke(false)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) {
                    waiting.remove(utteranceId)?.invoke(false)
                }

                override fun onError(
                    utteranceId: String,
                    errorCode: Int,
                ) {
                    waiting.remove(utteranceId)?.invoke(false)
                }
            },
        )
        return true
    }

    /** Speaks [text] and returns when done; false if nothing could be spoken. */
    suspend fun speak(text: String): Boolean {
        if (_available.value != true) return false
        val id = UUID.randomUUID().toString()
        return suspendCancellableCoroutine { cont ->
            waiting[id] = { ok -> if (cont.isActive) cont.resume(ok) }
            cont.invokeOnCancellation {
                waiting.remove(id)
                tts.stop()
            }
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
                waiting.remove(id)
                cont.resume(false)
            }
        }
    }

    /** Stops the current utterance; its speak() call returns false. */
    fun stop() {
        runCatching { tts.stop() }
        waiting.keys.toList().forEach { id -> waiting.remove(id)?.invoke(false) }
    }

    fun shutdown() = tts.shutdown()
}
