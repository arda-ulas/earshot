// JNI bridge for llama.cpp. One handle owns a model, a context and an abort flag; calls on a handle are
// serialized by the Kotlin side. The system prompt and examples are decoded once ("prefix") and kept
// in the KV cache; each request trims the cache back to the prefix and decodes only the new turn.
#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <new>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "earshot-llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace {

struct Handle {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::string prefix;
    int n_prefix = 0;
    std::atomic<bool> abort{false};
};

bool abort_requested(void *data) { return static_cast<Handle *>(data)->abort.load(); }

std::string to_string(JNIEnv *env, jstring s) {
    if (s == nullptr) return std::string();
    const char *c = env->GetStringUTFChars(s, nullptr);
    if (c == nullptr) return std::string();  // OutOfMemoryError pending; the call then fails cleanly
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text, bool add_special) {
    int n = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, add_special, true);
    std::vector<llama_token> tokens(n > 0 ? n : 0);
    if (n > 0 && llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), tokens.data(), n, add_special, true) < 0) {
        tokens.clear();
    }
    return tokens;
}

// Decodes tokens in chunks of n_batch. Returns false on error or abort.
bool decode_all(Handle *h, std::vector<llama_token> &tokens) {
    const int n_batch = (int) llama_n_batch(h->ctx);
    for (size_t i = 0; i < tokens.size(); i += n_batch) {
        int n = std::min<int>(n_batch, (int) (tokens.size() - i));
        if (llama_decode(h->ctx, llama_batch_get_one(tokens.data() + i, n)) != 0) return false;
        if (h->abort.load()) return false;
    }
    return true;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_ardaulas_earshot_llama_LlamaNative_load(JNIEnv *env, jclass, jstring path, jint n_ctx, jint threads) {
    llama_backend_init();
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    const std::string model_path = to_string(env, path);
    if (env->ExceptionCheck() || model_path.empty()) return 0;
    llama_model *model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (model == nullptr) return 0;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t) n_ctx;
    cparams.n_batch = (uint32_t) n_ctx;
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;
    cparams.no_perf = true;
    llama_context *ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        llama_model_free(model);
        return 0;
    }
    auto *h = new (std::nothrow) Handle();
    if (h == nullptr) {
        llama_free(ctx);
        llama_model_free(model);
        return 0;
    }
    h->model = model;
    h->ctx = ctx;
    h->vocab = llama_model_get_vocab(model);
    llama_set_abort_callback(ctx, abort_requested, h);
    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_ardaulas_earshot_llama_LlamaNative_free(JNIEnv *, jclass, jlong ptr) {
    auto *h = reinterpret_cast<Handle *>(ptr);
    if (h == nullptr) return;
    llama_free(h->ctx);
    llama_model_free(h->model);
    delete h;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_ardaulas_earshot_llama_LlamaNative_resetAbort(JNIEnv *, jclass, jlong ptr) {
    auto *h = reinterpret_cast<Handle *>(ptr);
    if (h != nullptr) h->abort.store(false);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_ardaulas_earshot_llama_LlamaNative_abort(JNIEnv *, jclass, jlong ptr) {
    auto *h = reinterpret_cast<Handle *>(ptr);
    if (h != nullptr) h->abort.store(true);
}

// Formats messages with the model's own chat template. roles/contents are parallel arrays.
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_ardaulas_earshot_llama_LlamaNative_formatChat(
        JNIEnv *env, jclass, jlong ptr, jobjectArray roles, jobjectArray contents, jboolean add_assistant) {
    auto *h = reinterpret_cast<Handle *>(ptr);
    const char *tmpl = llama_model_chat_template(h->model, nullptr);
    if (tmpl == nullptr) {
        LOGI("model has no chat template");
        return nullptr;
    }
    const jsize n = env->GetArrayLength(roles);
    std::vector<std::string> r(n), c(n);
    std::vector<llama_chat_message> msgs(n);
    for (jsize i = 0; i < n; ++i) {
        r[i] = to_string(env, (jstring) env->GetObjectArrayElement(roles, i));
        c[i] = to_string(env, (jstring) env->GetObjectArrayElement(contents, i));
        msgs[i] = {r[i].c_str(), c[i].c_str()};
    }
    std::vector<char> buf(4096);
    int len = llama_chat_apply_template(tmpl, msgs.data(), n, add_assistant, buf.data(), (int32_t) buf.size());
    if (len > (int) buf.size()) {
        buf.resize(len);
        len = llama_chat_apply_template(tmpl, msgs.data(), n, add_assistant, buf.data(), (int32_t) buf.size());
    }
    if (len < 0) {
        LOGI("chat template not supported by llama_chat_apply_template");
        return nullptr;
    }
    return env->NewStringUTF(std::string(buf.data(), len).c_str());
}

// Generates a completion for prefix + suffix, constrained by a GBNF grammar, greedy. The prefix is
// decoded only when it differs from the cached one. Returns null on error or abort.
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_ardaulas_earshot_llama_LlamaNative_generate(
        JNIEnv *env, jclass, jlong ptr, jstring jprefix, jstring jsuffix, jstring jgrammar, jint max_tokens) {
    auto *h = reinterpret_cast<Handle *>(ptr);
    if (h == nullptr) return nullptr;
    // The abort flag is reset by the caller (resetAbort) before the call is published, never here.
    if (h->abort.load()) return nullptr;
    const std::string prefix = to_string(env, jprefix);
    const std::string suffix = to_string(env, jsuffix);
    const std::string grammar = to_string(env, jgrammar);
    if (env->ExceptionCheck()) return nullptr;
    llama_memory_t mem = llama_get_memory(h->ctx);

    if (!(prefix == h->prefix && h->n_prefix > 0)) {
        // The fixed system prompt and examples, logged once so the exact prompt can be checked with
        // adb logcat. The suffix holds the user's words and is never logged (TH-6).
        LOGI("prompt prefix:\n%s", prefix.c_str());
    }
    const int64_t t0 = llama_time_us();
    bool prefix_cached = prefix == h->prefix && h->n_prefix > 0;
    if (!prefix_cached) {
        llama_memory_clear(mem, true);
        h->prefix.clear();
        h->n_prefix = 0;
        std::vector<llama_token> ptoks = tokenize(h->vocab, prefix, true);
        if (!ptoks.empty() && !decode_all(h, ptoks)) {
            LOGI("prefix decode failed or aborted (%zu tokens)", ptoks.size());
            llama_memory_clear(mem, true);
            return nullptr;
        }
        h->prefix = prefix;
        h->n_prefix = (int) ptoks.size();
    } else {
        llama_memory_seq_rm(mem, 0, h->n_prefix, -1);
    }
    const int64_t t1 = llama_time_us();

    std::vector<llama_token> stoks = tokenize(h->vocab, suffix, h->n_prefix == 0);
    if (h->n_prefix + (int) stoks.size() + max_tokens > (int) llama_n_ctx(h->ctx)) {
        LOGI("prompt too long: %d + %zu + %d tokens", h->n_prefix, stoks.size(), max_tokens);
        return nullptr;
    }
    if (!decode_all(h, stoks)) {
        LOGI("prompt decode failed or aborted");
        return nullptr;
    }
    const int64_t t2 = llama_time_us();

    llama_sampler *chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler *g = llama_sampler_init_grammar(h->vocab, grammar.c_str(), "root");
    if (g == nullptr) {
        LOGI("grammar failed to parse");
        llama_sampler_free(chain);
        return nullptr;
    }
    llama_sampler_chain_add(chain, g);
    llama_sampler_chain_add(chain, llama_sampler_init_greedy());

    std::string out;
    int generated = 0;
    bool ok = true;
    for (; generated < max_tokens; ++generated) {
        if (h->abort.load()) {
            ok = false;
            break;
        }
        llama_token tok = llama_sampler_sample(chain, h->ctx, -1);
        if (llama_vocab_is_eog(h->vocab, tok)) break;
        char piece[256];
        int n = llama_token_to_piece(h->vocab, tok, piece, sizeof(piece), 0, true);
        if (n < 0) {
            ok = false;
            break;
        }
        out.append(piece, n);
        if (llama_decode(h->ctx, llama_batch_get_one(&tok, 1)) != 0) {
            ok = false;
            break;
        }
    }
    llama_sampler_free(chain);
    const int64_t t3 = llama_time_us();
    LOGI("prefix %s (%d tokens) %.0f ms, prompt %zu tokens %.0f ms, generated %d tokens %.0f ms",
         prefix_cached ? "cached" : "decoded", h->n_prefix, (t1 - t0) / 1000.0, stoks.size(), (t2 - t1) / 1000.0,
         generated, (t3 - t2) / 1000.0);
    if (!ok) return nullptr;
    return env->NewStringUTF(out.c_str());
}
