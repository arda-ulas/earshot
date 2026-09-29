// Host mirror of native/llama/src/main/cpp/llama_jni.cpp, for evaluating models and prompts.
// Usage: lmhost MODEL SPEC GRAMMAR < utterances
//   SPEC: lines "role<TAB>content" (system/user/assistant) with "\n" escaped as "\\n"; an optional
//   line "assistant_prefix<TAB>text" is appended after the assistant turn marker.
// Prints one generated line per input utterance.
#include <algorithm>
#include <cstdio>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>
#include "llama.h"

static std::string unescape(const std::string &s) {
    std::string o; for (size_t i = 0; i < s.size(); ++i) {
        if (s[i] == '\\' && i + 1 < s.size() && s[i + 1] == 'n') { o += '\n'; ++i; } else o += s[i]; }
    return o;
}
static std::vector<llama_token> tok(const llama_vocab *v, const std::string &t, bool add_special) {
    int n = -llama_tokenize(v, t.c_str(), (int) t.size(), nullptr, 0, add_special, true);
    std::vector<llama_token> r(n); llama_tokenize(v, t.c_str(), (int) t.size(), r.data(), n, add_special, true); return r;
}
static std::string fmt(llama_model *m, const std::vector<std::pair<std::string,std::string>> &msgs, bool add_ass) {
    const char *tmpl = llama_model_chat_template(m, nullptr);
    std::vector<llama_chat_message> c; for (auto &p : msgs) c.push_back({p.first.c_str(), p.second.c_str()});
    std::vector<char> buf(16384);
    int n = llama_chat_apply_template(tmpl, c.data(), c.size(), add_ass, buf.data(), buf.size());
    if (n < 0) { fprintf(stderr, "template failed\n"); exit(2); }
    return std::string(buf.data(), n);
}
static bool decode_all(llama_context *ctx, std::vector<llama_token> &t) {
    int nb = llama_n_batch(ctx);
    for (size_t i = 0; i < t.size(); i += nb) {
        int n = std::min<int>(nb, t.size() - i);
        if (llama_decode(ctx, llama_batch_get_one(t.data() + i, n))) return false; }
    return true;
}
int main(int argc, char **argv) {
    if (argc < 4) { fprintf(stderr, "usage\n"); return 1; }
    llama_log_set([](ggml_log_level, const char *, void *) {}, nullptr);
    llama_backend_init();
    auto mp = llama_model_default_params(); mp.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(argv[1], mp);
    auto cp = llama_context_default_params(); cp.n_ctx = 1024; cp.n_batch = 1024; cp.n_threads = 2; cp.n_threads_batch = 2; cp.no_perf = true;
    llama_context *ctx = llama_init_from_model(model, cp);
    const llama_vocab *vocab = llama_model_get_vocab(model);
    std::vector<std::pair<std::string,std::string>> msgs; std::string apfx;
    std::ifstream spec(argv[2]); std::string line;
    while (std::getline(spec, line)) { auto t = line.find('\t'); if (t == std::string::npos) continue;
        auto role = line.substr(0, t), content = unescape(line.substr(t + 1));
        if (role == "assistant_prefix") apfx = content; else msgs.push_back({role, content}); }
    std::stringstream gs; gs << std::ifstream(argv[3]).rdbuf(); std::string grammar = gs.str();
    std::string prefix = fmt(model, msgs, false);
    auto ptoks = tok(vocab, prefix, true); decode_all(ctx, ptoks);
    int n_prefix = ptoks.size();
    fprintf(stderr, "prefix tokens: %d\n", n_prefix);
    std::string user;
    while (std::getline(std::cin, user)) {
        auto all = msgs; all.push_back({"user", user});
        std::string full = fmt(model, all, true) + apfx;
        if (full.rfind(prefix, 0) != 0) { fprintf(stderr, "prefix mismatch\n"); return 3; }
        std::string suffix = full.substr(prefix.size());
        llama_memory_seq_rm(llama_get_memory(ctx), 0, n_prefix, -1);
        auto st = tok(vocab, suffix, false); decode_all(ctx, st);
        auto *chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
        auto *g = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
        if (!g) { fprintf(stderr, "grammar failed\n"); return 4; }
        llama_sampler_chain_add(chain, g); llama_sampler_chain_add(chain, llama_sampler_init_greedy());
        std::string out;
        for (int i = 0; i < 40; ++i) {
            llama_token t = llama_sampler_sample(chain, ctx, -1);
            if (llama_vocab_is_eog(vocab, t)) break;
            char p[256]; int n = llama_token_to_piece(vocab, t, p, sizeof p, 0, true); out.append(p, n);
            if (llama_decode(ctx, llama_batch_get_one(&t, 1))) break;
        }
        llama_sampler_free(chain);
        printf("%s\n", out.c_str()); fflush(stdout);
    }
    return 0;
}
