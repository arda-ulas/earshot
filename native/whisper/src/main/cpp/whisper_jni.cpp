// JNI bridge for whisper.cpp. One handle owns a whisper context and an abort flag; calls on a handle
// are serialized by the Kotlin side.
#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <string>
#include <vector>

#include "whisper.h"

#define TAG "earshot-whisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace {

struct Handle {
    whisper_context *ctx;
    std::atomic<bool> abort{false};
};

bool abort_requested(void *user_data) {
    return static_cast<Handle *>(user_data)->abort.load();
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_ardaulas_earshot_whisper_WhisperNative_init(JNIEnv *env, jclass, jstring model_path) {
    LOGI("system info: %s", whisper_print_system_info());
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    if (path == nullptr) return 0;  // OutOfMemoryError pending
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(model_path, path);
    if (ctx == nullptr) return 0;
    auto *handle = new Handle();
    handle->ctx = ctx;
    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_ardaulas_earshot_whisper_WhisperNative_free(JNIEnv *, jclass, jlong ptr) {
    auto *handle = reinterpret_cast<Handle *>(ptr);
    if (handle == nullptr) return;
    whisper_free(handle->ctx);
    delete handle;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_ardaulas_earshot_whisper_WhisperNative_resetAbort(JNIEnv *, jclass, jlong ptr) {
    auto *handle = reinterpret_cast<Handle *>(ptr);
    if (handle != nullptr) handle->abort.store(false);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_ardaulas_earshot_whisper_WhisperNative_abort(JNIEnv *, jclass, jlong ptr) {
    auto *handle = reinterpret_cast<Handle *>(ptr);
    if (handle != nullptr) handle->abort.store(true);
}

// Transcribes 16 kHz mono float PCM. Writes the mean probability of the text tokens (0..1, or -1 when
// there are none) into out_confidence[0]. Returns null if whisper fails or the call was aborted.
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_ardaulas_earshot_whisper_WhisperNative_transcribe(
        JNIEnv *env, jclass, jlong ptr, jfloatArray audio, jint threads, jfloatArray out_confidence) {
    auto *handle = reinterpret_cast<Handle *>(ptr);
    if (handle == nullptr) return nullptr;
    // The abort flag is reset by the caller (resetAbort) before the call is published, never here.

    const jsize n = env->GetArrayLength(audio);
    std::vector<float> pcm;
    try {
        pcm.resize(static_cast<size_t>(n));
    } catch (const std::bad_alloc &) {
        return nullptr;
    }
    env->GetFloatArrayRegion(audio, 0, n, pcm.data());
    if (env->ExceptionCheck()) return nullptr;

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.language = "en";
    params.translate = false;
    params.no_context = true;
    params.no_timestamps = true;
    params.single_segment = true;
    params.suppress_nst = true;
    // Bounded work: no temperature-fallback retries (they can take over a minute on unclear audio)
    // and a token cap well above a push-to-talk command.
    params.temperature_inc = 0.0f;
    params.max_tokens = 48;
    // The full 30 s encoder window is kept on purpose: shrinking it (audio_ctx) saved about 0.5 s on
    // the emulator but made whisper loop or stall on sub-second answers like "yes".
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.abort_callback = abort_requested;
    params.abort_callback_user_data = handle;

    whisper_reset_timings(handle->ctx);
    if (whisper_full(handle->ctx, params, pcm.data(), n) != 0 || handle->abort.load()) {
        return nullptr;
    }

    std::string text;
    double p_sum = 0.0;
    int p_count = 0;
    const whisper_token eot = whisper_token_eot(handle->ctx);
    const int segments = whisper_full_n_segments(handle->ctx);
    for (int s = 0; s < segments; ++s) {
        text += whisper_full_get_segment_text(handle->ctx, s);
        const int tokens = whisper_full_n_tokens(handle->ctx, s);
        for (int t = 0; t < tokens; ++t) {
            // Token ids at or above end-of-text are special (timestamps, language, task).
            if (whisper_full_get_token_id(handle->ctx, s, t) >= eot) continue;
            p_sum += whisper_full_get_token_p(handle->ctx, s, t);
            ++p_count;
        }
    }
    const float confidence = p_count > 0 ? static_cast<float>(p_sum / p_count) : -1.0f;
    env->SetFloatArrayRegion(out_confidence, 0, 1, &confidence);
    const whisper_timings *t = whisper_get_timings(handle->ctx);
    LOGI("transcribed %d samples (audio_ctx %d, %d threads): %d text tokens, mean p %.3f; "
         "encode %.0f ms, decode %.0f ms, batchd %.0f ms, prompt %.0f ms, sample %.0f ms",
         n, params.audio_ctx, threads, p_count, confidence,
         t ? t->encode_ms : -1.0f, t ? t->decode_ms : -1.0f, t ? t->batchd_ms : -1.0f,
         t ? t->prompt_ms : -1.0f, t ? t->sample_ms : -1.0f);
    return env->NewStringUTF(text.c_str());
}
