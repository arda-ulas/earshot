// Host mirror of the app's language-model fallback path, for the regression harness.
//
//   lmhost [--threads N] [--ctx N] MODEL SPEC GRAMMAR   JSON lines: one user text per stdin line
//   lmhost [--threads N] --info                         one JSON line describing the build, then exit
//
// SPEC and GRAMMAR are described in README.md next to this file. Output per request:
//   {"raw": "{\"intent\":\"warmer\"}", "ms": 180.2}
// raw is exactly what the app's LlamaLmEngine.complete would return; the caller parses it (in the
// harness: core LmWireFormat.parse). On a failure (chat template, prompt too long, decode, grammar)
// raw is null and "error" says why; the app treats the same cases as "not understood".
//
// Mirrors native/llama/src/main/cpp/llama_jni.cpp (load, formatChat, generate, with the same prefix
// cache) and native/llama LlamaLmEngine.complete (how prefix and suffix are built). Defaults are the
// app's: n_ctx 1024, n_batch = n_ctx, 2 threads, CPU only, greedy with the GBNF grammar. Like the app,
// a warm-up request ("hello", 1 token) decodes the fixed prefix before the first real request, so ms
// is the steady-state cost of one request.
#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#include "jsonl.hpp"
#include "llama.h"

#ifndef ENGINE_VERSION
#define ENGINE_VERSION "unknown"
#endif
#ifndef ENGINE_SHA256
#define ENGINE_SHA256 "unknown"
#endif

namespace {

constexpr int DEFAULT_CTX = 1024;      // LlamaLmEngine.load contextTokens
constexpr int DEFAULT_THREADS = 2;     // LlamaLmEngine.load threads
constexpr int DEFAULT_MAX_TOKENS = 16; // core LmWireFormat.MAX_TOKENS

using Messages = std::vector<std::pair<std::string, std::string>>;

// ---- llama_jni.cpp ---------------------------------------------------------------------------------

struct Handle {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::string prefix;
    int n_prefix = 0;
};

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text, bool add_special) {
    int n = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, add_special, true);
    std::vector<llama_token> tokens(n > 0 ? n : 0);
    if (n > 0 && llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), tokens.data(), n, add_special, true) < 0) {
        tokens.clear();
    }
    return tokens;
}

bool decode_all(Handle *h, std::vector<llama_token> &tokens) {
    const int n_batch = (int) llama_n_batch(h->ctx);
    for (size_t i = 0; i < tokens.size(); i += n_batch) {
        int n = std::min<int>(n_batch, (int) (tokens.size() - i));
        if (llama_decode(h->ctx, llama_batch_get_one(tokens.data() + i, n)) != 0) return false;
    }
    return true;
}

// formatChat: the model's own chat template. Returns false if there is none or it is unsupported.
bool format_chat(Handle *h, const Messages &messages, bool add_assistant, std::string &out) {
    const char *tmpl = llama_model_chat_template(h->model, nullptr);
    if (tmpl == nullptr) return false;
    std::vector<llama_chat_message> msgs;
    for (auto &m : messages) msgs.push_back({m.first.c_str(), m.second.c_str()});
    std::vector<char> buf(4096);
    int len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), add_assistant, buf.data(), (int32_t) buf.size());
    if (len > (int) buf.size()) {
        buf.resize(len);
        len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), add_assistant, buf.data(), (int32_t) buf.size());
    }
    if (len < 0) return false;
    out.assign(buf.data(), len);
    return true;
}

// generate: prefix + suffix, GBNF-constrained greedy. The prefix is decoded only when it differs from
// the cached one. Returns false with a reason on error.
bool generate(Handle *h, const std::string &prefix, const std::string &suffix, const std::string &grammar,
              int max_tokens, std::string &out, std::string &error) {
    llama_memory_t mem = llama_get_memory(h->ctx);
    const bool prefix_cached = prefix == h->prefix && h->n_prefix > 0;
    if (!prefix_cached) {
        llama_memory_clear(mem, true);
        h->prefix.clear();
        h->n_prefix = 0;
        std::vector<llama_token> ptoks = tokenize(h->vocab, prefix, true);
        if (!ptoks.empty() && !decode_all(h, ptoks)) {
            llama_memory_clear(mem, true);
            error = "prefix decode failed";
            return false;
        }
        h->prefix = prefix;
        h->n_prefix = (int) ptoks.size();
    } else {
        llama_memory_seq_rm(mem, 0, h->n_prefix, -1);
    }

    std::vector<llama_token> stoks = tokenize(h->vocab, suffix, h->n_prefix == 0);
    if (h->n_prefix + (int) stoks.size() + max_tokens > (int) llama_n_ctx(h->ctx)) {
        error = "prompt too long";
        return false;
    }
    if (!decode_all(h, stoks)) {
        error = "prompt decode failed";
        return false;
    }

    llama_sampler *chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler *g = llama_sampler_init_grammar(h->vocab, grammar.c_str(), "root");
    if (g == nullptr) {
        llama_sampler_free(chain);
        error = "grammar failed to parse";
        return false;
    }
    llama_sampler_chain_add(chain, g);
    llama_sampler_chain_add(chain, llama_sampler_init_greedy());

    out.clear();
    bool ok = true;
    for (int generated = 0; generated < max_tokens; ++generated) {
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
    if (!ok) error = "generation failed";
    return ok;
}

// ---- LlamaLmEngine.complete ------------------------------------------------------------------------

struct Spec {
    Messages prefix_messages;  // system, then user/assistant example pairs
    std::string assistant_prefix;
    int max_tokens = DEFAULT_MAX_TOKENS;
};

bool complete(Handle *h, const Spec &spec, const std::string &user, const std::string &grammar, int max_tokens,
              std::string &out, std::string &error) {
    std::string prefix, full;
    Messages all = spec.prefix_messages;
    all.push_back({"user", user});
    if (!format_chat(h, spec.prefix_messages, false, prefix) || !format_chat(h, all, true, full)) {
        error = "chat template failed";
        return false;
    }
    full += spec.assistant_prefix;
    // The cached prefix is only reusable when the full prompt really starts with it.
    if (full.rfind(prefix, 0) == 0) return generate(h, prefix, full.substr(prefix.size()), grammar, max_tokens, out, error);
    return generate(h, "", full, grammar, max_tokens, out, error);
}

// ---- spec file -------------------------------------------------------------------------------------

// \n -> newline, \t -> tab, \\ -> backslash; any other backslash is kept as is.
std::string unescape(const std::string &s) {
    std::string o;
    for (size_t i = 0; i < s.size(); ++i) {
        if (s[i] == '\\' && i + 1 < s.size()) {
            const char n = s[i + 1];
            if (n == 'n') { o += '\n'; ++i; continue; }
            if (n == 't') { o += '\t'; ++i; continue; }
            if (n == '\\') { o += '\\'; ++i; continue; }
        }
        o += s[i];
    }
    return o;
}

bool read_spec(const std::string &path, Spec &spec, std::string &error) {
    std::ifstream f(path);
    if (!f) {
        error = "cannot open spec " + path;
        return false;
    }
    std::string line;
    int lineno = 0;
    bool have_system = false;
    while (std::getline(f, line)) {
        ++lineno;
        hostio::chomp(line);
        if (line.empty() || line[0] == '#') continue;
        const auto tab = line.find('\t');
        if (tab == std::string::npos) {
            error = "spec line " + std::to_string(lineno) + ": expected <key><TAB><value>";
            return false;
        }
        const std::string key = line.substr(0, tab);
        const std::string value = unescape(line.substr(tab + 1));
        if (key == "system") {
            if (have_system || !spec.prefix_messages.empty()) {
                error = "spec line " + std::to_string(lineno) + ": system must come first and only once";
                return false;
            }
            have_system = true;
            spec.prefix_messages.push_back({key, value});
        } else if (key == "user" || key == "assistant") {
            spec.prefix_messages.push_back({key, value});
        } else if (key == "assistant_prefix") {
            spec.assistant_prefix = value;
        } else if (key == "max_tokens") {
            spec.max_tokens = std::atoi(value.c_str());
            if (spec.max_tokens < 1 || spec.max_tokens > 256) {
                error = "spec line " + std::to_string(lineno) + ": max_tokens must be 1..256";
                return false;
            }
        } else {
            error = "spec line " + std::to_string(lineno) + ": unknown key '" + key + "'";
            return false;
        }
    }
    if (!have_system) {
        error = "spec has no system line";
        return false;
    }
    return true;
}

void print_info(int threads, int n_ctx) {
    llama_backend_init();
    hostio::emit(std::string("{\"engine\":\"llama.cpp\",\"version\":") + hostio::json_string(ENGINE_VERSION) +
                 ",\"source_sha256\":" + hostio::json_string(ENGINE_SHA256) + ",\"threads\":" +
                 std::to_string(threads) + ",\"n_ctx\":" + std::to_string(n_ctx) +
                 ",\"system_info\":" + hostio::json_string(llama_print_system_info()) + "}");
}

int usage() {
    std::fprintf(stderr, "usage: lmhost [--threads N] [--ctx N] MODEL SPEC GRAMMAR   (user texts on stdin)\n"
                         "       lmhost [--threads N] [--ctx N] --info\n");
    return 1;
}

}  // namespace

int main(int argc, char **argv) {
    int threads = DEFAULT_THREADS;
    int n_ctx = DEFAULT_CTX;
    bool info = false;
    std::vector<std::string> pos;
    for (int i = 1; i < argc; ++i) {
        const std::string a = argv[i];
        if (a == "--threads" && i + 1 < argc) threads = std::max(1, std::atoi(argv[++i]));
        else if (a == "--ctx" && i + 1 < argc) n_ctx = std::max(64, std::atoi(argv[++i]));
        else if (a == "--info") info = true;
        else if (a.rfind("--", 0) == 0) return usage();
        else pos.push_back(a);
    }
    if (info) {
        print_info(threads, n_ctx);
        return 0;
    }
    if (pos.size() != 3) return usage();

    Spec spec;
    std::string error;
    if (!read_spec(pos[1], spec, error)) {
        std::fprintf(stderr, "lmhost: %s\n", error.c_str());
        return 2;
    }
    std::ifstream gf(pos[2]);
    if (!gf) {
        std::fprintf(stderr, "lmhost: cannot open grammar %s\n", pos[2].c_str());
        return 2;
    }
    std::stringstream gs;
    gs << gf.rdbuf();
    const std::string grammar = gs.str();

    llama_log_set([](ggml_log_level, const char *, void *) {}, nullptr);
    llama_backend_init();
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(pos[0].c_str(), mparams);
    if (model == nullptr) {
        std::fprintf(stderr, "lmhost: cannot load model %s\n", pos[0].c_str());
        return 2;
    }
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t) n_ctx;
    cparams.n_batch = (uint32_t) n_ctx;
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;
    cparams.no_perf = true;
    llama_context *ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        std::fprintf(stderr, "lmhost: cannot create context\n");
        llama_model_free(model);
        return 2;
    }
    Handle h;
    h.model = model;
    h.ctx = ctx;
    h.vocab = llama_model_get_vocab(model);

    // Warm-up, as the app does after loading: decodes the fixed prefix once. A failure here (bad
    // grammar, no chat template) would fail every request, so it stops the process instead.
    std::string out;
    const double w0 = hostio::now_ms();
    if (!complete(&h, spec, "hello", grammar, 1, out, error)) {
        std::fprintf(stderr, "lmhost: warm-up failed: %s\n", error.c_str());
        return 3;
    }
    std::fprintf(stderr, "lmhost: llama.cpp %s, %d threads, n_ctx %d, prefix %d tokens, warm-up %.0f ms, %s\n",
                 ENGINE_VERSION, threads, n_ctx, h.n_prefix, hostio::now_ms() - w0, llama_print_system_info());

    std::string user;
    while (std::getline(std::cin, user)) {
        hostio::chomp(user);
        const double t0 = hostio::now_ms();
        const bool ok = complete(&h, spec, user, grammar, spec.max_tokens, out, error);
        const std::string ms = hostio::json_number(hostio::now_ms() - t0);
        if (ok) {
            hostio::emit("{\"raw\":" + hostio::json_string(out) + ",\"ms\":" + ms + "}");
        } else {
            hostio::emit("{\"raw\":null,\"ms\":" + ms + ",\"error\":" + hostio::json_string(error) + "}");
        }
    }
    llama_free(ctx);
    llama_model_free(model);
    return 0;
}
